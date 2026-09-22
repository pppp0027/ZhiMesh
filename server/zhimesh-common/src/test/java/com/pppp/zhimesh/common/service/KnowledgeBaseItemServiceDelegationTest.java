package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_AUTH;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 条目权限探针的委托语义：非管理员一律经 KnowledgeBaseAccessService 裁决；
 * 管理员旁路保留；空参前置与掩蔽错误码不变。
 */
class KnowledgeBaseItemServiceDelegationTest {

    private KnowledgeBaseItemService service;
    private KnowledgeBaseItemMapper itemMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeBaseAccessService accessService;

    private User member;
    private User admin;
    private KnowledgeBase teamKb;
    private KnowledgeBaseItem item;
    private SpringMessageSourceStub messageSourceStub;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        // 每个实体独立的 MapperBuilderAssistant：namespace 只能设置一次
        MybatisTableInfoTestSupport.init(KnowledgeBaseItem.class, KnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseItemService();
        itemMapper = mock(KnowledgeBaseItemMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        accessService = mock(KnowledgeBaseAccessService.class);
        ReflectionTestUtils.setField(service, "baseMapper", itemMapper);
        ReflectionTestUtils.setField(service, "entityClass", KnowledgeBaseItem.class);
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseAccessService", accessService);

        member = new User();
        member.setId(1L);
        member.setUuid("user-uuid");
        member.setLocale("zh-CN");
        ThreadContext.setCurrentUser(member);
        messageSourceStub = SpringMessageSourceStub.install();

        admin = new User();
        admin.setId(9L);
        admin.setIsAdmin(true);

        teamKb = new KnowledgeBase();
        teamKb.setId(100L);
        teamKb.setUuid("kb-uuid");
        teamKb.setOwnerType("TEAM");
        teamKb.setTeamId(10L);

        item = new KnowledgeBaseItem();
        item.setId(7L);
        item.setUuid("item-uuid");
        item.setKbId(100L);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
        messageSourceStub.close();
    }

    // ========== 委托 resolver ==========

    @Test
    void hasWritePrivilegeDelegatesToResolver() {
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(knowledgeBaseMapper.selectById(100L)).thenReturn(teamKb);
        when(accessService.canWrite(member, teamKb)).thenReturn(true);

        assertTrue(service.hasWritePrivilege("item-uuid"));
        verify(accessService).canWrite(member, teamKb);

        when(accessService.canWrite(member, teamKb)).thenReturn(false);
        assertFalse(service.hasWritePrivilege("item-uuid"));
    }

    @Test
    void hasWritePrivilegeByKbResolvesKnowledgeBaseByUuid() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(teamKb);
        when(accessService.canWrite(member, teamKb)).thenReturn(true);

        assertTrue(service.hasWritePrivilegeByKb("kb-uuid"));
        verify(accessService).canWrite(member, teamKb);
    }

    @Test
    void hasWritePrivilegeByIdResolvesThroughItemKnowledgeBase() {
        when(itemMapper.selectById(7L)).thenReturn(item);
        when(knowledgeBaseMapper.selectById(100L)).thenReturn(teamKb);
        when(accessService.canWrite(member, teamKb)).thenReturn(false);

        assertFalse(service.hasWritePrivilegeById(7L));
        // 拒绝时抛 A_USER_NOT_AUTH
        assertThrows(BaseException.class, () -> service.checkWritePrivilegeById(7L));
    }

    @Test
    void checkReadPrivilegeMasksDenialAsDataNotFound() {
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(knowledgeBaseMapper.selectById(100L)).thenReturn(teamKb);
        when(accessService.canRead(member, teamKb)).thenReturn(true);
        assertDoesNotThrow(() -> service.checkReadPrivilege("item-uuid"));

        when(accessService.canRead(member, teamKb)).thenReturn(false);
        BaseException e = assertThrows(BaseException.class, () -> service.checkReadPrivilege("item-uuid"));
        assertEquals(A_DATA_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void checkWritePrivilegeByKbThrowsNotAuthOnDenial() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(teamKb);
        when(accessService.canWrite(member, teamKb)).thenReturn(false);

        BaseException e = assertThrows(BaseException.class,
                () -> service.checkWritePrivilegeByKb("kb-uuid"));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    // ========== 管理员旁路与前置校验 ==========

    @Test
    void adminBypassSkipsResolverEntirely() {
        ThreadContext.setCurrentUser(admin);

        assertTrue(service.hasWritePrivilege("item-uuid"));
        assertTrue(service.hasWritePrivilegeByKb("kb-uuid"));
        assertTrue(service.hasWritePrivilegeById(7L));
        assertDoesNotThrow(() -> service.checkReadPrivilege("item-uuid"));
        verify(accessService, never()).canWrite(any(), any());
    }

    @Test
    void blankParametersAreRejectedBeforeAnyQuery() {
        BaseException byUuid = assertThrows(BaseException.class,
                () -> service.hasWritePrivilege(" "));
        assertEquals(A_PARAMS_ERROR.getCode(), byUuid.getCode());

        BaseException byKbUuid = assertThrows(BaseException.class,
                () -> service.hasWritePrivilegeByKb(null));
        assertEquals(A_PARAMS_ERROR.getCode(), byKbUuid.getCode());

        BaseException byId = assertThrows(BaseException.class,
                () -> service.hasWritePrivilegeById(0L));
        assertEquals(A_PARAMS_ERROR.getCode(), byId.getCode());
    }

    @Test
    void missingItemOrKnowledgeBaseCountsAsDenied() {
        when(itemMapper.selectOne(any())).thenReturn(null);
        assertFalse(service.hasWritePrivilege("item-ghost"));

        when(itemMapper.selectOne(any())).thenReturn(item);
        when(knowledgeBaseMapper.selectById(100L)).thenReturn(null);
        assertFalse(service.hasWritePrivilege("item-uuid"));
    }
}
