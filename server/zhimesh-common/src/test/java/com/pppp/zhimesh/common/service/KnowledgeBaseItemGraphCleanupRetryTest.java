package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.GraphicalStatusEnum;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.rag.GraphRag;
import com.pppp.zhimesh.common.rag.GraphRagContext;
import com.pppp.zhimesh.common.rag.GraphStore;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.GraphIngestParam;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_GRAPH_CLEANUP_RETRY_SIGNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图谱清理失败的 Redis 标记语义（B4）：索引失败后 cleanupDocument 再失败时向
 * Redis set 写入 "kbUuid:kbItemUuid" 待重试成员（Redis 故障只降级 warn，不影
 * 响 FAIL 状态回写）；重建成功后成员被移除；消费侧逐个重试，成功移除、失败
 * 保留、格式非法直接丢弃，单成员失败不阻塞其余成员；清理前先核对条目当前状
 * 态——仅 FAIL 才清理，DONE/NONE 丢弃陈旧标记，DOING 推迟到下轮，条目已删除
 * 仍清理残留数据。
 * B4 retry-marker semantics: a cleanup that fails after an indexing error queues
 * a "kbUuid:kbItemUuid" Redis member (Redis failures only warn and never block
 * the FAIL write-back), a successful rebuild removes it, and the consumer
 * retries members one by one — success removes, failure keeps, malformed drops.
 * Before cleaning, the item's current status is checked: only FAIL is cleaned,
 * DONE/NONE drop the stale marker, DOING defers to the rebuild, and a deleted
 * item still gets its residual AGE data cleaned.
 */
class KnowledgeBaseItemGraphCleanupRetryTest {

    /**
     * Stubs only the graph surface indexingGraph touches. {@code failCleanupFromCall}
     * makes cleanupDocument throw from the Nth call on (2 = the post-failure
     * retry), {@code failingMembers} makes specific members always fail for the
     * consumer-side tests.
     */
    private static final class StubGraphRag extends GraphRag {
        private final int failCleanupFromCall;
        private final Set<String> failingMembers;
        private final AtomicInteger cleanupCalls = new AtomicInteger();
        final List<String> cleanedMembers = new CopyOnWriteArrayList<>();

        StubGraphRag(String name, int failCleanupFromCall, Set<String> failingMembers) {
            super(name, mock(GraphStore.class));
            this.failCleanupFromCall = failCleanupFromCall;
            this.failingMembers = failingMembers;
        }

        @Override
        public void cleanupDocument(String kbUuid, String kbItemUuid) {
            String member = kbUuid + ":" + kbItemUuid;
            cleanedMembers.add(member);
            if (failCleanupFromCall > 0 && cleanupCalls.incrementAndGet() >= failCleanupFromCall) {
                throw new IllegalStateException("age cleanup down");
            }
            if (failingMembers.contains(member)) {
                throw new IllegalStateException("age cleanup down for " + member);
            }
        }

        @Override
        public void ingest(GraphIngestParam graphIngestParam) {
            // Success path: no graph writes.
        }
    }

    /** Write-side harness: indexingGraph resolves GraphRag from the static context. */
    private static final class WriteSideHarness {
        final KnowledgeBaseItemService service;
        final KnowledgeBaseItemMapper mapper;
        final SetOperations<String, String> setOperations;

        WriteSideHarness(StubGraphRag rag, RuntimeException redisAddFailure,
                List<GraphRagRegistration> registrations) {
            service = new KnowledgeBaseItemService();
            mapper = mock(KnowledgeBaseItemMapper.class);
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            SetOperations<String, String> setOperations = mock(SetOperations.class);
            this.setOperations = setOperations;
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            if (redisAddFailure != null) {
                when(setOperations.add(anyString(), anyString())).thenThrow(redisAddFailure);
            }
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            ReflectionTestUtils.setField(service, "entityClass", KnowledgeBaseItem.class);
            ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
            ReflectionTestUtils.setField(service, "adiProperties", new ZhiMeshProperties());
            service.initializeIndexingConcurrency();
            when(mapper.update(isNull(), any())).thenReturn(1);
            // indexingGraph reads the context directly (not the seam), so the
            // stub must be registered; @AfterEach restores the previous entry
            // (or removes the key) so later tests in the same fork are
            // order-independent.
            registrations.add(new GraphRagRegistration(rag.getName(), GraphRagContext.get(rag.getName())));
            GraphRagContext.add(rag);
        }
    }

