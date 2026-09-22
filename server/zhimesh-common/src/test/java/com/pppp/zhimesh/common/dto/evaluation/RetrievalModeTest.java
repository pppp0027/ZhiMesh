package com.pppp.zhimesh.common.dto.evaluation;

import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalModeTest {

    @Test
    void acceptsCaseInsensitiveValuesAndDefaultsBlankToHybrid() {
        assertEquals(RetrievalMode.VECTOR, RetrievalMode.fromJson("vector"));
        assertEquals(RetrievalMode.GRAPH, RetrievalMode.fromJson("GRAPH"));
        assertEquals(RetrievalMode.HYBRID, RetrievalMode.fromJson(" "));
        assertEquals("hybrid", RetrievalMode.HYBRID.toJson());
    }

    @Test
    void rejectsUnsupportedMode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RetrievalMode.fromJson("keyword"));
        assertTrue(error.getMessage().contains("vector, graph, hybrid"));
    }

    @Test
    void requestDefaultsRemainBackwardCompatible() {
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                null, null, null, null, null);
        assertEquals(0D, request.effectiveTemperature());
        assertEquals(RetrievalMode.HYBRID, request.effectiveRetrievalMode());
        assertNull(request.useReranker());
        assertFalse(request.effectiveRetrievalOnly());
        assertFalse(request.effectiveIncludeQueryEmbedding());
        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH),
                request.effectiveRetrievalRoutes());
    }

    @Test
    void retrievalOnlyIsExplicitOptIn() {
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                0D, RetrievalMode.GRAPH, false, true, true);

        assertTrue(request.effectiveRetrievalOnly());
        assertTrue(request.effectiveIncludeQueryEmbedding());
    }

    @Test
    void explicitRouteSetTakesPriorityOverLegacyMode() {
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                0D, RetrievalMode.HYBRID, false, true, true,
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.BM25));

        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.BM25),
                request.effectiveRetrievalRoutes());
        assertEquals(RetrievalRoute.BM25, RetrievalRoute.fromJson("Bm25"));
        assertEquals("bm25", RetrievalRoute.BM25.toJson());
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalRoute.fromJson("title-lookup"));
        assertThrows(IllegalArgumentException.class, () -> RetrievalRoute.fromJson(" "));
    }

    @Test
    void intentRoutingDefaultsToFalseWhenAbsent() {
        // 旧 9 参构造器：intentRouting 委托传 null，旧行为不变
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                0D, null, null, null, null, null);
        assertNull(request.intentRouting());
        assertFalse(request.effectiveIntentRouting());
    }

    @Test
    void legacyConstructorWithoutRoutesKeepsIntentRoutingNull() {
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                null, null, null, null, null);
        assertNull(request.intentRouting());
        assertFalse(request.effectiveIntentRouting());
    }

    @Test
    void canonicalConstructorPassesIntentRoutingThrough() {
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                0D, null, null, null, null, null, Boolean.TRUE);
        assertEquals(Boolean.TRUE, request.intentRouting());
        assertTrue(request.effectiveIntentRouting());

        RagEvaluationAskReq explicitFalse = new RagEvaluationAskReq("q1", "question", 1L,
                0D, null, null, null, null, null, Boolean.FALSE);
        assertEquals(Boolean.FALSE, explicitFalse.intentRouting());
        assertFalse(explicitFalse.effectiveIntentRouting());
    }

    @Test
    void intentRoutingKeepsPureRouteDefaults() {
        // DTO 纯数据语义：intentRouting=true 不改变路由缺省（仍回落 HYBRID 的 V+G），
        // 与 retrievalMode/retrievalRoutes 的互斥裁决在服务层完成
        RagEvaluationAskReq request = new RagEvaluationAskReq("q1", "question", 1L,
                0D, null, null, null, null, null, Boolean.TRUE);
        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH),
                request.effectiveRetrievalRoutes());
    }
}
