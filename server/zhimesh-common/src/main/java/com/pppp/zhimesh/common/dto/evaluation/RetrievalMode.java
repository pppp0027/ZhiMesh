package com.pppp.zhimesh.common.dto.evaluation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Retrieval routes enabled for an isolated RAG evaluation request. */
public enum RetrievalMode {
    VECTOR,
    GRAPH,
    HYBRID;

    @JsonCreator
    public static RetrievalMode fromJson(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "retrievalMode must be one of: vector, graph, hybrid", exception);
        }
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
