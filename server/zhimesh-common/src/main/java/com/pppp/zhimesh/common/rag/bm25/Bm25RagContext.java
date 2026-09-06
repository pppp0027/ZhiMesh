package com.pppp.zhimesh.common.rag.bm25;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry mirroring EmbeddingRagContext and GraphRagContext. */
public final class Bm25RagContext {

    private static final Map<String, Bm25Rag> NAME_TO_RAG = new ConcurrentHashMap<>();

    private Bm25RagContext() {
    }

    public static Bm25Rag get(String name) {
        return NAME_TO_RAG.get(name);
    }

    public static void add(Bm25Rag rag) {
        if (rag == null) {
            throw new IllegalArgumentException("rag is required");
        }
        NAME_TO_RAG.put(rag.getName(), rag);
    }

    public static void remove(String name) {
        if (name != null) {
            NAME_TO_RAG.remove(name);
        }
    }
}
