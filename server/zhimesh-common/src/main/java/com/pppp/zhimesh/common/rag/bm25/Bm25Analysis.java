package com.pppp.zhimesh.common.rag.bm25;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable lexical analysis output used by both indexing and querying. */
public record Bm25Analysis(Map<String, Integer> termFrequencies, int documentLength) {

    public Bm25Analysis {
        termFrequencies = termFrequencies == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(termFrequencies));
        documentLength = Math.max(0, documentLength);
    }

    public List<String> distinctTerms(int limit) {
        if (limit <= 0 || termFrequencies.isEmpty()) {
            return List.of();
        }
        return termFrequencies.keySet().stream().limit(limit).toList();
    }
}
