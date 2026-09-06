package com.pppp.zhimesh.common.rag.intent;

/** Independent outputs of the multi-label retrieval router. */
public enum RoutingCapability {
    VECTOR_SUFFICIENT,
    GRAPH_REQUIRED,
    BM25_REQUIRED
}
