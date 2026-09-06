package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import org.junit.jupiter.api.Test;

import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompositeRagModeTest {

    @Test
    void vectorEnablesOnlyVectorRoute() {
        assertTrue(CompositeRag.usesVector(RetrievalMode.VECTOR));
        assertFalse(CompositeRag.usesGraph(RetrievalMode.VECTOR));
    }

    @Test
    void graphEnablesOnlyGraphRoute() {
        assertFalse(CompositeRag.usesVector(RetrievalMode.GRAPH));
        assertTrue(CompositeRag.usesGraph(RetrievalMode.GRAPH));
    }

    @Test
    void hybridEnablesBothRoutes() {
        assertTrue(CompositeRag.usesVector(RetrievalMode.HYBRID));
        assertTrue(CompositeRag.usesGraph(RetrievalMode.HYBRID));
    }

    @Test
    void explicitRouteSetSupportsBm25Combinations() {
        RetrieverCreateParam vectorBm25 = RetrieverCreateParam.builder()
                .retrievalMode(RetrievalMode.GRAPH)
                .retrievalRoutes(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.BM25))
                .build();

        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.BM25),
                CompositeRag.effectiveRoutes(vectorBm25));
        assertTrue(CompositeRag.usesBm25(CompositeRag.effectiveRoutes(vectorBm25)));

        RetrieverCreateParam legacy = RetrieverCreateParam.builder()
                .retrievalMode(RetrievalMode.HYBRID)
                .build();
        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH),
                CompositeRag.effectiveRoutes(legacy));
    }
}
