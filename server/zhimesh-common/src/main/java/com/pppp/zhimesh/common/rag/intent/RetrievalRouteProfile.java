package com.pppp.zhimesh.common.rag.intent;

import java.util.EnumSet;
import java.util.Set;

/** Fixed route plans used by the no-training prototype router. */
public enum RetrievalRouteProfile {
    VECTOR_ONLY(RoutingCapability.VECTOR_SUFFICIENT),
    VECTOR_GRAPH(RoutingCapability.GRAPH_REQUIRED),
    VECTOR_BM25(RoutingCapability.BM25_REQUIRED),
    VECTOR_GRAPH_BM25(RoutingCapability.GRAPH_REQUIRED, RoutingCapability.BM25_REQUIRED);

    private final Set<RoutingCapability> capabilities;

    RetrievalRouteProfile(RoutingCapability... capabilities) {
        this.capabilities = capabilities.length == 0
                ? Set.of() : Set.copyOf(EnumSet.of(capabilities[0], capabilities));
    }

    public Set<RoutingCapability> capabilities() {
        return capabilities;
    }
}
