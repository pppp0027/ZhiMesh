package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.TeamEditReq;
import com.pppp.zhimesh.common.dto.TeamInfoResp;
import com.pppp.zhimesh.common.dto.TeamMemberAddReq;
import com.pppp.zhimesh.common.dto.TeamMemberResp;
import com.pppp.zhimesh.common.dto.TeamMemberUpdateReq;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.Team;
import com.pppp.zhimesh.common.entity.TeamMember;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.TeamRoleEnum;
import com.pppp.zhimesh.common.enums.UserStatusEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.mapper.TeamMapper;
import com.pppp.zhimesh.common.mapper.TeamMemberMapper;
import com.pppp.zhimesh.common.util.UuidUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.enums.ErrorEnum.*;

/**
 * 团队管理服务：团队 CRUD 与成员管理（邀请、改角色、移除、退出）。
 *
 * <p>团队是用户空间的一等实体，管理权只来自 OWNER 成员身份——管理员会话不获得
 * 额外的团队管理权（保持"user session stays a user session"惯例，管理端只负责
 * 企业知识库）。团队知识库的访问控制不在此层，统一走
 * {@link KnowledgeBaseAccessService} 从成员关系推导。
 *
 * <p>依赖注入只用 mapper（KnowledgeBaseMapper 而非 KnowledgeBaseService），
 * 避免与知识库侧形成服务循环依赖。
 */
@Slf4j
@Service
public class TeamService extends ServiceImpl<TeamMapper, Team> {

    @Resource
    private TeamMemberMapper teamMemberMapper;

    @Resource
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private UserService userService;

    // ========== 查询 ==========

    /**
     * 分页搜索我所在的团队（按名称/备注模糊匹配）。
     */
    public Page<TeamInfoResp> searchMyTeams(String keyword, Integer currentPage, Integer pageSize) {
        User user = currentUser();
        Page<TeamInfoResp> result = new Page<>(currentPage, pageSize);
        Map<Long, String> roleByTeamId = myRoleByTeamId(user.getId());
        if (roleByTeamId.isEmpty()) {
            return result;
        }
        Page<Team> teamPage = lambdaQuery()
                .in(Team::getId, roleByTeamId.keySet())
                .and(StringUtils.isNotBlank(keyword), w -> w
                        .like(Team::getName, keyword)
                        .or()
                        .like(Team::getRemark, keyword))
                .orderByDesc(Team::getId)
                .page(new Page<>(currentPage, pageSize));
        result.setTotal(teamPage.getTotal());
        result.setRecords(teamPage.getRecords().stream()
                .map(team -> toInfoResp(team, roleByTeamId.get(team.getId()), countMembers(team.getId())))
                .toList());
        return result;
    }

    /**
     * 我所在的团队轻量列表（建库/转移弹窗用），按名称排序。
     */
    public List<TeamInfoResp> myTeamsLite() {
        User user = currentUser();
        Map<Long, String> roleByTeamId = myRoleByTeamId(user.getId());
        if (roleByTeamId.isEmpty()) {
            return List.of();
        }
        return listByIds(roleByTeamId.keySet()).stream()
                .sorted(Comparator.comparing(Team::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(team -> toInfoResp(team, roleByTeamId.get(team.getId()), null))
                .toList();
    }

    // ========== 团队 CRUD ==========

    /**
     * 新建或编辑团队。新建自动写入创建者的 OWNER 成员记录；编辑（改名/备注）仅 OWNER。
     */
    @Transactional
    public Team saveOrUpdate(TeamEditReq teamEditReq) {
        User user = currentUser();
        if (null == teamEditReq.getId() || teamEditReq.getId() < 1) {
            Team team = new Team();
            team.setUuid(UuidUtil.createShort());
            team.setName(StringUtils.trim(teamEditReq.getName()));
            team.setRemark(teamEditReq.getRemark());
            team.setCreatorId(user.getId());
            save(team);
            TeamMember creatorMembership = new TeamMember();
            creatorMembership.setTeamId(team.getId());
            creatorMembership.setUserId(user.getId());
            creatorMembership.setRole(TeamRoleEnum.OWNER.getValue());
            teamMemberMapper.insert(creatorMembership);
            log.info("Team created, uuid:{}, name:{}, creatorId:{}", team.getUuid(), team.getName(), user.getId());
            return team;
        }
        Team team = getEditableOrThrow(teamEditReq.getId(), teamEditReq.getUuid());
        requireOwner(team, user);
        team.setName(StringUtils.trim(teamEditReq.getName()));
        team.setRemark(teamEditReq.getRemark());
        updateById(team);
        return team;
    }

    /**
     * 删除团队。仅 OWNER 可删；名下仍有团队知识库时拒绝（A_TEAM_HAS_KB），
     * 避免产生无主团队库。成员记录随团队一并清理。
     */
    @Transactional
    public void delete(String uuid) {
        User user = currentUser();
        Team team = lockOrThrow(uuid);
        requireOwner(team, user);
        Long kbCount = knowledgeBaseMapper.selectCount(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getTeamId, team.getId()));
        if (kbCount != null && kbCount > 0) {
            throw new BaseException(A_TEAM_HAS_KB);
        }
        teamMemberMapper.delete(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, team.getId()));
        removeById(team.getId());
        log.info("Team deleted, uuid:{}, operatorId:{}", uuid, user.getId());
    }

