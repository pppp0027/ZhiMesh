package com.pppp.zhimesh.common.rag.intent;

import dev.langchain4j.rag.content.retriever.ContentRetriever;

/** A retriever paired with explicit provenance; no implementation-type inspection is needed. */
public record RoutedRetriever(RetrievalRouteKey key, ContentRetriever delegate) {
    public RoutedRetriever {
        if (key == null || delegate == null) {
            throw new IllegalArgumentException("key and delegate are required");
        }
    }
}
