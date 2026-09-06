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
}
