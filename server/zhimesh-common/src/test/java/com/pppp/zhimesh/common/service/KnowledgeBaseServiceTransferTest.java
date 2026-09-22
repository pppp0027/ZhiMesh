package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbTransferReq;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DOC_INDEX_DOING;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_KB_TRANSFER_FORBIDDEN;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_AUTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库归属转移守卫：PERSONAL→TEAM 双 OWNER、TEAM→PERSONAL owner_* 重指向、
 * COMPANY 不参与、同层级拒绝、索引锁占用阻断、系统库按不存在掩蔽。
 */
class KnowledgeBaseServiceTransferTest {

    private KnowledgeBaseService service;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeBaseAccessService accessService;
    private TeamMapper teamMapper;
    private StringRedisTemplate redisTemplate;

    private User caller;
    private KnowledgeBase personalKb;
    private KnowledgeBase teamKb;
    private Team targetTeam;
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
        redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "baseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseAccessService", accessService);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        caller = new User();
        caller.setId(1L);
        caller.setUuid("caller-uuid");
        caller.setName("caller");
        caller.setLocale("zh-CN");
        ThreadContext.setCurrentUser(caller);
        messageSourceStub = SpringMessageSourceStub.install();

        personalKb = new KnowledgeBase();
        personalKb.setId(100L);
        personalKb.setUuid("kb-personal");
        personalKb.setOwnerType("PERSONAL");
        personalKb.setOwnerId(1L);
        personalKb.setOwnerUuid("caller-uuid");
        personalKb.setOwnerName("caller");
        personalKb.setTeamId(0L);

        teamKb = new KnowledgeBase();
        teamKb.setId(101L);
        teamKb.setUuid("kb-team");
        teamKb.setOwnerType("TEAM");
        teamKb.setOwnerId(2L);
        teamKb.setOwnerUuid("creator-uuid");
        teamKb.setOwnerName("creator");
        teamKb.setTeamId(10L);

        targetTeam = new Team();
        targetTeam.setId(10L);
        targetTeam.setUuid("team-uuid");
        targetTeam.setName("platform-team");

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
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

    private KbTransferReq transferReq(String kbUuid, String ownerType, String teamUuid) {
        KbTransferReq req = new KbTransferReq();
        req.setKbUuid(kbUuid);
        req.setOwnerType(ownerType);
        req.setTeamUuid(teamUuid);
        return req;
    }

    // ========== PERSONAL → TEAM ==========

    @Test
    void personalToTeamRequiresManageOnKnowledgeBase() {
        stubKb(personalKb);
        when(accessService.canManage(caller, personalKb)).thenReturn(false);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-personal", "TEAM", "team-uuid")));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
        verify(knowledgeBaseMapper, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void personalToTeamRequiresTargetTeamOwner() {
        stubKb(personalKb);
        when(accessService.canManage(caller, personalKb)).thenReturn(true);
        when(teamMapper.selectOne(any())).thenReturn(targetTeam);
        // 库主只是目标团队的 CONTRIBUTOR：不满足“双 OWNER”
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.CONTRIBUTOR);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-personal", "TEAM", "team-uuid")));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    void personalToTeamRequiresTeamUuid() {
        stubKb(personalKb);
        when(accessService.canManage(caller, personalKb)).thenReturn(true);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-personal", "TEAM", " ")));
        assertEquals(A_PARAMS_ERROR.getCode(), e.getCode());
    }

    @Test
    void personalToTeamSucceedsForDoubleOwner() {
        stubKb(personalKb);
        when(accessService.canManage(caller, personalKb)).thenReturn(true);
        when(teamMapper.selectOne(any())).thenReturn(targetTeam);
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.OWNER);

        service.transfer(transferReq("kb-personal", "TEAM", "team-uuid"));

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(kbCaptor.capture());
        KnowledgeBase updated = kbCaptor.getValue();
        assertEquals("TEAM", updated.getOwnerType());
        assertEquals(10L, updated.getTeamId());
        // owner_* 保持创建者信息用于审计
        assertEquals("caller-uuid", updated.getOwnerUuid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void personalToTeamLocksTargetTeamRow() {
        stubKb(personalKb);
        when(accessService.canManage(caller, personalKb)).thenReturn(true);
        when(teamMapper.selectOne(any())).thenReturn(targetTeam);
        when(accessService.teamRole(10L, 1L)).thenReturn(TeamRoleEnum.OWNER);

        service.transfer(transferReq("kb-personal", "TEAM", "team-uuid"));

        ArgumentCaptor<LambdaQueryWrapper<Team>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(teamMapper).selectOne(wrapperCaptor.capture());
        // 转移与团队删除/成员移除同样以团队行锁串行化（SELECT ... FOR UPDATE）
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("for update"));
    }

    // ========== TEAM → PERSONAL ==========

    @Test
    void teamToPersonalRequiresTeamOwner() {
        stubKb(teamKb);
        when(accessService.canManage(caller, teamKb)).thenReturn(false);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-team", "PERSONAL", null)));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    void teamToPersonalRepointsOwnerToCaller() {
        stubKb(teamKb);
        when(accessService.canManage(caller, teamKb)).thenReturn(true);

        service.transfer(transferReq("kb-team", "PERSONAL", null));

        ArgumentCaptor<KnowledgeBase> kbCaptor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(kbCaptor.capture());
        KnowledgeBase updated = kbCaptor.getValue();
        assertEquals("PERSONAL", updated.getOwnerType());
        assertEquals(0L, updated.getTeamId());
        // owner_* 重指向调用者：个人语义（列表、索引锁、ext key）随之迁移
        assertEquals(1L, updated.getOwnerId());
        assertEquals("caller-uuid", updated.getOwnerUuid());
        assertEquals("caller", updated.getOwnerName());
    }

    // ========== 禁止组合 ==========

    @Test
    void companyNeverParticipatesInEitherDirection() {
        KnowledgeBase companyKb = new KnowledgeBase();
        companyKb.setId(102L);
        companyKb.setUuid("kb-company");
        companyKb.setOwnerType("COMPANY");
        stubKb(companyKb);

        BaseException out = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-company", "TEAM", "team-uuid")));
        assertEquals(A_KB_TRANSFER_FORBIDDEN.getCode(), out.getCode());

        stubKb(personalKb);
        BaseException in = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-personal", "COMPANY", null)));
        assertEquals(A_KB_TRANSFER_FORBIDDEN.getCode(), in.getCode());
    }

    @Test
    void sameTierTransferIsRejected() {
        stubKb(personalKb);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-personal", "PERSONAL", null)));
        assertEquals(A_KB_TRANSFER_FORBIDDEN.getCode(), e.getCode());
    }

    // ========== 前置守卫 ==========

    @Test
    void activeIndexingLockBlocksTransfer() {
        stubKb(personalKb);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(accessService.canManage(caller, personalKb)).thenReturn(true);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-personal", "TEAM", "team-uuid")));
        assertEquals(A_DOC_INDEX_DOING.getCode(), e.getCode());
    }

    @Test
    void systemKnowledgeBaseIsMaskedAsNotFound() {
        KnowledgeBase systemKb = new KnowledgeBase();
        systemKb.setUuid("kb-system");
        systemKb.setIsSystem(true);
        stubKb(systemKb);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-system", "TEAM", "team-uuid")));
        assertEquals(A_DATA_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void missingKnowledgeBaseIsNotFound() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        BaseException e = assertThrows(BaseException.class,
                () -> service.transfer(transferReq("kb-missing", "TEAM", "team-uuid")));
        assertEquals(A_DATA_NOT_FOUND.getCode(), e.getCode());
    }

    // ========== 语义说明：canManage 的来源 ==========

    @Test
    void resolverLevelsExplainWhoMayTransfer() {
        // 文档化断言：MANAGE 恰好覆盖 PERSONAL 库主与 TEAM 库的团队 OWNER，
        // CONTRIBUTOR(WRITE) 不足以转移。
        assertTrue(KbAccessType.WRITE.satisfies(KbAccessType.READ));
        assertFalse(KbAccessType.WRITE.satisfies(KbAccessType.MANAGE));
        assertTrue(KbAccessType.MANAGE.satisfies(KbAccessType.MANAGE));
    }
}
