package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.TeamMember;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.KbAccessType;
import com.pppp.zhimesh.common.enums.KbCompanyScopeEnum;
import com.pppp.zhimesh.common.enums.KbOwnerTypeEnum;
import com.pppp.zhimesh.common.enums.TeamRoleEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.mapper.TeamMemberMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 知识库访问权限的统一裁决入口（三级归属：PERSONAL/TEAM/COMPANY）。
 *
 * <p>可见性完全由归属推导，不存在独立"公开"开关：PERSONAL 永远私有，仅 owner
 * MANAGE；TEAM 永远仅团队成员可见，由 adi_team_member 成员关系推导
 * （OWNER→MANAGE、CONTRIBUTOR→WRITE、READER→READ），成员关系删除即权限整体
 * 回收；COMPANY 按 company_scope 分级——STAFF 全员 READ、EXECUTIVE 仅管理员
 * 可见，管理员一律 MANAGE。未登录用户对所有层级 NONE。管理员对个人/团队库
 * 不获得工作区特权——管理通道走既有 /admin/* 与 admin bypass，保持"user
 * session stays a user session"惯例。
 *
 * <p>系统知识库（isSystem）不属于三级模型，一律 NONE；其白名单绑定仍由
 * CharacterService 的既有机制处理。本服务只读两张表（adi_team_member 唯一索引
 * 单点查询），无缓存、无状态，可被任意服务/节点安全注入。
 */
@Service
public class KnowledgeBaseAccessService {

    @Resource
    private TeamMemberMapper teamMemberMapper;

    /** 实体版裁决：以 ownerId 判定 PERSONAL 归属。未知归属（null/空白）按 PERSONAL 兜底。 */
    public KbAccessType resolve(User user, KnowledgeBase kb) {
        if (kb == null) {
            return KbAccessType.NONE;
        }
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            return KbAccessType.NONE;
        }
        KbOwnerTypeEnum ownerType = kbOwnerType(kb.getOwnerType());
        return switch (ownerType) {
            case PERSONAL -> resolvePersonal(user, kb.getOwnerId());
            case TEAM -> resolveTeam(user, kb.getTeamId());
            case COMPANY -> resolveCompany(user, kb.getCompanyScope());
        };
    }

    /** DTO 版裁决：CharacterService 等走 KbInfoResp 的调用方使用，PERSONAL 以 ownerUuid 判定。 */
    public KbAccessType resolve(User user, KbInfoResp kb) {
        if (kb == null) {
            return KbAccessType.NONE;
        }
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            return KbAccessType.NONE;
        }
        KbOwnerTypeEnum ownerType = kbOwnerType(kb.getOwnerType());
        return switch (ownerType) {
            case PERSONAL -> resolvePersonalByUuid(user, kb.getOwnerUuid());
            case TEAM -> resolveTeam(user, kb.getTeamId());
            case COMPANY -> resolveCompany(user, kb.getCompanyScope());
        };
    }

    public boolean canRead(User user, KnowledgeBase kb) {
        return resolve(user, kb).satisfies(KbAccessType.READ);
    }

    public boolean canWrite(User user, KnowledgeBase kb) {
        return resolve(user, kb).satisfies(KbAccessType.WRITE);
    }

    public boolean canManage(User user, KnowledgeBase kb) {
        return resolve(user, kb).satisfies(KbAccessType.MANAGE);
    }

    public boolean canRead(User user, KbInfoResp kb) {
        return resolve(user, kb).satisfies(KbAccessType.READ);
    }

    /** 用户在团队中的角色；非成员或团队无效返回 null。uk_team_member 单点查询。 */
    public TeamRoleEnum teamRole(Long teamId, Long userId) {
        if (teamId == null || teamId <= 0 || userId == null || userId <= 0) {
            return null;
        }
        TeamMember member = teamMemberMapper.selectOne(
                new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getTeamId, teamId)
                        .eq(TeamMember::getUserId, userId));
        if (member == null) {
            return null;
        }
        return TeamRoleEnum.getByValue(member.getRole());
    }

    private KbAccessType resolvePersonal(User user, Long ownerId) {
        if (user != null && ownerId != null && ownerId.equals(user.getId())) {
            return KbAccessType.MANAGE;
        }
        return KbAccessType.NONE;
    }

    private KbAccessType resolvePersonalByUuid(User user, String ownerUuid) {
        if (user != null && ownerUuid != null && ownerUuid.equals(user.getUuid())) {
            return KbAccessType.MANAGE;
        }
        return KbAccessType.NONE;
    }

    private KbAccessType resolveTeam(User user, Long teamId) {
        if (user == null) {
            return KbAccessType.NONE;
        }
        TeamRoleEnum role = teamRole(teamId, user.getId());
        if (role == null) {
            return KbAccessType.NONE;
        }
        return switch (role) {
            case OWNER -> KbAccessType.MANAGE;
            case CONTRIBUTOR -> KbAccessType.WRITE;
            case READER -> KbAccessType.READ;
        };
    }

    private KbAccessType resolveCompany(User user, String companyScope) {
        if (user == null) {
            return KbAccessType.NONE;
        }
        if (KbCompanyScopeEnum.EXECUTIVE == KbCompanyScopeEnum.getByValue(companyScope)) {
            return Boolean.TRUE.equals(user.getIsAdmin()) ? KbAccessType.MANAGE : KbAccessType.NONE;
        }
        return Boolean.TRUE.equals(user.getIsAdmin()) ? KbAccessType.MANAGE : KbAccessType.READ;
    }

    private KbOwnerTypeEnum kbOwnerType(String ownerType) {
        KbOwnerTypeEnum parsed = KbOwnerTypeEnum.getByValue(ownerType);
        // 迁移 039 前后短暂窗口内旧行可能尚未回填，统一按存量语义（个人库）兜底。
        return parsed == null ? KbOwnerTypeEnum.PERSONAL : parsed;
    }
}
