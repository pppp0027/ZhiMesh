package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.TeamMember;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.KbAccessType;
import com.pppp.zhimesh.common.enums.TeamRoleEnum;
import com.pppp.zhimesh.common.mapper.TeamMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限矩阵全枚举：三级归属 × 访问者身份 × 四级访问级别。
 * 可见性完全由归属推导（无公开开关）：个人库仅 owner、团队库仅成员、
 * 企业库按 company_scope 分级（STAFF 全员 / EXECUTIVE 仅管理员）。
 * 另覆盖系统库 NONE、匿名访问、管理员仅在企业库获得 MANAGE、
 * 未知归属按 PERSONAL 兜底、null 安全与便捷方法阈值。
 */
class KnowledgeBaseAccessServiceTest {

    private KnowledgeBaseAccessService service;
    private TeamMemberMapper teamMemberMapper;

    private User owner;
    private User stranger;
    private User admin;
    private User contributor;
    private User reader;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseAccessService();
        teamMemberMapper = mock(TeamMemberMapper.class);
        ReflectionTestUtils.setField(service, "teamMemberMapper", teamMemberMapper);

        owner = user(1L, "uuid-1", false);
        stranger = user(2L, "uuid-2", false);
        admin = user(3L, "uuid-3", true);
        contributor = user(4L, "uuid-4", false);
        reader = user(5L, "uuid-5", false);
    }

    private User user(Long id, String uuid, boolean isAdmin) {
        User user = new User();
        user.setId(id);
        user.setUuid(uuid);
        user.setIsAdmin(isAdmin);
        return user;
    }

    private KnowledgeBase kb(String ownerType, Long ownerId, Long teamId, boolean isSystem) {
        return kb(ownerType, ownerId, teamId, null, isSystem);
    }

    private KnowledgeBase kb(String ownerType, Long ownerId, Long teamId, String companyScope, boolean isSystem) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setOwnerType(ownerType);
        kb.setOwnerId(ownerId);
        kb.setTeamId(teamId);
        kb.setCompanyScope(companyScope);
        kb.setIsSystem(isSystem);
        return kb;
    }

    private void stubTeamRole(Long teamId, Long userId, TeamRoleEnum role) {
        when(teamMemberMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            if (role == null) {
                return null;
            }
            TeamMember member = new TeamMember();
            member.setTeamId(teamId);
            member.setUserId(userId);
            member.setRole(role.getValue());
            return member;
        });
    }

    // ========== PERSONAL（永远私有） ==========

    @Test
    void personalOwnerGetsManage() {
        KnowledgeBase kb = kb("PERSONAL", 1L, 0L, false);
        assertEquals(KbAccessType.MANAGE, service.resolve(owner, kb));
        assertTrue(service.canManage(owner, kb));
        assertTrue(service.canWrite(owner, kb));
        assertTrue(service.canRead(owner, kb));
    }

    @Test
    void personalStrangerGetsNoneEvenForAnonymous() {
        KnowledgeBase kb = kb("PERSONAL", 1L, 0L, false);
        assertEquals(KbAccessType.NONE, service.resolve(stranger, kb));
        assertFalse(service.canRead(stranger, kb));
        // 未登录同样不可见：公开概念已下线
        assertEquals(KbAccessType.NONE, service.resolve(null, kb));
    }

    @Test
    void adminGetsNoPersonalWorkspacePrivilege() {
        // "user session stays a user session"：管理员会话对他人个人库无特权，
        // 管理通道走 /admin/* 的 admin bypass，不经由此裁决。
        KnowledgeBase kb = kb("PERSONAL", 1L, 0L, false);
        assertEquals(KbAccessType.NONE, service.resolve(admin, kb));
    }

    @Test
    void nullOwnerTypeFallsBackToPersonal() {
        KnowledgeBase legacy = kb(null, 1L, 0L, false);
        assertEquals(KbAccessType.MANAGE, service.resolve(owner, legacy));
        KnowledgeBase unknown = kb("WHATEVER", 1L, 0L, false);
        assertEquals(KbAccessType.MANAGE, service.resolve(owner, unknown));
    }

    // ========== TEAM（永远仅成员） ==========

    @Test
    void teamRolesMapToAccessLevels() {
        KnowledgeBase kb = kb("TEAM", 1L, 10L, false);
        stubTeamRole(10L, 1L, TeamRoleEnum.OWNER);
        assertEquals(KbAccessType.MANAGE, service.resolve(owner, kb));
        stubTeamRole(10L, 4L, TeamRoleEnum.CONTRIBUTOR);
        assertEquals(KbAccessType.WRITE, service.resolve(contributor, kb));
        assertTrue(service.canWrite(contributor, kb));
        assertFalse(service.canManage(contributor, kb));
        stubTeamRole(10L, 5L, TeamRoleEnum.READER);
        assertEquals(KbAccessType.READ, service.resolve(reader, kb));
        assertFalse(service.canWrite(reader, kb));
    }

    @Test
    void teamNonMemberGetsNoneIncludingAnonymousAndAdmin() {
        stubTeamRole(10L, 2L, null);
        KnowledgeBase kb = kb("TEAM", 1L, 10L, false);
        assertEquals(KbAccessType.NONE, service.resolve(stranger, kb));
        // 匿名与非成员管理员同语义：团队边界即可见性边界
        assertEquals(KbAccessType.NONE, service.resolve(null, kb));
        stubTeamRole(10L, 3L, null);
        assertEquals(KbAccessType.NONE, service.resolve(admin, kb));
    }

    // ========== COMPANY（STAFF 全员 / EXECUTIVE 管理层） ==========

    @Test
    void companyStaffScopeIsReadForEveryoneAndManageForAdminOnly() {
        KnowledgeBase kb = kb("COMPANY", 0L, 0L, "STAFF", false);
        assertEquals(KbAccessType.READ, service.resolve(stranger, kb));
        assertEquals(KbAccessType.READ, service.resolve(owner, kb));
        assertFalse(service.canWrite(stranger, kb));
        assertEquals(KbAccessType.MANAGE, service.resolve(admin, kb));
        assertTrue(service.canManage(admin, kb));
        // 企业库不对外匿名开放
        assertEquals(KbAccessType.NONE, service.resolve(null, kb));
    }

    @Test
    void companyExecutiveScopeIsAdminOnly() {
        KnowledgeBase kb = kb("COMPANY", 0L, 0L, "EXECUTIVE", false);
        assertEquals(KbAccessType.NONE, service.resolve(stranger, kb));
        assertFalse(service.canRead(stranger, kb));
        assertEquals(KbAccessType.NONE, service.resolve(null, kb));
        assertEquals(KbAccessType.MANAGE, service.resolve(admin, kb));
    }

    @Test
    void companyBlankScopeFallsBackToStaff() {
        // company_scope 为列默认值；未知/空值按全员可见兜底，不放大到管理层语义
        KnowledgeBase blank = kb("COMPANY", 0L, 0L, null, false);
        assertEquals(KbAccessType.READ, service.resolve(stranger, blank));
        KnowledgeBase unknown = kb("COMPANY", 0L, 0L, "WHATEVER", false);
        assertEquals(KbAccessType.READ, service.resolve(stranger, unknown));
    }

    // ========== isSystem 与 null 安全 ==========

    @Test
    void systemKnowledgeBasesResolveToNoneForEveryone() {
        KnowledgeBase systemKb = kb("PERSONAL", 1L, 0L, true);
        assertEquals(KbAccessType.NONE, service.resolve(owner, systemKb));
        assertEquals(KbAccessType.NONE, service.resolve(admin, systemKb));
        assertEquals(KbAccessType.NONE, service.resolve(null, systemKb));
    }

    @Test
    void nullKnowledgeBaseResolvesToNone() {
        assertEquals(KbAccessType.NONE, service.resolve(owner, (KnowledgeBase) null));
        assertEquals(KbAccessType.NONE, service.resolve(owner, (KbInfoResp) null));
    }

    // ========== DTO 重载 ==========

    @Test
    void dtoOverloadResolvesPersonalByOwnerUuid() {
        KbInfoResp dto = new KbInfoResp();
        dto.setOwnerType("PERSONAL");
        dto.setOwnerUuid("uuid-1");
        assertEquals(KbAccessType.MANAGE, service.resolve(owner, dto));
        assertEquals(KbAccessType.NONE, service.resolve(stranger, dto));
        assertEquals(KbAccessType.NONE, service.resolve(null, dto));
    }

    @Test
    void dtoOverloadResolvesTeamAndCompany() {
        KbInfoResp teamDto = new KbInfoResp();
        teamDto.setOwnerType("TEAM");
        teamDto.setTeamId(10L);
        stubTeamRole(10L, 4L, TeamRoleEnum.CONTRIBUTOR);
        assertEquals(KbAccessType.WRITE, service.resolve(contributor, teamDto));
        stubTeamRole(10L, 2L, null);
        assertEquals(KbAccessType.NONE, service.resolve(stranger, teamDto));

        KbInfoResp companyDto = new KbInfoResp();
        companyDto.setOwnerType("COMPANY");
        companyDto.setCompanyScope("STAFF");
        assertEquals(KbAccessType.READ, service.resolve(stranger, companyDto));
        assertEquals(KbAccessType.MANAGE, service.resolve(admin, companyDto));
        companyDto.setCompanyScope("EXECUTIVE");
        assertEquals(KbAccessType.NONE, service.resolve(stranger, companyDto));
        assertEquals(KbAccessType.MANAGE, service.resolve(admin, companyDto));
    }

    // ========== teamRole 单点查询 ==========

    @Test
    void teamRoleReturnsNullForInvalidIdentifiers() {
        assertNull(service.teamRole(null, 1L));
        assertNull(service.teamRole(0L, 1L));
        assertNull(service.teamRole(10L, null));
        assertNull(service.teamRole(10L, 0L));
    }

    @Test
    void teamRoleLooksUpMembershipOnce() {
        stubTeamRole(10L, 1L, TeamRoleEnum.OWNER);
        assertEquals(TeamRoleEnum.OWNER, service.teamRole(10L, 1L));
        stubTeamRole(10L, 2L, null);
        assertNull(service.teamRole(10L, 2L));
    }

    // ========== satisfies 阈值 ==========

    @Test
    void accessLevelSatisfiesThresholds() {
        assertTrue(KbAccessType.MANAGE.satisfies(KbAccessType.READ));
        assertTrue(KbAccessType.MANAGE.satisfies(KbAccessType.WRITE));
        assertTrue(KbAccessType.WRITE.satisfies(KbAccessType.READ));
        assertFalse(KbAccessType.READ.satisfies(KbAccessType.WRITE));
        assertFalse(KbAccessType.NONE.satisfies(KbAccessType.READ));
    }
}
