package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.KbAccessType;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.rag.bm25.Bm25IndexService;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCoordinator;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删库物理清理守卫：删行成功后条目/向量/BM25/canonical chunk 四类清理全部触发，
 * 单项失败不影响其余清理与返回值，系统库仍被拒绝。
 * Physical-cleanup guards for knowledge-base deletion: after the row deletion
 * succeeds every cleanup collaborator (items, embeddings, BM25, canonical
 * chunks) is invoked, one failing cleanup neither skips the others nor flips
 * the returned result, and system knowledge bases stay undeletable.
 */
class KnowledgeBaseServicePhysicalCleanupTest {

    private KnowledgeBaseService service;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeBaseItemService knowledgeBaseItemService;
    private IKnowledgeEmbeddingService embeddingService;
    private Bm25IndexService bm25IndexService;
    private CanonicalChunkIndexService canonicalChunkIndexService;
    private KnowledgeRouteProfileCoordinator routeProfileCoordinator;
    private SpringMessageSourceStub messageSourceStub;

    private KnowledgeBase kb;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        MybatisTableInfoTestSupport.init(KnowledgeBase.class, KnowledgeBaseItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService();
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        knowledgeBaseItemService = mock(KnowledgeBaseItemService.class);
        embeddingService = mock(IKnowledgeEmbeddingService.class);
        bm25IndexService = mock(Bm25IndexService.class);
        canonicalChunkIndexService = mock(CanonicalChunkIndexService.class);
        routeProfileCoordinator = mock(KnowledgeRouteProfileCoordinator.class);
        ReflectionTestUtils.setField(service, "baseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(service, "entityClass", KnowledgeBase.class);
        ReflectionTestUtils.setField(service, "knowledgeBaseItemService", knowledgeBaseItemService);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "bm25IndexService", bm25IndexService);
        ReflectionTestUtils.setField(service, "canonicalChunkIndexService", canonicalChunkIndexService);
        ReflectionTestUtils.setField(service, "routeProfileCoordinator", routeProfileCoordinator);

        // 管理员直通 checkManagePrivilege，让用例聚焦清理协作而非权限矩阵
        User admin = new User();
        admin.setId(9L);
        admin.setUuid("admin-uuid");
        admin.setName("admin");
        admin.setIsAdmin(true);
        admin.setLocale("zh-CN");
        ThreadContext.setCurrentUser(admin);
        messageSourceStub = SpringMessageSourceStub.install();

        kb = new KnowledgeBase();
        kb.setId(100L);
        kb.setUuid("kb-uuid");
        kb.setOwnerType("PERSONAL");
        kb.setOwnerId(9L);
        kb.setIsSystem(false);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(knowledgeBaseMapper.delete(any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
        messageSourceStub.close();
    }

    @Test
    void deleteRunsEveryPhysicalCleanupAfterRowDeletion() {
        assertTrue(service.softDelete("kb-uuid"));

        verify(knowledgeBaseMapper).delete(any());
        verify(routeProfileCoordinator).knowledgeBaseDeleted("kb-uuid", 0L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KnowledgeBaseItem>> itemWrapperCaptor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(knowledgeBaseItemService).remove(itemWrapperCaptor.capture());
        // 条目删除必须按 kb_uuid 限定，防止误删他库条目
        assertTrue(itemWrapperCaptor.getValue().getSqlSegment().contains("kb_uuid"));

        // 先删条目行，再依次清向量、BM25、canonical chunks
        InOrder order = inOrder(knowledgeBaseItemService, embeddingService,
                bm25IndexService, canonicalChunkIndexService);
        order.verify(knowledgeBaseItemService).remove(any());
        order.verify(embeddingService).deleteByKbUuid("kb-uuid");
        order.verify(bm25IndexService).deleteByKbUuid("kb-uuid");
        order.verify(canonicalChunkIndexService).deleteByKbUuid("kb-uuid");
    }

    @Test
    void failingCleanupStepsAreIndependentAndDeletionStillSucceeds() {
        doThrow(new IllegalStateException("item store down"))
                .when(knowledgeBaseItemService).remove(any());
        doThrow(new IllegalStateException("vector store down"))
                .when(embeddingService).deleteByKbUuid("kb-uuid");

        assertTrue(service.softDelete("kb-uuid"));

        // 首项与中间项失败都不阻断其余清理，也不影响返回值
        verify(knowledgeBaseItemService).remove(any());
        verify(embeddingService).deleteByKbUuid("kb-uuid");
        verify(bm25IndexService).deleteByKbUuid("kb-uuid");
        verify(canonicalChunkIndexService).deleteByKbUuid("kb-uuid");
    }

    @Test
    void systemKnowledgeBaseIsRejectedBeforeAnyCleanup() {
        kb.setIsSystem(true);

        BaseException e = assertThrows(BaseException.class, () -> service.softDelete("kb-uuid"));
        assertEquals(A_PARAMS_ERROR.getCode(), e.getCode());

        verify(knowledgeBaseMapper, never()).delete(any());
        verify(knowledgeBaseItemService, never()).remove(any());
        verify(embeddingService, never()).deleteByKbUuid(any());
        verify(bm25IndexService, never()).deleteByKbUuid(any());
        verify(canonicalChunkIndexService, never()).deleteByKbUuid(any());
    }

    @Test
    void userWorkspaceDeletionRunsTheSameCleanups() {
        User owner = new User();
        owner.setId(1L);
        owner.setUuid("owner-uuid");
        owner.setName("owner");
        owner.setLocale("zh-CN");
        ThreadContext.setCurrentUser(owner);
        KnowledgeBaseAccessService accessService = mock(KnowledgeBaseAccessService.class);
        ReflectionTestUtils.setField(service, "knowledgeBaseAccessService", accessService);
        when(accessService.canWrite(owner, kb)).thenReturn(true);
        // softDeleteForUserWorkspace 内部仍走 softDelete 的 MANAGE 校验
        when(accessService.resolve(owner, kb)).thenReturn(KbAccessType.MANAGE);

        assertTrue(service.softDeleteForUserWorkspace("kb-uuid"));

        verify(knowledgeBaseItemService).remove(any());
        verify(embeddingService).deleteByKbUuid("kb-uuid");
        verify(bm25IndexService).deleteByKbUuid("kb-uuid");
        verify(canonicalChunkIndexService).deleteByKbUuid("kb-uuid");
    }
}
