package com.pppp.zhimesh.common.rag.intent;

/** Explainable signals detected before retrieval routing. */
public enum IntentSignal {
    NO_RAG_PATTERN,
    RELATION_QUERY,
    EXACT_IDENTIFIER,
    AMBIGUOUS_CONTEXT
}
