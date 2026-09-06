package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;

import java.util.EnumSet;
import java.util.Set;

/** Strict compatibility adapter for the existing vector/graph-only execution engine. */
public final class LegacyRetrievalModeAdapter {
    private LegacyRetrievalModeAdapter() {
    }

    public static Set<RetrievalRoute> toRoutes(RetrievalMode mode) {
        if (mode == null || mode == RetrievalMode.HYBRID) {
            return EnumSet.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);
        }
        return mode == RetrievalMode.VECTOR
                ? EnumSet.of(RetrievalRoute.VECTOR)
                : EnumSet.of(RetrievalRoute.GRAPH);
    }

    public static RetrievalMode toMode(Set<RetrievalRoute> routes) {
        Set<RetrievalRoute> safe = routes == null ? Set.of() : routes;
        if (safe.stream().anyMatch(route -> route != RetrievalRoute.VECTOR && route != RetrievalRoute.GRAPH)) {
        throw new IllegalArgumentException("BM25 cannot be represented by legacy RetrievalMode");
        }
        boolean vector = safe.contains(RetrievalRoute.VECTOR);
        boolean graph = safe.contains(RetrievalRoute.GRAPH);
        if (vector && graph) return RetrievalMode.HYBRID;
        if (vector) return RetrievalMode.VECTOR;
        if (graph) return RetrievalMode.GRAPH;
        throw new IllegalArgumentException("An empty route set cannot be represented by legacy RetrievalMode");
    }
}