    /** Consumer-side harness: retryPendingGraphCleanups goes through the seam. */
    private static final class ConsumerHarness {
        final KnowledgeBaseItemService service;
        final StubGraphRag rag;
        final SetOperations<String, String> setOperations;
        /** Row the status guard resolves; null = the item was deleted. */
        final AtomicReference<KnowledgeBaseItem> itemRow = new AtomicReference<>();

        ConsumerHarness(StubGraphRag rag, Set<String> members) {
            this.rag = rag;
            service = new KnowledgeBaseItemService() {
                @Override
                GraphRag knowledgeBaseGraphRag() {
                    return rag;
                }
            };
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            SetOperations<String, String> setOperations = mock(SetOperations.class);
            this.setOperations = setOperations;
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            if (members != null) {
                when(setOperations.members(KB_GRAPH_CLEANUP_RETRY_SIGNAL))
                        .thenReturn(new java.util.LinkedHashSet<>(members));
            }
            KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
            when(mapper.selectOne(any())).thenAnswer(invocation -> itemRow.get());
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            ReflectionTestUtils.setField(service, "entityClass", KnowledgeBaseItem.class);
            ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        }
    }

    @BeforeAll
    static void initializeLambdaColumnCache() {
        // set() resolves lambda columns eagerly, which needs the entity's TableInfo.
        MybatisTableInfoTestSupport.init(KnowledgeBaseItem.class);
    }

    private SpringMessageSourceStub messageSourceStub;
    private Object savedLlmServices;
    private Object savedHealthService;
    private final List<GraphRagRegistration> graphRagRegistrations = new ArrayList<>();

    /** A stub registered in GraphRagContext plus the entry it displaced. */
    private record GraphRagRegistration(String name, GraphRag previous) {
    }

    @BeforeEach
    void installMessageSource() {
        // The failure path constructs BaseException (no model resolvable without
        // a Spring context), which resolves its i18n message via SpringUtil.
        messageSourceStub = SpringMessageSourceStub.install();
        // Capture the statics this test may touch so @AfterEach restores the
        // exact pre-test state instead of clobbering it with empty values.
        savedLlmServices = ReflectionTestUtils.getField(LLMContext.class, "LLM_SERVICES");
        savedHealthService = ReflectionTestUtils.getField(LLMContext.class, "healthService");
    }

    @AfterEach
    void resetStatics() {
        messageSourceStub.close();
        ReflectionTestUtils.setField(LLMContext.class, "LLM_SERVICES", savedLlmServices);
        ReflectionTestUtils.setField(LLMContext.class, "healthService", savedHealthService);
        for (GraphRagRegistration registration : graphRagRegistrations) {
            if (registration.previous() == null) {
                removeGraphRagRegistration(registration.name());
            } else {
                GraphRagContext.add(registration.previous());
            }
        }
        graphRagRegistrations.clear();
    }

    /** The registry has no remove API, so unregistering goes through its map. */
    @SuppressWarnings("unchecked")
    private static void removeGraphRagRegistration(String name) {
        Map<String, GraphRag> registry =
                (Map<String, GraphRag>) ReflectionTestUtils.getField(GraphRagContext.class, "NAME_TO_RAG");
        registry.remove(name);
    }

