package com.pppp.zhimesh.common.rag.intent;

import org.apache.commons.lang3.StringUtils;

/** Stable descriptor for one source instance and retrieval method. */
public record RetrievalRouteKey(KnowledgeSourceType sourceType,
                                RetrievalRoute route,
                                String sourceInstanceId) {
    public RetrievalRouteKey {
        if (sourceType == null || route == null) {
            throw new IllegalArgumentException("sourceType and route are required");
        }
        sourceInstanceId = StringUtils.trimToNull(sourceInstanceId);
    }

    public String routeName() {
        return route.name().toLowerCase(java.util.Locale.ROOT);
    }
}
