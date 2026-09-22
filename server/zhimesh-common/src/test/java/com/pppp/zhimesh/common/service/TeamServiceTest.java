package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.TeamEditReq;
import com.pppp.zhimesh.common.dto.TeamMemberAddReq;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_KB_TRANSFER_FORBIDDEN;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_TEAM_HAS_KB;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_TEAM_LAST_OWNER;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_TEAM_MEMBER_EXIST;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_TEAM_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_TEAM_OWNER_CANNOT_LEAVE;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_AUTH;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_EXIST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队服务核心守卫：建团自动 OWNER 成员、邮箱邀请（默认 CONTRIBUTOR、重复、
 * 非正常状态、直接给 OWNER）、last-owner 保护、退出/移除/删除阻断。
 */
class TeamServiceTest {

    private TeamService service;
    private TeamMapper teamMapper;
    private TeamMemberMapper teamMemberMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private UserService userService;

    private User actor;
    private Team team;
    private SpringMessageSourceStub messageSourceStub;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        // 每个实体独立的 MapperBuilderAssistant：namespace 只能设置一次
        MybatisTableInfoTestSupport.init(Team.class, TeamMember.class, KnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        service = new TeamService();
        teamMapper = mock(TeamMapper.class);
        teamMemberMapper = mock(TeamMemberMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        userService = mock(UserService.class);
        ReflectionTestUtils.setField(service, "baseMapper", teamMapper);
        // TeamService 内部 lambdaQuery() 走 getEntityClass 缓存，预置实体类
        // 即可绕开对 mock mapper 的代理反射
        ReflectionTestUtils.setField(service, "entityClass", Team.class);
        ReflectionTestUtils.setField(service, "teamMemberMapper", teamMemberMapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "userService", userService);

        actor = user(1L, "owner@zhimesh.dev");
        // BaseException 构造需要消息源；用户带 locale 避免触碰 SysConfigService。
        actor.setLocale("zh-CN");
        ThreadContext.setCurrentUser(actor);
        messageSourceStub = SpringMessageSourceStub.install();

        team = new Team();
        team.setId(10L);
        team.setUuid("team-uuid");
        team.setName("platform-team");
        team.setCreatorId(actor.getId());
        // getOrThrow 走 lambdaQuery().eq(uuid).one()
        when(teamMapper.selectOne(any())).thenReturn(team);
        when(teamMapper.selectById(10L)).thenReturn(team);
        when(teamMapper.insert(any(Team.class))).thenAnswer(invocation -> {
            Team inserting = invocation.getArgument(0);
            inserting.setId(10L);
            return 1;
        });
        when(teamMapper.updateById(any(Team.class))).thenReturn(1);
        when(teamMapper.deleteById(10L)).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
        messageSourceStub.close();
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setUuid("user-uuid-" + id);
        user.setEmail(email);
        user.setUserStatus(UserStatusEnum.NORMAL);
        return user;
    }

    private TeamMember membership(Long userId, TeamRoleEnum role) {
        TeamMember member = new TeamMember();
        member.setId(userId);
        member.setTeamId(10L);
        member.setUserId(userId);
        member.setRole(role.getValue());
        return member;
    }

    private void stubMyRole(TeamRoleEnum role) {
        TeamMember myMembership = role == null ? null : membership(actor.getId(), role);
        when(teamMemberMapper.selectOne(any())).thenReturn(myMembership);
    }

    // ========== 建团 / 编辑 ==========

    @Test
    void createTeamWritesCreatorAsOwnerMember() {
        TeamEditReq req = new TeamEditReq();
        req.setName("  new-team  ");
        req.setRemark("remark");

        Team created = service.saveOrUpdate(req);

        assertEquals("new-team", created.getName());
        assertEquals(actor.getId(), created.getCreatorId());
        ArgumentCaptor<TeamMember> memberCaptor = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMemberMapper).insert(memberCaptor.capture());
        assertEquals(team.getId(), memberCaptor.getValue().getTeamId());
        assertEquals(actor.getId(), memberCaptor.getValue().getUserId());
        assertEquals(TeamRoleEnum.OWNER.getValue(), memberCaptor.getValue().getRole());
    }

    @Test
    void editTeamAllowsOwnerToRename() {
        stubMyRole(TeamRoleEnum.OWNER);
        TeamEditReq req = new TeamEditReq();
        req.setId(10L);
        req.setName("renamed");

        Team updated = service.saveOrUpdate(req);

        assertEquals("renamed", updated.getName());
        verify(teamMapper).updateById(any(Team.class));
    }

