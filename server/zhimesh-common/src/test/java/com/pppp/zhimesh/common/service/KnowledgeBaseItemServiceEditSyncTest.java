package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbItemEditReq;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.EmbeddingStatusEnum;
import com.pppp.zhimesh.common.enums.FulltextStatusEnum;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.rag.bm25.Bm25IndexService;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编辑同步删向量语义：内容变化时旧向量与 BM25 同步停止服务，且 embedding
 * 状态随 fulltext 一起复位（NONE + 新 changeTime + startedAt/completedAt 置空）；
 * 内容未变则既不删除任何索引也不复位状态。
 * Edit-sync semantics: a content change drops the old vectors together with
 * BM25 and resets the embedding lifecycle state; unchanged content leaves
 * every derived index untouched.
 */
class KnowledgeBaseItemServiceEditSyncTest {

    private KnowledgeBaseItemService service;
    private KnowledgeBaseItemMapper itemMapper;
    private Bm25IndexService bm25IndexService;
    private IKnowledgeEmbeddingService embeddingService;
    private KnowledgeBaseItem previous;
    private SpringMessageSourceStub messageSourceStub;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        // 每个实体独立的 MapperBuilderAssistant：namespace 只能设置一次
        MybatisTableInfoTestSupport.init(KnowledgeBaseItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseItemService();
        itemMapper = mock(KnowledgeBaseItemMapper.class);
        bm25IndexService = mock(Bm25IndexService.class);
        embeddingService = mock(IKnowledgeEmbeddingService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        ReflectionTestUtils.setField(service, "baseMapper", itemMapper);
        ReflectionTestUtils.setField(service, "entityClass", KnowledgeBaseItem.class);
        ReflectionTestUtils.setField(service, "bm25IndexService", bm25IndexService);
        ReflectionTestUtils.setField(service, "iKnowledgeEmbeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        // Admin bypasses the write-privilege probes; the edit-sync branch is the target.
        User admin = new User();
        admin.setId(9L);
        admin.setIsAdmin(true);
        ThreadContext.setCurrentUser(admin);
        messageSourceStub = SpringMessageSourceStub.install();

        previous = new KnowledgeBaseItem();
        previous.setId(7L);
        previous.setUuid("item-uuid");
        previous.setKbId(100L);
        previous.setKbUuid("kb-uuid");
        previous.setTitle("old title");
        previous.setRemark("old content");
        previous.setActiveChunkSetUuid("chunk-set");
        previous.setEmbeddingChunkSetUuid("chunk-set");
        previous.setGraphicalChunkSetUuid("chunk-set");
        previous.setFulltextChunkSetUuid("chunk-set");
        previous.setEmbeddingStatus(EmbeddingStatusEnum.DONE);
        previous.setEmbeddingStatusChangeTime(LocalDateTime.of(2026, 9, 1, 0, 0));
        previous.setEmbeddingStartedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        previous.setEmbeddingCompletedAt(LocalDateTime.of(2026, 9, 1, 0, 5));
        previous.setFulltextStatus(FulltextStatusEnum.DONE);
        when(itemMapper.selectById(7L)).thenReturn(previous);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
        messageSourceStub.close();
    }

    @Test
    void contentChangeDeletesOldVectorsAndResetsEmbeddingState() {
        KbItemEditReq req = editRequest("new content");

        service.saveOrUpdate(req);

        // Old BM25 rows and old vectors must stop serving in the same window.
        verify(bm25IndexService).deleteByItemUuid("item-uuid");
        verify(embeddingService).deleteByItemUuid("item-uuid");

        ArgumentCaptor<KnowledgeBaseItem> captor = ArgumentCaptor.forClass(KnowledgeBaseItem.class);
        verify(itemMapper).updateById(captor.capture());
        KnowledgeBaseItem updated = captor.getValue();
        // Embedding lifecycle reset mirrors the fulltext reset in the same branch.
        assertEquals(EmbeddingStatusEnum.NONE, updated.getEmbeddingStatus());
        assertNotNull(updated.getEmbeddingStatusChangeTime());
        assertNull(updated.getEmbeddingStartedAt());
        assertNull(updated.getEmbeddingCompletedAt());
        assertEquals("", updated.getEmbeddingChunkSetUuid());
        assertEquals("", updated.getActiveChunkSetUuid());
        // The pre-existing fulltext reset is preserved alongside.
        assertEquals(FulltextStatusEnum.NONE, updated.getFulltextStatus());
        assertNotNull(updated.getFulltextStatusChangeTime());
    }

    @Test
    void unchangedContentKeepsVectorsAndEmbeddingState() {
        KbItemEditReq req = editRequest("old content");

        service.saveOrUpdate(req);

        verify(bm25IndexService, never()).deleteByItemUuid(anyString());
        verify(embeddingService, never()).deleteByItemUuid(anyString());

        ArgumentCaptor<KnowledgeBaseItem> captor = ArgumentCaptor.forClass(KnowledgeBaseItem.class);
        verify(itemMapper).updateById(captor.capture());
        KnowledgeBaseItem updated = captor.getValue();
        // No reset of any derived-index state when the source content is unchanged.
        assertNull(updated.getEmbeddingStatus());
        assertNull(updated.getEmbeddingStatusChangeTime());
        assertNull(updated.getFulltextStatus());
    }

    private KbItemEditReq editRequest(String remark) {
        KbItemEditReq req = new KbItemEditReq();
        req.setId(7L);
        req.setKbId(100L);
        req.setKbUuid("kb-uuid");
        req.setTitle("edited title");
        req.setRemark(remark);
        return req;
    }
}
