package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrievalRouteKey;
import com.pppp.zhimesh.common.rag.intent.RoutedRetriever;
import com.pppp.zhimesh.common.rag.bm25.Bm25ContentRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeduplicatingContentRetrieverTest {

    @Test
    void usesExplicitRequestTokenEstimatorInsteadOfThreadLocalState() {
        TokenCountEstimator estimator = mock(TokenCountEstimator.class);
        when(estimator.estimateTokenCountInText(any())).thenReturn(1);
        RoutedRetriever route = new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb"),
                query -> List.of(Content.from(TextSegment.from("request scoped evidence"))));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(route), null, 5, false, null, new ZhiMeshProperties.Retrieval(),
                5000, "system", true, estimator);

        assertEquals(List.of("request scoped evidence"), texts(retriever.retrieve(Query.from("question"))));
        verify(estimator, atLeastOnce()).estimateTokenCountInText(any());
    }

    @Test
    void executorSaturationFailsRouteWithoutRunningItOnCallerThread() {
        AtomicInteger invocations = new AtomicInteger();
        RoutedRetriever route = new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb"),
                query -> {
                    invocations.incrementAndGet();
                    return List.of(Content.from(TextSegment.from("must not run")));
                });
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        when(executor.submit(org.mockito.ArgumentMatchers.<Callable<RetrievalRouteResult>>any()))
                .thenThrow(new RejectedExecutionException("saturated"));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(route), null, 5, false, executor, new ZhiMeshProperties.Retrieval(),
                0, null, true);

        assertThrows(IllegalStateException.class, () -> retriever.retrieve(Query.from("question")));
        assertEquals(0, invocations.get());
        assertEquals(RetrievalRouteStatus.ERROR, retriever.getRouteResults().get(0).status());
        assertEquals("RejectedExecutionException", retriever.getRouteResults().get(0).errorType());
    }

    @Test
    void removesOnlyExactDuplicateBeforeReranking() {
        Content original = Content.from(TextSegment.from("Microsoft invested in OpenAI in 2019."));
        Content duplicate = Content.from(TextSegment.from("Microsoft invested in OpenAI in 2019."));
        Content containing = Content.from(TextSegment.from("According to the document, Microsoft invested in OpenAI in 2019."));
        Content different = Content.from(TextSegment.from("OpenAI uses Azure cloud infrastructure."));

        List<Content> result = DeduplicatingContentRetriever.deduplicate(List.of(original, duplicate, containing, different));

        assertEquals(List.of(original, containing, different), result);
    }

    @Test
    void prefersDiverseEvidenceAfterRankingAndUsesSimilarEvidenceAsFallback() {
        Content best = Content.from(TextSegment.from("Microsoft invested in OpenAI in 2019."));
        Content nearDuplicate = Content.from(TextSegment.from(
                "According to the document, Microsoft invested in OpenAI in 2019."));
        Content diverse = Content.from(TextSegment.from("OpenAI uses Azure cloud infrastructure."));

        assertEquals(List.of(best, diverse),
                DeduplicatingContentRetriever.selectDiverse(List.of(best, nearDuplicate, diverse), 2));
        assertEquals(List.of(best, nearDuplicate),
                DeduplicatingContentRetriever.selectDiverse(List.of(best, nearDuplicate), 2));
    }

    @Test
    void keepsRelatedButDifferentFacts() {
        Content investment = Content.from(TextSegment.from("Microsoft invested in OpenAI."));
        Content infrastructure = Content.from(TextSegment.from("Microsoft provides Azure infrastructure to OpenAI."));

        List<Content> result = DeduplicatingContentRetriever.deduplicate(List.of(investment, infrastructure));

        assertEquals(List.of(investment, infrastructure), result);
    }

    @Test
    void interleavesRoutesWhenRerankerIsDisabled() {
        Content vectorOne = Content.from(TextSegment.from("vector one"));
        Content vectorTwo = Content.from(TextSegment.from("vector two"));
        Content graphOne = Content.from(TextSegment.from("graph one"));
        Content graphTwo = Content.from(TextSegment.from("graph two"));
        ContentRetriever vector = query -> List.of(vectorOne, vectorTwo);
        ContentRetriever graph = query -> List.of(graphOne, graphTwo);
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(vector, graph), null, 3, false, null, new ZhiMeshProperties.Retrieval());

        assertEquals(List.of("vector one", "graph one", "vector two"),
                texts(retriever.retrieve(Query.from("question"))));
    }

    @Test
    void protectsVectorTopOneWithoutChangingRemainingFusionOrder() {
        RetrievedCandidate vectorTopOne =
                candidate("vector top one", "vector", 1, 0.90D, "doc-a");
        RetrievedCandidate sharedTopOne = candidate("shared top one", "graph", 1, "doc-a");
        sharedTopOne.merge(candidate("shared top one", "vector", 3, 0.82D, "doc-a"));
        RetrievedCandidate sharedTopTwo = candidate("shared top two", "graph", 2, "doc-a");
        sharedTopTwo.merge(candidate("shared top two", "vector", 4, 0.80D, "doc-a"));
        List<RetrievedCandidate> rrfOrder = List.of(sharedTopOne, sharedTopTwo, vectorTopOne);

        List<RetrievedCandidate> result =
                DeduplicatingContentRetriever.prioritizeVectorEvidence(
                        rrfOrder, 1, 0.05D, 3, 2);

        assertEquals(List.of("vector top one", "shared top one", "shared top two"),
                result.stream().map(RetrievedCandidate::text).toList());
    }

    @Test
    void leavesFusionOrderUnchangedWhenVectorLeadIsTooSmall() {
        RetrievedCandidate vectorTopOne = candidate("vector top one", "vector", 1, 0.90D);
        RetrievedCandidate sharedTopOne = candidate("shared top one", "graph", 1);
        sharedTopOne.merge(candidate("shared top one", "vector", 2, 0.88D));
        List<RetrievedCandidate> rrfOrder = List.of(sharedTopOne, vectorTopOne);

        assertEquals(rrfOrder,
                DeduplicatingContentRetriever.prioritizeVectorEvidence(
                        rrfOrder, 1, 0.05D, 2, 2));
    }

    @Test
    void leavesConfidentVectorTopOneInPlaceWhenItIsAlreadySafe() {
        RetrievedCandidate vectorTopOne =
                candidate("vector top one", "vector", 1, 0.90D);
        RetrievedCandidate sharedTopOne = candidate("shared top one", "graph", 1);
        sharedTopOne.merge(candidate("shared top one", "vector", 2, 0.82D));
        List<RetrievedCandidate> rrfOrder = List.of(sharedTopOne, vectorTopOne);

        assertEquals(rrfOrder,
                DeduplicatingContentRetriever.prioritizeVectorEvidence(
                        rrfOrder, 1, 0.05D, 2, 2));
    }

    @Test
    void vectorProtectionCanBeDisabledAndLeavesGraphOnlyOrderUnchanged() {
        RetrievedCandidate graphOne = candidate("graph one", "graph", 1);
        RetrievedCandidate graphTwo = candidate("graph two", "graph", 2);
        List<RetrievedCandidate> graphOrder = List.of(graphOne, graphTwo);

        assertEquals(graphOrder,
                DeduplicatingContentRetriever.prioritizeVectorEvidence(
                        graphOrder, 1, 0.05D, 2, 2));
        assertEquals(graphOrder,
                DeduplicatingContentRetriever.prioritizeVectorEvidence(
                        graphOrder, 0, 0.05D, 2, 2));
    }

    @Test
    void vectorProtectionCoversHybridRetrievalWithBm25Present() {
        assertTrue(DeduplicatingContentRetriever.shouldProtectVectorEvidence(
                false, 1, Set.of("vector", "graph", "bm25")));
        assertTrue(DeduplicatingContentRetriever.shouldProtectVectorEvidence(
                false, 1, Set.of("vector", "graph")));
    }

    @Test
    void vectorProtectionRequiresBothVectorAndGraphUsableEvidence() {
        assertFalse(DeduplicatingContentRetriever.shouldProtectVectorEvidence(
                false, 1, Set.of("vector", "bm25")));
        assertFalse(DeduplicatingContentRetriever.shouldProtectVectorEvidence(
                false, 1, Set.of("graph", "bm25")));
        // A successful rerank replaces the RRF order, so protection is moot.
        assertFalse(DeduplicatingContentRetriever.shouldProtectVectorEvidence(
                true, 1, Set.of("vector", "graph", "bm25")));
        assertFalse(DeduplicatingContentRetriever.shouldProtectVectorEvidence(
                false, 0, Set.of("vector", "graph", "bm25")));
    }

    @Test
    void removesWeakRerankTailUsingRelativeScore() {
        RetrievedCandidate best = rerankedCandidate("best", 0.90D);
        RetrievedCandidate weak = rerankedCandidate("weak", 0.10D);
        RetrievedCandidate usefulAfterDiversification = rerankedCandidate("useful", 0.50D);

        List<RetrievedCandidate> result =
                DeduplicatingContentRetriever.applyRerankScoreCutoff(
                        List.of(best, weak, usefulAfterDiversification), 1, 0.30D);

        assertEquals(List.of("best", "useful"),
                result.stream().map(RetrievedCandidate::text).toList());
    }

    @Test
    void rerankCutoffAlwaysKeepsConfiguredMinimumAndCanBeDisabled() {
        RetrievedCandidate best = rerankedCandidate("best", 0.90D);
        RetrievedCandidate weakOne = rerankedCandidate("weak one", 0.10D);
        RetrievedCandidate weakTwo = rerankedCandidate("weak two", 0.05D);
        List<RetrievedCandidate> ranked = List.of(best, weakOne, weakTwo);

        assertEquals(List.of(best, weakOne),
                DeduplicatingContentRetriever.applyRerankScoreCutoff(
                        ranked, 2, 0.30D));
        assertEquals(ranked,
                DeduplicatingContentRetriever.applyRerankScoreCutoff(
                        ranked, 1, 0D));
    }

    @Test
    void packsFinalContextWithinTokenBudget() {
        ContentRetriever route = query -> List.of(
                Content.from(TextSegment.from("one two three four five six.")),
                Content.from(TextSegment.from("seven eight nine ten eleven twelve.")));
        ZhiMeshProperties.Retrieval properties = new ZhiMeshProperties.Retrieval();
        properties.setContextMaxTokens(8);
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(route), null, 5, false, null, properties);

        List<Content> result = retriever.retrieve(Query.from("question"));
        int tokens = result.stream().mapToInt(content -> TokenEstimatorFactory.create(null)
                .estimateTokenCountInText(content.textSegment().text())).sum();

        assertTrue(tokens <= 8);
        assertFalse(result.isEmpty());
    }

    @Test
    void nearDuplicateFromAnotherRouteIsNotPackedIntoTheContext() {
        // A graph segment and its vector chunk of the same source text differ
        // only in framing; only the first may occupy context slots.
        ContentRetriever vector = query -> List.of(Content.from(TextSegment.from(
                "According to the document, Microsoft invested in OpenAI in 2019.")));
        ContentRetriever graph = query -> List.of(Content.from(TextSegment.from(
                "Microsoft invested in OpenAI in 2019.")));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(vector, graph), null, 5, false, null, new ZhiMeshProperties.Retrieval());

        assertEquals(List.of("According to the document, Microsoft invested in OpenAI in 2019."),
                texts(retriever.retrieve(Query.from("question"))));
    }

    @Test
    void documentCapRefillStillPacksNonRedundantDeferredEvidence() {
        ContentRetriever route = query -> List.of(
                documentChunk("doc-a", "alpha handles request routing for the gateway"),
                documentChunk("doc-a", "beta persists order records with strict schema"),
                documentChunk("doc-a", "gamma emits audit events for every change"));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(route), null, 5, false, null, new ZhiMeshProperties.Retrieval());

        assertEquals(3, texts(retriever.retrieve(Query.from("question"))).size());
    }

    private static Content documentChunk(String documentId, String text) {
        return Content.from(TextSegment.from(text,
                new Metadata(Map.of(RetrievedCandidate.SOURCE_DOCUMENT_IDS, documentId))));
    }

    @Test
    void sameCanonicalChunkFromDifferentRoutesFusesByProvenance() {
        // Same canonical chunk uuid: the graph segment text may differ in
        // framing from the vector chunk, but they are one piece of evidence.
        ContentRetriever vector = query -> List.of(Content.from(TextSegment.from(
                "Microsoft invested in OpenAI in 2019.",
                new Metadata(Map.of(Bm25ContentRetriever.CHUNK_UUID, "chunk-9")))));
        ContentRetriever graph = query -> List.of(Content.from(TextSegment.from(
                "微软于2019年投资了OpenAI",
                new Metadata(Map.of(Bm25ContentRetriever.CHUNK_UUID, "chunk-9")))));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(
                        new RoutedRetriever(new RetrievalRouteKey(
                                KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb"), vector),
                        new RoutedRetriever(new RetrievalRouteKey(
                                KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.GRAPH, "kb"), graph)),
                null, 5, false, null, new ZhiMeshProperties.Retrieval(), 0, null, true);

        List<Content> selected = retriever.retrieve(Query.from("question"));

        assertEquals(1, selected.size());
        assertEquals("Microsoft invested in OpenAI in 2019.",
                selected.get(0).textSegment().text());
        assertEquals("vector,graph", selected.get(0).textSegment().metadata()
                .getString(RetrievedCandidate.ROUTES));
        assertEquals("chunk-9", selected.get(0).textSegment().metadata()
                .getString(Bm25ContentRetriever.CHUNK_UUID));
    }

    @Test
    void keepsUsableRouteWhenAnotherRouteFails() {
        Content expected = Content.from(TextSegment.from("vector evidence"));
        ContentRetriever successful = query -> List.of(expected);
        ContentRetriever failed = query -> { throw new IllegalStateException("graph unavailable"); };
        ZhiMeshProperties.Retrieval properties = new ZhiMeshProperties.Retrieval();
        properties.setRetryCount(0);
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(successful, failed), null, 5, true, null, properties);

        assertEquals(List.of("vector evidence"), texts(retriever.retrieve(Query.from("question"))));
        assertEquals(List.of(RetrievalRouteStatus.SUCCESS, RetrievalRouteStatus.ERROR),
                retriever.getRouteResults().stream().map(RetrievalRouteResult::status).toList());
    }

    @Test
    void strictModeStopsOnlyWhenAllRoutesAreNormallyEmpty() {
        ContentRetriever empty = query -> List.of();
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(empty, empty), null, 5, true, null, new ZhiMeshProperties.Retrieval());

        assertThrows(BaseException.class, () -> retriever.retrieve(Query.from("question")));
    }

    @Test
    void routeFailureWithoutAnyUsableContextIsSystemError() {
        ContentRetriever empty = query -> List.of();
        ContentRetriever failed = query -> { throw new IllegalStateException("database unavailable"); };
        ZhiMeshProperties.Retrieval properties = new ZhiMeshProperties.Retrieval();
        properties.setRetryCount(0);
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(empty, failed), null, 5, true, null, properties);

        assertThrows(IllegalStateException.class, () -> retriever.retrieve(Query.from("question")));
    }

    @Test
    void parallelTimeoutDoesNotDiscardAnotherRoutesEvidence() {
        Content expected = Content.from(TextSegment.from("fast evidence"));
        ContentRetriever fast = query -> List.of(expected);
        ContentRetriever slow = query -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return List.of(Content.from(TextSegment.from("late evidence")));
        };
        ZhiMeshProperties.Retrieval properties = new ZhiMeshProperties.Retrieval();
        properties.setVectorTimeoutMs(50);
        properties.setRetryCount(0);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(2);
        executor.initialize();
        try {
            DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                    List.of(fast, slow), null, 5, true, executor, properties);

            assertEquals(List.of("fast evidence"), texts(retriever.retrieve(Query.from("question"))));
            assertEquals(List.of(RetrievalRouteStatus.SUCCESS, RetrievalRouteStatus.TIMEOUT),
                    retriever.getRouteResults().stream().map(RetrievalRouteResult::status).toList());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void bm25UsesItsOwnTimeoutWithoutDiscardingVectorEvidence() {
        ContentRetriever vector = query -> List.of(Content.from(TextSegment.from("vector evidence")));
        ContentRetriever slowBm25 = query -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return List.of(Content.from(TextSegment.from("late bm25 evidence")));
        };
        ZhiMeshProperties.Retrieval properties = new ZhiMeshProperties.Retrieval();
        properties.getBm25().setTimeoutMs(25);
        properties.setVectorTimeoutMs(1000);
        properties.setRetryCount(0);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(2);
        executor.initialize();
        try {
            DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                    List.of(
                            new RoutedRetriever(new RetrievalRouteKey(
                                    KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb"), vector),
                            new RoutedRetriever(new RetrievalRouteKey(
                                    KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25, "kb"), slowBm25)),
                    null, 5, true, executor, properties, 0, null, true);

            assertEquals(List.of("vector evidence"), texts(retriever.retrieve(Query.from("question"))));
            assertEquals(List.of(RetrievalRouteStatus.SUCCESS, RetrievalRouteStatus.TIMEOUT),
                    retriever.getRouteResults().stream().map(RetrievalRouteResult::status).toList());
            assertEquals("bm25", retriever.getRouteResults().get(1).route());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectedCandidatesAreRemovedFromVectorAndBm25Provenance() {
        ZhiMeshEmbeddingStoreContentRetriever vector =
                mock(ZhiMeshEmbeddingStoreContentRetriever.class);
        Bm25ContentRetriever bm25 = mock(Bm25ContentRetriever.class);
        when(vector.retrieve(any(Query.class))).thenReturn(List.of(Content.from(TextSegment.from(
                "技术文档的信息架构与版本治理",
                new Metadata(Map.of("embedding_id", "embedding-1",
                        RetrievedCandidate.VECTOR_SCORE, 0.69D))))));
        when(bm25.retrieve(any(Query.class))).thenReturn(List.of(Content.from(TextSegment.from(
                "接口版本控制与数据库迁移规范",
                new Metadata(Map.of(Bm25ContentRetriever.CHUNK_UUID, "chunk-1",
                        RetrievedCandidate.BM25_SCORE, 3.2D))))));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(
                        new RoutedRetriever(new RetrievalRouteKey(
                                KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb"), vector),
                        new RoutedRetriever(new RetrievalRouteKey(
                                KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25, "kb"), bm25)),
                null, 5, false, null, new ZhiMeshProperties.Retrieval(),
                0, null, true, null, true);

        assertTrue(retriever.retrieve(Query.from("火影忍者里面的面具男是谁？")).isEmpty());
        verify(vector).retainRetrievedEmbeddings(Set.of());
        verify(bm25).retainRetrievedHits(Set.of());
    }

    @Test
    void graphProvenanceIsRestrictedToSelectedGraphElements() {
        GraphStoreContentRetriever graph = mock(GraphStoreContentRetriever.class);
        when(graph.retrieve(any(Query.class))).thenReturn(List.of(Content.from(TextSegment.from(
                "订单服务依赖库存服务",
                new Metadata(Map.of(
                        RetrievedCandidate.GRAPH_ELEMENT_IDS, "edge-1,vertex-1",
                        RetrievedCandidate.ROUTE, "graph"))))));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(new RoutedRetriever(new RetrievalRouteKey(
                        KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.GRAPH, "kb"), graph)),
                null, 3, false, null, new ZhiMeshProperties.Retrieval(),
                0, null, true);

        retriever.retrieve(Query.from("订单服务依赖什么"));

        verify(graph).retainRetrievedReference(Set.of("edge-1", "vertex-1"));
    }

    @Test
    void preRerankGateAvoidsRemoteCallWhenOnlyOneCandidateSurvives() {
        BgeReranker reranker = mock(BgeReranker.class);
        RoutedRetriever vector = new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb"),
                query -> List.of(
                        Content.from(TextSegment.from("技术文档版本治理",
                                new Metadata(Map.of(RetrievedCandidate.VECTOR_SCORE, 0.69D)))),
                        Content.from(TextSegment.from("面具男的身份分析",
                                new Metadata(Map.of(RetrievedCandidate.VECTOR_SCORE, 0.69D))))));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(vector), reranker, 3, false, null, new ZhiMeshProperties.Retrieval(),
                0, null, true, null, true);

        assertEquals(List.of("面具男的身份分析"),
                texts(retriever.retrieve(Query.from("火影忍者里面的面具男是谁？"))));
        verifyNoInteractions(reranker);
    }

    @Test
    void forcedSingleCandidateRerankRejectsCandidateBelowAbsoluteFloor() {
        BgeReranker reranker = mock(BgeReranker.class);
        when(reranker.rerank(eq("我平时喜欢什么饮料？"), any(), eq(1),
                anyLong(), anyInt(), anyLong(), eq(true)))
                .thenReturn(RerankResult.success(
                        List.of(new RerankResult.RerankScore(0, 0.20D)), 5L));
        DeduplicatingContentRetriever retriever = forcedSingleCandidateRetriever(reranker);

        assertTrue(retriever.retrieve(Query.from("我平时喜欢什么饮料？")).isEmpty());
        verify(reranker).rerank(eq("我平时喜欢什么饮料？"), any(), eq(1),
                anyLong(), anyInt(), anyLong(), eq(true));
    }

    @Test
    void forcedSingleCandidateRerankRetainsCandidateAboveAbsoluteFloor() {
        BgeReranker reranker = mock(BgeReranker.class);
        when(reranker.rerank(eq("我平时喜欢什么饮料？"), any(), eq(1),
                anyLong(), anyInt(), anyLong(), eq(true)))
                .thenReturn(RerankResult.success(
                        List.of(new RerankResult.RerankScore(0, 0.80D)), 5L));
        DeduplicatingContentRetriever retriever = forcedSingleCandidateRetriever(reranker);

        assertEquals(List.of("用户平时喜欢无糖咖啡"),
                texts(retriever.retrieve(Query.from("我平时喜欢什么饮料？"))));
        verify(reranker).rerank(eq("我平时喜欢什么饮料？"), any(), eq(1),
                anyLong(), anyInt(), anyLong(), eq(true));
    }

    private static DeduplicatingContentRetriever forcedSingleCandidateRetriever(BgeReranker reranker) {
        RoutedRetriever vector = new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.CHARACTER_MEMORY,
                        RetrievalRoute.VECTOR, "semantic-memory"),
                query -> List.of(Content.from(TextSegment.from(
                        "用户平时喜欢无糖咖啡",
                        new Metadata(Map.of(RetrievedCandidate.VECTOR_SCORE, 0.80D))))));
        return new DeduplicatingContentRetriever(
                List.of(vector), reranker, 1, false, null,
                new ZhiMeshProperties.Retrieval(), 0, null,
                true, null, true, true);
    }

    private static List<String> texts(List<Content> contents) {
        return contents.stream().map(content -> content.textSegment().text()).toList();
    }

    private static RetrievedCandidate candidate(String text, String route, int rank) {
        return RetrievedCandidate.from(Content.from(TextSegment.from(text)), route, rank);
    }

    private static RetrievedCandidate candidate(
            String text, String route, int rank, double vectorScore) {
        Metadata metadata = new Metadata(Map.of(RetrievedCandidate.VECTOR_SCORE, vectorScore));
        return RetrievedCandidate.from(
                Content.from(TextSegment.from(text, metadata)), route, rank);
    }

    private static RetrievedCandidate candidate(
            String text, String route, int rank, String documentId) {
        Metadata metadata = new Metadata(Map.of(
                RetrievedCandidate.SOURCE_DOCUMENT_IDS, documentId));
        return RetrievedCandidate.from(
                Content.from(TextSegment.from(text, metadata)), route, rank);
    }

    private static RetrievedCandidate candidate(
            String text, String route, int rank, double vectorScore, String documentId) {
        Metadata metadata = new Metadata(Map.of(
                RetrievedCandidate.VECTOR_SCORE, vectorScore,
                RetrievedCandidate.SOURCE_DOCUMENT_IDS, documentId));
        return RetrievedCandidate.from(
                Content.from(TextSegment.from(text, metadata)), route, rank);
    }

    private static RetrievedCandidate rerankedCandidate(String text, double score) {
        RetrievedCandidate candidate = candidate(text, "vector", 1);
        candidate.setRerankScore(score);
        return candidate;
    }
}
