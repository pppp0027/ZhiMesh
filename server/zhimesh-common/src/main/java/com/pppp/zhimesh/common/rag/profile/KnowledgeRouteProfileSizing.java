package com.pppp.zhimesh.common.rag.profile;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;

/**
 * Deterministic profile budget policy shared by profile builds and online
 * probes. The budget grows with the amount of active content but remains
 * globally bounded so a multi-KB chat request stays low latency.
 */
public final class KnowledgeRouteProfileSizing {

    private KnowledgeRouteProfileSizing() {
    }

    /**
     * Returns the adaptive target before an optional deployment-level cap.
     * Content units are the larger of active document and embedding counts.
     */
    public static int forContentUnits(long contentUnits) {
        long units = Math.max(0L, contentUnits);
        if (units <= 50L) return ZhiMeshConstant.CharacterConstant.MIN_KNOWLEDGE_SCOPE_PROFILE_VECTORS;
        if (units <= 300L) return 12;
        if (units <= 1_000L) return 16;
        if (units <= 5_000L) return 20;
        return ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_SCOPE_PROFILE_VECTORS;
    }

    /**
     * Applies the configured maximum without allowing it to exceed the
     * server-side hard limit or reduce a valid target below one vector.
     */
    public static int boundedLimit(long contentUnits, int configuredMaximum) {
        return Math.max(1, Math.min(forContentUnits(contentUnits),
                Math.min(ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_SCOPE_PROFILE_VECTORS,
                        configuredMaximum)));
    }

    public static long contentUnits(long itemCount, long embeddingCount) {
        return Math.max(Math.max(0L, itemCount), Math.max(0L, embeddingCount));
    }
}
