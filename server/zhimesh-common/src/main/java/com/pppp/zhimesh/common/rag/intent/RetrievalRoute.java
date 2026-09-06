package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Retrieval method supported by a knowledge source. */
public enum RetrievalRoute {
    VECTOR,
    GRAPH,
    BM25;

    @JsonCreator
    public static RetrievalRoute fromJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("retrieval route must not be blank");
        }
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "retrieval route must be one of: vector, graph, bm25", exception);
        }
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
