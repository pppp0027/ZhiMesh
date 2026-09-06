package com.pppp.zhimesh.common.vo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * Immutable query data shared by all vector retrieval routes in one request.
 * The embedding is request-scoped and must not be cached globally.
 */
public record RetrievalQueryContext(String text, Embedding embedding) {

    public static RetrievalQueryContext create(String text, EmbeddingModel embeddingModel) {
        return new RetrievalQueryContext(text, embeddingModel.embed(text).content());
    }
}
