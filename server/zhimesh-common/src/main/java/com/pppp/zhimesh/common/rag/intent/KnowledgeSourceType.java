package com.pppp.zhimesh.common.rag.intent;

/** Knowledge ownership/source. This is intentionally separate from retrieval method. */
public enum KnowledgeSourceType {
    DOCUMENT_KB,
    CHARACTER_MEMORY,
    EPISODIC_MEMORY,
    WEB
}
