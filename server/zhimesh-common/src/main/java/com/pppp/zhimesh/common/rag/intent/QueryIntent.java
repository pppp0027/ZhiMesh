package com.pppp.zhimesh.common.rag.intent;

/** High-level intent used to propose a safe retrieval plan. */
public enum QueryIntent {
    NO_RAG,
    KNOWLEDGE_LOOKUP,
    RELATIONSHIP,
    UNCERTAIN
}