    @Test
    void failedCleanupAfterGraphIngestionErrorQueuesRetryMarker() {
        // Pre-ingest cleanup (1st call) succeeds; the post-failure cleanup (2nd
        // call) throws, which is the situation the marker exists for.
        StubGraphRag rag = new StubGraphRag(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE, 2, Set.of());
        WriteSideHarness harness = new WriteSideHarness(rag, null, graphRagRegistrations);

        harness.service.indexingGraph(user(), knowledgeBase(), kbItem(), document(), null);

        // Without a Spring context the LLM resolution fails, the catch-block
        // cleanup then fails too, and the marker lands in the Redis set.
        verify(harness.setOperations).add(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-1:item-1");
        assertStatusWriteBack(harness, GraphicalStatusEnum.FAIL);
    }

    @Test
    void successfulGraphIndexingRemovesRetryMarker() {
        StubGraphRag rag = new StubGraphRag(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE, 0, Set.of());
        WriteSideHarness harness = new WriteSideHarness(rag, null, graphRagRegistrations);
        registerHealthyLlm();

        harness.service.indexingGraph(user(), knowledgeBase(), kbItem(), document(), null);

        // The rebuild superseded any contribution a previously failed attempt
        // left behind, so its marker is removed; nothing is ever queued.
        verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-1:item-1");
        verify(harness.setOperations, never()).add(anyString(), anyString());
        assertStatusWriteBack(harness, GraphicalStatusEnum.DONE);
    }

    @Test
    void redisFailureWhileQueuingMarkerDoesNotBlockFailWriteBack() {
        StubGraphRag rag = new StubGraphRag(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE, 1, Set.of());
        WriteSideHarness harness = new WriteSideHarness(rag, new RuntimeException("redis down"),
                graphRagRegistrations);

        assertDoesNotThrow(() ->
                harness.service.indexingGraph(user(), knowledgeBase(), kbItem(), document(), null));

        // The marker add was attempted (and failed) yet the FAIL status still
        // landed: Redis availability must not gate the failure write-back.
        verify(harness.setOperations).add(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-1:item-1");
        assertStatusWriteBack(harness, GraphicalStatusEnum.FAIL);
    }

    @Test
    void retryRemovesSuccessfulAndMalformedMembersAndKeepsFailingOnes() {
        StubGraphRag rag = new StubGraphRag("consumer", 0, Set.of("kb-2:item-2"));
        ConsumerHarness harness = new ConsumerHarness(rag,
                Set.of("kb-1:item-1", "kb-2:item-2", "not-a-marker", ":leading", "trailing:"));

        harness.service.retryPendingGraphCleanups();

        // Only well-formed members reach cleanupDocument; the failing member
        // does not block the valid one after it.
        assertThat(rag.cleanedMembers).containsExactlyInAnyOrder("kb-1:item-1", "kb-2:item-2");
        // Successful cleanup and malformed members are removed...
        verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-1:item-1");
        verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "not-a-marker");
        verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, ":leading");
        verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "trailing:");
        // ...while the failing cleanup keeps its marker for the next tick.
        verify(harness.setOperations, never()).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-2:item-2");
    }

    @Test
    void retryDefersCleanupWhileTheItemIsBeingReIndexed() {
        StubGraphRag rag = new StubGraphRag("consumer", 0, Set.of());
        ConsumerHarness harness = new ConsumerHarness(rag, Set.of("kb-1:item-1"));
        KnowledgeBaseItem item = kbItem();
        item.setGraphicalStatus(GraphicalStatusEnum.DOING);
        harness.itemRow.set(item);

        harness.service.retryPendingGraphCleanups();

        // A rebuild is writing this item's contribution right now; cleaning it
        // would delete fresh graph data, so the marker waits for a later tick.
        assertThat(rag.cleanedMembers).isEmpty();
        verify(harness.setOperations, never()).remove(anyString(), any());
    }

    @Test
    void retryDropsStaleMarkerWhenTheItemIsDoneOrReset() {
        for (GraphicalStatusEnum staleStatus
                : new GraphicalStatusEnum[]{GraphicalStatusEnum.DONE, GraphicalStatusEnum.NONE}) {
            StubGraphRag rag = new StubGraphRag("consumer", 0, Set.of());
            ConsumerHarness harness = new ConsumerHarness(rag, Set.of("kb-1:item-1"));
            KnowledgeBaseItem item = kbItem();
            item.setGraphicalStatus(staleStatus);
            harness.itemRow.set(item);

            harness.service.retryPendingGraphCleanups();

            // DONE holds a live contribution and NONE has none left to clean;
            // either way a stale marker (e.g. one whose removal Redis
            // swallowed) is dropped without touching the graph.
            assertThat(rag.cleanedMembers).isEmpty();
            verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-1:item-1");
        }
    }

    @Test
    void retryCleansResidualGraphDataWhenTheItemRowIsGone() {
        StubGraphRag rag = new StubGraphRag("consumer", 0, Set.of());
        ConsumerHarness harness = new ConsumerHarness(rag, Set.of("kb-1:item-1"));

        harness.service.retryPendingGraphCleanups();

        // Deletion never removes markers and its own cleanup is best-effort,
        // so a deleted item's residual AGE data is still worth cleaning.
        assertThat(rag.cleanedMembers).containsExactly("kb-1:item-1");
        verify(harness.setOperations).remove(KB_GRAPH_CLEANUP_RETRY_SIGNAL, "kb-1:item-1");
    }

    @Test
    void retrySurvivesRedisReadFailureWithoutTouchingTheGraph() {
        StubGraphRag rag = new StubGraphRag("consumer", 0, Set.of());
        ConsumerHarness harness = new ConsumerHarness(rag, null);
        when(harness.setOperations.members(KB_GRAPH_CLEANUP_RETRY_SIGNAL))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(harness.service::retryPendingGraphCleanups);

        assertThat(rag.cleanedMembers).isEmpty();
    }

    @Test
    void retryWithoutMarkersLeavesTheGraphUntouched() {
        // Unstubbed members() returns null (missing key), the other empty case.
        StubGraphRag rag = new StubGraphRag("consumer", 0, Set.of());
        ConsumerHarness harness = new ConsumerHarness(rag, null);

        harness.service.retryPendingGraphCleanups();

        assertThat(rag.cleanedMembers).isEmpty();
        verify(harness.setOperations, never()).remove(anyString(), any());
    }

    /** Registers an LLM the graph path can resolve without a Spring context. */
    private void registerHealthyLlm() {
        AiModel aiModel = new AiModel();
        aiModel.setId(1L);
        aiModel.setName("stub-model");
        aiModel.setIsFree(true);
        ModelPlatform platform = new ModelPlatform();
        platform.setName("stub-platform");
        AbstractLLMService llmService = mock(AbstractLLMService.class);
        when(llmService.getAiModel()).thenReturn(aiModel);
        when(llmService.getPlatform()).thenReturn(platform);
        when(llmService.buildChatLLM(any(ChatModelBuilderProperties.class))).thenReturn(mock(ChatModel.class));
        LLMContext.addLLMService(llmService);
        ModelHealthService healthService = mock(ModelHealthService.class);
        when(healthService.isHealthy(anyString(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(LLMContext.class, "healthService", healthService);
    }

    /** The second mapper update is the terminal status write (DOING start came first). */
    private void assertStatusWriteBack(WriteSideHarness harness, GraphicalStatusEnum expected) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(harness.mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> last = captor.getValue();
        // Condition parameters register lazily, when the SQL segment renders.
        last.getTargetSql();
        assertTrue(last.getParamNameValuePairs().containsValue(expected),
                "terminal status write-back must set " + expected);
    }

    private User user() {
        return new User();
    }

    private KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setIngestModelId(1L);
        // The GraphIngestParam builder unboxes these; nulls would NPE the run.
        knowledgeBase.setIngestMaxOverlap(100);
        knowledgeBase.setIngestMaxSegmentSize(800);
        knowledgeBase.setQueryLlmTemperature(0.7);
        return knowledgeBase;
    }

    private KnowledgeBaseItem kbItem() {
        KnowledgeBaseItem kbItem = new KnowledgeBaseItem();
        kbItem.setId(7L);
        kbItem.setUuid("item-1");
        kbItem.setKbUuid("kb-1");
        kbItem.setTitle("title");
        return kbItem;
    }

    private Document document() {
        return new DefaultDocument("content", new Metadata());
    }
}