    @Test
    void editTeamRejectsNonOwner() {
        stubMyRole(TeamRoleEnum.CONTRIBUTOR);
        TeamEditReq req = new TeamEditReq();
        req.setId(10L);
        req.setName("renamed");

        BaseException e = assertThrows(BaseException.class, () -> service.saveOrUpdate(req));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    void missingTeamIsReportedAsNotFound() {
        when(teamMapper.selectOne(any())).thenReturn(null);
        when(teamMapper.selectById(anyLong())).thenReturn(null);
        TeamEditReq req = new TeamEditReq();
        req.setId(99L);
        req.setName("any");

        BaseException e = assertThrows(BaseException.class, () -> service.saveOrUpdate(req));
        assertEquals(A_TEAM_NOT_FOUND.getCode(), e.getCode());
    }

    // ========== 删除 ==========

    @Test
    void deleteTeamBlockedWhileKnowledgeBasesExist() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(2L);

        BaseException e = assertThrows(BaseException.class, () -> service.delete("team-uuid"));
        assertEquals(A_TEAM_HAS_KB.getCode(), e.getCode());
    }

    @Test
    void deleteTeamRemovesMembersThenTeam() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);

        service.delete("team-uuid");

        verify(teamMemberMapper).delete(any());
        verify(teamMapper).deleteById(10L);
    }

    @Test
    void deleteTeamRejectsNonOwner() {
        stubMyRole(TeamRoleEnum.READER);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);

        BaseException e = assertThrows(BaseException.class, () -> service.delete("team-uuid"));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteTeamLocksTeamRowForUpdate() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);

        service.delete("team-uuid");

        ArgumentCaptor<LambdaQueryWrapper<Team>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMapper).selectOne(wrapperCaptor.capture());
        // 治理动作锁定团队行，串行化 last-owner 计数与建库并发
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("for update"));
    }

    // ========== 邀请 ==========

    @Test
    void addMemberDefaultsToContributor() {
        stubMyRole(TeamRoleEnum.OWNER);
        User invitee = user(2L, "b@zhimesh.dev");
        when(userService.getByEmail("b@zhimesh.dev")).thenReturn(invitee);

        TeamMemberAddReq req = new TeamMemberAddReq();
        req.setTeamUuid("team-uuid");
        req.setEmail("b@zhimesh.dev");

        service.addMember(req);

        ArgumentCaptor<TeamMember> memberCaptor = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMemberMapper).insert(memberCaptor.capture());
        assertEquals(invitee.getId(), memberCaptor.getValue().getUserId());
        assertEquals(TeamRoleEnum.CONTRIBUTOR.getValue(), memberCaptor.getValue().getRole());
    }

    @Test
    void addMemberRejectsNonNormalInvitee() {
        stubMyRole(TeamRoleEnum.OWNER);
        User frozen = user(3L, "frozen@zhimesh.dev");
        frozen.setUserStatus(UserStatusEnum.FREEZE);
        when(userService.getByEmail("frozen@zhimesh.dev")).thenReturn(frozen);

        TeamMemberAddReq req = new TeamMemberAddReq();
        req.setTeamUuid("team-uuid");
        req.setEmail("frozen@zhimesh.dev");

        BaseException e = assertThrows(BaseException.class, () -> service.addMember(req));
        // 非正常状态按不存在掩蔽，避免探测账号状态
        assertEquals(A_USER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void addMemberRejectsDirectOwnerRole() {
        stubMyRole(TeamRoleEnum.OWNER);
        User invitee = user(2L, "b@zhimesh.dev");
        when(userService.getByEmail("b@zhimesh.dev")).thenReturn(invitee);

        TeamMemberAddReq req = new TeamMemberAddReq();
        req.setTeamUuid("team-uuid");
        req.setEmail("b@zhimesh.dev");
        req.setRole(TeamRoleEnum.OWNER.getValue());

        BaseException e = assertThrows(BaseException.class, () -> service.addMember(req));
        assertEquals(A_PARAMS_ERROR.getCode(), e.getCode());
    }

    @Test
    void addMemberMapsDuplicateKeyToMemberExists() {
        stubMyRole(TeamRoleEnum.OWNER);
        User invitee = user(2L, "b@zhimesh.dev");
        when(userService.getByEmail("b@zhimesh.dev")).thenReturn(invitee);
        when(teamMemberMapper.insert(any(TeamMember.class)))
                .thenThrow(new DuplicateKeyException("uk_team_member"));

        TeamMemberAddReq req = new TeamMemberAddReq();
        req.setTeamUuid("team-uuid");
        req.setEmail("b@zhimesh.dev");

        BaseException e = assertThrows(BaseException.class, () -> service.addMember(req));
        assertEquals(A_TEAM_MEMBER_EXIST.getCode(), e.getCode());
    }

    @Test
    void addMemberRequiresOwner() {
        stubMyRole(TeamRoleEnum.CONTRIBUTOR);
        TeamMemberAddReq req = new TeamMemberAddReq();
        req.setTeamUuid("team-uuid");
        req.setEmail("b@zhimesh.dev");

        BaseException e = assertThrows(BaseException.class, () -> service.addMember(req));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    // ========== 角色调整 / 移除 ==========

    @Test
    void updateMemberRoleProtectsLastOwner() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(teamMemberMapper.selectOne(any())).thenReturn(membership(2L, TeamRoleEnum.OWNER));
        when(teamMemberMapper.selectCount(any())).thenReturn(1L);

        TeamMemberUpdateReq req = new TeamMemberUpdateReq();
        req.setTeamUuid("team-uuid");
        req.setUserId(2L);
        req.setRole(TeamRoleEnum.CONTRIBUTOR.getValue());

        BaseException e = assertThrows(BaseException.class, () -> service.updateMemberRole(req));
        assertEquals(A_TEAM_LAST_OWNER.getCode(), e.getCode());
    }

    @Test
    void updateMemberRoleSucceedsWhenAnotherOwnerRemains() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(teamMemberMapper.selectOne(any())).thenReturn(membership(2L, TeamRoleEnum.OWNER));
        when(teamMemberMapper.selectCount(any())).thenReturn(2L);

        TeamMemberUpdateReq req = new TeamMemberUpdateReq();
        req.setTeamUuid("team-uuid");
        req.setUserId(2L);
        req.setRole(TeamRoleEnum.CONTRIBUTOR.getValue());

        service.updateMemberRole(req);

        ArgumentCaptor<TeamMember> memberCaptor = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMemberMapper).updateById(memberCaptor.capture());
        assertEquals(TeamRoleEnum.CONTRIBUTOR.getValue(), memberCaptor.getValue().getRole());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateMemberRoleLocksTeamRowForUpdate() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(teamMemberMapper.selectOne(any())).thenReturn(membership(2L, TeamRoleEnum.OWNER));
        when(teamMemberMapper.selectCount(any())).thenReturn(2L);

        TeamMemberUpdateReq req = new TeamMemberUpdateReq();
        req.setTeamUuid("team-uuid");
        req.setUserId(2L);
        req.setRole(TeamRoleEnum.CONTRIBUTOR.getValue());

        service.updateMemberRole(req);

        ArgumentCaptor<LambdaQueryWrapper<Team>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMapper).selectOne(wrapperCaptor.capture());
        // 治理动作锁定团队行，串行化 last-owner 计数与建库并发
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("for update"));
    }

    @Test
    void removeMemberProtectsLastOwner() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(teamMemberMapper.selectOne(any())).thenReturn(membership(1L, TeamRoleEnum.OWNER));
        when(teamMemberMapper.selectCount(any())).thenReturn(1L);

        BaseException e = assertThrows(BaseException.class,
                () -> service.removeMember("team-uuid", 1L));
        assertEquals(A_TEAM_LAST_OWNER.getCode(), e.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeMemberLocksTeamRowForUpdate() {
        stubMyRole(TeamRoleEnum.OWNER);
        when(teamMemberMapper.selectOne(any())).thenReturn(membership(2L, TeamRoleEnum.OWNER));
        when(teamMemberMapper.selectCount(any())).thenReturn(2L);

        service.removeMember("team-uuid", 2L);

        ArgumentCaptor<LambdaQueryWrapper<Team>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMapper).selectOne(wrapperCaptor.capture());
        // 治理动作锁定团队行，串行化 last-owner 计数与建库并发
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("for update"));
    }

    // ========== 退出 ==========

    @Test
    void leaveRejectsOwner() {
        stubMyRole(TeamRoleEnum.OWNER);

        BaseException e = assertThrows(BaseException.class, () -> service.leave("team-uuid"));
        assertEquals(A_TEAM_OWNER_CANNOT_LEAVE.getCode(), e.getCode());
    }

    @Test
    void leaveRemovesMembershipForNonOwner() {
        TeamMember myMembership = membership(actor.getId(), TeamRoleEnum.CONTRIBUTOR);
        myMembership.setId(77L);
        when(teamMemberMapper.selectOne(any())).thenReturn(myMembership);

        service.leave("team-uuid");

        verify(teamMemberMapper).deleteById(77L);
    }

    // ========== 花名册 ==========

    @Test
    void listMembersMasksNonMemberAsTeamNotFound() {
        stubMyRole(null);

        BaseException e = assertThrows(BaseException.class, () -> service.listMembers("team-uuid"));
        assertEquals(A_TEAM_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void listMembersJoinsUserProfiles() {
        when(teamMemberMapper.selectOne(any())).thenReturn(membership(actor.getId(), TeamRoleEnum.READER));
        TeamMember other = membership(2L, TeamRoleEnum.CONTRIBUTOR);
        when(teamMemberMapper.selectList(any())).thenReturn(List.of(membership(actor.getId(), TeamRoleEnum.READER), other));
        User otherUser = user(2L, "b@zhimesh.dev");
        when(userService.listByIds(anyCollection())).thenReturn(List.of(actor, otherUser));

        var members = service.listMembers("team-uuid");

        assertEquals(2, members.size());
        assertEquals(otherUser.getEmail(), members.get(1).getEmail());
        assertEquals(TeamRoleEnum.CONTRIBUTOR.getValue(), members.get(1).getRole());
    }

    // ========== 供 KnowledgeBaseService 复用的错误码存在性 ==========

    @Test
    void transferForbiddenCodeRemainsStable() {
        // KnowledgeBaseService 转移守卫复用该错误码，误删会破坏团队库转移语义。
        assertTrue(A_KB_TRANSFER_FORBIDDEN.getCode().startsWith("A0083"));
    }
}