    // ========== 成员管理 ==========

    /**
     * 团队花名册。任一成员可看；非成员按团队不存在处理，避免探测。
     */
    public List<TeamMemberResp> listMembers(String teamUuid) {
        User user = currentUser();
        Team team = getOrThrow(teamUuid);
        requireMember(team, user);
        List<TeamMember> members = teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, team.getId())
                .orderByAsc(TeamMember::getId));
        if (members.isEmpty()) {
            return List.of();
        }
        Map<Long, User> userById = userService.listByIds(members.stream()
                        .map(TeamMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        return members.stream()
                .map(member -> toMemberResp(member, userById.get(member.getUserId())))
                .toList();
    }

    /**
     * 按邮箱邀请成员。仅 OWNER 可邀请；邮箱是唯一身份标识；非 NORMAL 状态用户
     * 按不存在拒绝；并发重复邀请由 uk_team_member 兜底映射为 A_TEAM_MEMBER_EXIST。
     * 不允许直接以 OWNER 身份邀请——所有权转让统一走 updateMemberRole 的
     * last-owner 保护。
     */
    @Transactional
    public TeamMemberResp addMember(TeamMemberAddReq addReq) {
        User user = currentUser();
        Team team = getOrThrow(addReq.getTeamUuid());
        requireOwner(team, user);
        User invitee = userService.getByEmail(StringUtils.trim(addReq.getEmail()));
        if (UserStatusEnum.NORMAL != invitee.getUserStatus()) {
            // 非正常状态账号一律按不存在处理，避免通过邀请探测账号状态。
            throw new BaseException(A_USER_NOT_EXIST);
        }
        TeamRoleEnum role = StringUtils.isBlank(addReq.getRole())
                ? TeamRoleEnum.CONTRIBUTOR
                : TeamRoleEnum.getByValue(addReq.getRole());
        if (null == role || TeamRoleEnum.OWNER == role) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        TeamMember membership = new TeamMember();
        membership.setTeamId(team.getId());
        membership.setUserId(invitee.getId());
        membership.setRole(role.getValue());
        try {
            teamMemberMapper.insert(membership);
        } catch (DuplicateKeyException e) {
            throw new BaseException(A_TEAM_MEMBER_EXIST);
        }
        log.info("Team member added, teamUuid:{}, userId:{}, role:{}, operatorId:{}",
                team.getUuid(), invitee.getId(), role.getValue(), user.getId());
        return toMemberResp(membership, invitee);
    }

    /**
     * 调整成员角色。仅 OWNER 可调；唯一的 OWNER 被降级时拒绝（A_TEAM_LAST_OWNER），
     * OWNER 想退出的正规路径是先把所有权转让给他人。
     */
    @Transactional
    public void updateMemberRole(TeamMemberUpdateReq updateReq) {
        User user = currentUser();
        Team team = lockOrThrow(updateReq.getTeamUuid());
        requireOwner(team, user);
        TeamRoleEnum newRole = TeamRoleEnum.getByValue(updateReq.getRole());
        if (null == newRole) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        TeamMember membership = membership(team.getId(), updateReq.getUserId());
        if (null == membership) {
            throw new BaseException(A_TEAM_MEMBER_NOT_EXIST);
        }
        if (TeamRoleEnum.OWNER.getValue().equals(membership.getRole())
                && TeamRoleEnum.OWNER != newRole) {
            requireMultipleOwners(team.getId());
        }
        membership.setRole(newRole.getValue());
        teamMemberMapper.updateById(membership);
        log.info("Team member role updated, teamUuid:{}, userId:{}, role:{}, operatorId:{}",
                team.getUuid(), updateReq.getUserId(), newRole.getValue(), user.getId());
    }

    /**
     * 移除成员。仅 OWNER 可移除；被移除者在本团队全部知识库上的权限随成员记录
     * 一并回收。移除的是唯一 OWNER 时拒绝。
     */
    @Transactional
    public void removeMember(String teamUuid, Long userId) {
        User user = currentUser();
        Team team = lockOrThrow(teamUuid);
        requireOwner(team, user);
        TeamMember membership = membership(team.getId(), userId);
        if (null == membership) {
            throw new BaseException(A_TEAM_MEMBER_NOT_EXIST);
        }
        if (TeamRoleEnum.OWNER.getValue().equals(membership.getRole())) {
            requireMultipleOwners(team.getId());
        }
        teamMemberMapper.deleteById(membership.getId());
        log.info("Team member removed, teamUuid:{}, userId:{}, operatorId:{}", teamUuid, userId, user.getId());
    }

    /**
     * 退出团队。OWNER 不可直接退出（A_TEAM_OWNER_CANNOT_LEAVE），须先转让所有权
     * 或删除团队；退出后权限随成员记录回收。
     */
    @Transactional
    public void leave(String uuid) {
        User user = currentUser();
        Team team = getOrThrow(uuid);
        TeamMember membership = membership(team.getId(), user.getId());
        if (null == membership) {
            throw new BaseException(A_TEAM_NOT_MEMBER);
        }
        if (TeamRoleEnum.OWNER.getValue().equals(membership.getRole())) {
            throw new BaseException(A_TEAM_OWNER_CANNOT_LEAVE);
        }
        teamMemberMapper.deleteById(membership.getId());
        log.info("Team member left, teamUuid:{}, userId:{}", uuid, user.getId());
    }

    // ========== 供知识库侧复用 ==========

    public Team getOrThrow(String uuid) {
        if (StringUtils.isBlank(uuid)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        Team team = lambdaQuery().eq(Team::getUuid, uuid).one();
        if (null == team) {
            throw new BaseException(A_TEAM_NOT_FOUND);
        }
        return team;
    }

    /**
     * 事务内锁定团队行（SELECT ... FOR UPDATE）。团队删除、成员移除/降级与
     * 建库/转移（KnowledgeBaseService#getTeamOrThrow 同口径加锁）以此串行化，
     * 消除 count-then-act 竞态：删团队与建团队库并发时后者阻塞至删除提交后
     * 按团队不存在拒绝；并发的 OWNER 降级/移除串行后 last-owner 计数才准确。
     */
    private Team lockOrThrow(String uuid) {
        if (StringUtils.isBlank(uuid)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        Team team = lambdaQuery().eq(Team::getUuid, uuid).last("for update").one();
        if (null == team) {
            throw new BaseException(A_TEAM_NOT_FOUND);
        }
        return team;
    }

    /**
     * 当前用户在指定团队的成员记录；非成员返回 null。
     */
    public TeamMember myMembership(Long teamId) {
        return membership(teamId, currentUser().getId());
    }

    // ========== Private helpers ==========

    private User currentUser() {
        User user = ThreadContext.getCurrentUser();
        if (null == user) {
            throw new BaseException(A_USER_NOT_EXIST);
        }
        return user;
    }

    private Team getEditableOrThrow(Long id, String uuid) {
        Team team = null;
        if (null != id && id >= 1) {
            team = getById(id);
        }
        if (null == team && StringUtils.isNotBlank(uuid)) {
            team = lambdaQuery().eq(Team::getUuid, uuid).one();
        }
        if (null == team) {
            throw new BaseException(A_TEAM_NOT_FOUND);
        }
        return team;
    }

    private void requireOwner(Team team, User user) {
        TeamMember membership = membership(team.getId(), user.getId());
        if (null == membership || !TeamRoleEnum.OWNER.getValue().equals(membership.getRole())) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
    }

    private void requireMember(Team team, User user) {
        if (null == membership(team.getId(), user.getId())) {
            // 非成员按不存在处理，与知识库读取的掩蔽语义一致。
            throw new BaseException(A_TEAM_NOT_FOUND);
        }
    }

    private void requireMultipleOwners(Long teamId) {
        Long ownerCount = teamMemberMapper.selectCount(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getRole, TeamRoleEnum.OWNER.getValue()));
        if (ownerCount == null || ownerCount <= 1) {
            throw new BaseException(A_TEAM_LAST_OWNER);
        }
    }

    private TeamMember membership(Long teamId, Long userId) {
        return teamMemberMapper.selectOne(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, teamId)
                .eq(TeamMember::getUserId, userId));
    }

    private Map<Long, String> myRoleByTeamId(Long userId) {
        return teamMemberMapper.selectList(Wrappers.<TeamMember>lambdaQuery()
                        .eq(TeamMember::getUserId, userId)).stream()
                .collect(Collectors.toMap(TeamMember::getTeamId, TeamMember::getRole, (a, b) -> a));
    }

    private int countMembers(Long teamId) {
        Long count = teamMemberMapper.selectCount(Wrappers.<TeamMember>lambdaQuery()
                .eq(TeamMember::getTeamId, teamId));
        return count == null ? 0 : count.intValue();
    }

    private TeamInfoResp toInfoResp(Team team, String myRole, Integer memberCount) {
        TeamInfoResp resp = new TeamInfoResp();
        resp.setId(team.getId());
        resp.setUuid(team.getUuid());
        resp.setName(team.getName());
        resp.setRemark(team.getRemark());
        resp.setMyRole(myRole);
        resp.setMemberCount(memberCount);
        resp.setCreateTime(team.getCreateTime());
        resp.setUpdateTime(team.getUpdateTime());
        return resp;
    }

    private TeamMemberResp toMemberResp(TeamMember membership, User member) {
        TeamMemberResp resp = new TeamMemberResp();
        resp.setUserId(membership.getUserId());
        resp.setRole(membership.getRole());
        resp.setCreateTime(membership.getCreateTime());
        if (null != member) {
            resp.setUuid(member.getUuid());
            resp.setName(member.getName());
            resp.setEmail(member.getEmail());
            resp.setAvatar(member.getAvatar());
        }
        return resp;
    }
}
