package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbEditReq;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.Team;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.KbAccessType;
import com.pppp.zhimesh.common.enums.TeamRoleEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.mapper.TeamMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_KB_OWNER_TYPE_INVALID;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_KB_TRANSFER_FORBIDDEN;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_TEAM_NOT_MEMBER;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_AUTH;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三级归属接线的关键守卫：读取/写入收口矩阵、用户侧建库校验（TEAM 须成员、
 * COMPANY 拒绝、默认 PERSONAL）、编辑忽略入参层级、管理员仅可 PERSONAL↔COMPANY。
 */
class KnowledgeBaseServiceTierCheckTest {

    private KnowledgeBaseService service;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeBaseAccessService accessService;
    private TeamMapper teamMapper;
    private AiModelService aiModelService;

    private User user;
    private User admin;
    private KnowledgeBase kb;
    private Team team;
    private SpringMessageSourceStub messageSourceStub;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        MybatisTableInfoTestSupport.init(KnowledgeBase.class, Team.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService();
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        accessService = mock(KnowledgeBaseAccessService.class);
        teamMapper = mock(TeamMapper.class);
        aiModelService = mock(AiModelService.class);
        ReflectionTestUtils.setField(service, "baseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "entityClass", KnowledgeBase.class);
        ReflectionTestUtils.setField(service, "knowledgeBaseAccessService", accessService);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "aiModelService", aiModelService);

        user = new User();
        user.setId(1L);
        user.setUuid("user-uuid");
        user.setName("user");
        user.setLocale("zh-CN");
        ThreadContext.setCurrentUser(user);
        messageSourceStub = SpringMessageSourceStub.install();

        admin = new User();
        admin.setId(9L);
        admin.setUuid("admin-uuid");
        admin.setName("admin");
        admin.setIsAdmin(true);
        admin.setLocale("zh-CN");

        kb = new KnowledgeBase();
        kb.setId(100L);
        kb.setUuid("kb-uuid");
        kb.setOwnerId(2L);

        team = new Team();
        team.setId(10L);
        team.setUuid("team-uuid");

        AiModel model = new AiModel();
        model.setId(55L);
        model.setName("test-model");
        when(aiModelService.getByIdOrThrow(55L)).thenReturn(model);
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenReturn(1);
        when(knowledgeBaseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
        messageSourceStub.close();
    }

    private void stubKb(KnowledgeBase kb) {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
    }

    private void loginAs(User who) {
        ThreadContext.setCurrentUser(who);
    }

    // ========== checkReadPrivilege 矩阵 ==========

    @Test
    void readPrivilegeResolvesThroughThreeTierRule() {
        kb.setOwnerType("PERSONAL");
        stubKb(kb);

        when(accessService.canRead(user, kb)).thenReturn(false);
        BaseException denied = assertThrows(BaseException.class, () -> service.checkReadPrivilege("kb-uuid"));
        assertEquals(A_DATA_NOT_FOUND.getCode(), denied.getCode());

        when(accessService.canRead(user, kb)).thenReturn(true);
        assertDoesNotThrow(() -> service.checkReadPrivilege("kb-uuid"));
    }

    @Test
    void readPrivilegeMasksSystemKnowledgeBaseFromUserWorkspace() {
        kb.setIsSystem(true);
        stubKb(kb);

        BaseException e = assertThrows(BaseException.class, () -> service.checkReadPrivilege("kb-uuid"));
        assertEquals(A_DATA_NOT_FOUND.getCode(), e.getCode());
    }

    // ========== checkUserWorkspaceWritePrivilege 矩阵 ==========

    @Test
    void workspaceWriteRequiresWriteLevel() {
        kb.setOwnerType("TEAM");
        kb.setTeamId(10L);
        stubKb(kb);

        when(accessService.canWrite(user, kb)).thenReturn(true);
        assertDoesNotThrow(() -> service.checkUserWorkspaceWritePrivilege("kb-uuid"));

        when(accessService.canWrite(user, kb)).thenReturn(false);
        BaseException e = assertThrows(BaseException.class,
                () -> service.checkUserWorkspaceWritePrivilege("kb-uuid"));
        assertEquals(A_DATA_NOT_FOUND.getCode(), e.getCode());
    }

    // ========== 用户侧建库 ==========

    private KbEditReq createReq(String ownerType, String teamUuid) {
        KbEditReq req = new KbEditReq();
        req.setTitle("tier-kb");
        req.setOwnerType(ownerType);
        req.setTeamUuid(teamUuid);
        req.setIngestModelId(55L);
        req.setIsEnabled(true);
        return req;
    }

    @Test
    void userWorkspaceNeverCreatesCompanyTier() {
        loginAs(admin);

        BaseException e = assertThrows(BaseException.class,
                () -> service.saveOrUpdateForUserWorkspace(createReq("COMPANY", null)));
        // 即便是管理员会话：企业库只在管理端维护（"user session stays a user session"）
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    void teamTierRequiresTeamUuid() {
        BaseException e = assertThrows(BaseException.class,
                () -> service.saveOrUpdateForUserWorkspace(createReq("TEAM", " ")));
        assertEquals(A_PARAMS_ERROR.getCode(), e.getCode());
    }

    @Test
    void teamTierRequiresMembership() {
        when(teamMapper.selectOne(any())).thenReturn(team);
        when(accessService.teamRole(10L, 1L)).thenReturn(null);

        BaseException e = assertThrows(BaseException.class,
                () -> service.saveOrUpdateForUserWorkspace(createReq("TEAM", "team-uuid")));
        assertEquals(A_TEAM_NOT_MEMBER.getCode(), e.getCode());
    }

    @Test
    void teamTierCreatedForMember() {
        when(teamMapper.selectOne(any())).thenReturn(team);
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.CONTRIBUTOR);

        service.saveOrUpdateForUserWorkspace(createReq("TEAM", "team-uuid"));

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(kbCaptor.capture());
        assertEquals("TEAM", kbCaptor.getValue().getOwnerType());
        assertEquals(10L, kbCaptor.getValue().getTeamId());
        assertEquals(1L, kbCaptor.getValue().getOwnerId());
    }

    @Test
    void teamTierRejectsReaderRole() {
        when(teamMapper.selectOne(any())).thenReturn(team);
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.READER);

        BaseException e = assertThrows(BaseException.class,
                () -> service.saveOrUpdateForUserWorkspace(createReq("TEAM", "team-uuid")));
        // READER 建出的团队库自己无权写入（WRITE 检查会拒绝），入口直接拒绝
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamTierCreateLocksTeamRow() {
        when(teamMapper.selectOne(any())).thenReturn(team);
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.CONTRIBUTOR);

        service.saveOrUpdateForUserWorkspace(createReq("TEAM", "team-uuid"));

        ArgumentCaptor<LambdaQueryWrapper<Team>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMapper).selectOne(wrapperCaptor.capture());
        // 建团队库与团队删除/成员移除以团队行锁串行化（SELECT ... FOR UPDATE）
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("for update"));
    }

    @Test
    void blankOwnerTypeDefaultsToPersonal() {
        service.saveOrUpdateForUserWorkspace(createReq(" ", null));

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(kbCaptor.capture());
        assertEquals("PERSONAL", kbCaptor.getValue().getOwnerType());
        assertEquals(0L, kbCaptor.getValue().getTeamId());
    }

    @Test
    void unknownOwnerTypeIsRejected() {
        BaseException e = assertThrows(BaseException.class,
                () -> service.saveOrUpdateForUserWorkspace(createReq("GUEST", null)));
        assertEquals(A_KB_OWNER_TYPE_INVALID.getCode(), e.getCode());
    }

    // ========== 编辑层级规则 ==========

    private KnowledgeBase existing(String ownerType, Long teamId) {
        KnowledgeBase existing = new KnowledgeBase();
        existing.setId(100L);
        existing.setUuid("kb-uuid");
        existing.setOwnerType(ownerType);
        existing.setTeamId(teamId);
        existing.setIsSystem(false);
        existing.setIsEnabled(true);
        when(knowledgeBaseMapper.selectById(100L)).thenReturn(existing);
        // checkUserWorkspaceWritePrivilege 经 uuid 再查一次
        stubKb(existing);
        return existing;
    }

    private KbEditReq editReq(Long id, String ownerType) {
        KbEditReq req = createReq(null, null);
        req.setId(id);
        req.setOwnerType(ownerType);
        return req;
    }

    @Test
    void editIgnoresRequestedTierForRegularUsers() {
        existing("PERSONAL", 0L);
        when(accessService.canWrite(eq(user), any(KnowledgeBase.class))).thenReturn(true);
        when(accessService.resolve(eq(user), any(KnowledgeBase.class))).thenReturn(KbAccessType.MANAGE);

        service.saveOrUpdateForUserWorkspace(editReq(100L, "COMPANY"));

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(kbCaptor.capture());
        // 普通用户编辑时入参层级被忽略，防篡改
        assertEquals("PERSONAL", kbCaptor.getValue().getOwnerType());
    }

    @Test
    void adminEditMayFlipPersonalToCompany() {
        existing("PERSONAL", 0L);
        loginAs(admin);

        service.saveOrUpdate(editReq(100L, "COMPANY"));

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(kbCaptor.capture());
        assertEquals("COMPANY", kbCaptor.getValue().getOwnerType());
        assertEquals(0L, kbCaptor.getValue().getTeamId());
    }

    @Test
    void adminEditRejectsTeamInvolvement() {
        existing("TEAM", 10L);
        loginAs(admin);

        BaseException e = assertThrows(BaseException.class,
                () -> service.saveOrUpdate(editReq(100L, "PERSONAL")));
        assertEquals(A_KB_TRANSFER_FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void systemKnowledgeBaseAlwaysStaysPersonal() {
        loginAs(admin);

        KbEditReq req = createReq("COMPANY", null);
        req.setIsSystem(true);
        KnowledgeBase created = service.saveOrUpdate(req);

        // 系统库游离于三级模型之外
        assertEquals(Boolean.TRUE, created.getIsSystem());
        assertEquals("PERSONAL", created.getOwnerType());
        assertEquals(0L, created.getTeamId());
    }

    // ========== 企业库 company_scope ==========

    @Test
    void companyCreateHonorsAdminScopeAndRejectsUnknownValues() {
        loginAs(admin);
        KbEditReq req = createReq("COMPANY", null);
        req.setCompanyScope("EXECUTIVE");

        service.saveOrUpdate(req);

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(kbCaptor.capture());
        assertEquals("EXECUTIVE", kbCaptor.getValue().getCompanyScope());

        BaseException e = assertThrows(BaseException.class, () -> {
            KbEditReq bad = createReq("COMPANY", null);
            bad.setCompanyScope("GUEST");
            service.saveOrUpdate(bad);
        });
        assertEquals(A_PARAMS_ERROR.getCode(), e.getCode());
    }

    @Test
    void nonCompanyTiersArePinnedToStaffScope() {
        when(teamMapper.selectOne(any())).thenReturn(team);
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.CONTRIBUTOR);

        KbEditReq req = createReq("TEAM", "team-uuid");
        req.setCompanyScope("EXECUTIVE");

        service.saveOrUpdateForUserWorkspace(req);

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(kbCaptor.capture());
        // 非 COMPANY 归属强制 STAFF，防脏数据（CHECK 约束同口径）
        assertEquals("STAFF", kbCaptor.getValue().getCompanyScope());
    }

    @Test
    void adminEditRetunesCompanyScope() {
        existing("COMPANY", 0L);
        loginAs(admin);
        KbEditReq req = editReq(100L, null);
        req.setCompanyScope("EXECUTIVE");

        service.saveOrUpdate(req);

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(kbCaptor.capture());
        assertEquals("EXECUTIVE", kbCaptor.getValue().getCompanyScope());
    }

    @Test
    void adminTierFlipEnteringHonorsScopeAndLeavingPinsStaff() {
        existing("PERSONAL", 0L);
        loginAs(admin);
        KbEditReq entering = editReq(100L, "COMPANY");
        entering.setCompanyScope("EXECUTIVE");
        service.saveOrUpdate(entering);

        existing("COMPANY", 0L);
        KbEditReq leaving = editReq(100L, "PERSONAL");
        service.saveOrUpdate(leaving);

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper, times(2)).updateById(kbCaptor.capture());
        assertEquals("EXECUTIVE", kbCaptor.getAllValues().get(0).getCompanyScope());
        // 转出 COMPANY 归一为 STAFF，避免 EXECUTIVE 残留在个人库上
        assertEquals("STAFF", kbCaptor.getAllValues().get(1).getCompanyScope());
    }

    @Test
    void regularUserEditCannotSetCompanyScope() {
        existing("COMPANY", 0L);
        when(accessService.canWrite(eq(user), any(KnowledgeBase.class))).thenReturn(true);
        when(accessService.resolve(eq(user), any(KnowledgeBase.class))).thenReturn(KbAccessType.MANAGE);
        KbEditReq req = editReq(100L, null);
        req.setCompanyScope("EXECUTIVE");

        service.saveOrUpdateForUserWorkspace(req);

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(kbCaptor.capture());
        // scope 是管理员专属字段，普通用户编辑时保持存量值（此处 null→STAFF）
        assertEquals("STAFF", kbCaptor.getValue().getCompanyScope());
    }

    @Test
    void companySectionHidesExecutiveScopeFromRegularUsers() {
        loginAs(user);
        when(knowledgeBaseMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.searchCompanyForUserWorkspace("", 1, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBase>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(knowledgeBaseMapper).selectPage(any(), wrapperCaptor.capture());
        // 非 admin 的企业分区必须过滤 EXECUTIVE 库
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("company_scope"));

        loginAs(admin);
        service.searchCompanyForUserWorkspace("", 1, 10);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBase>> adminWrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(knowledgeBaseMapper, times(2)).selectPage(any(), adminWrapperCaptor.capture());
        // 管理员看全量
        assertFalse(adminWrapperCaptor.getAllValues().get(1).getSqlSegment().contains("company_scope"));
    }
}
