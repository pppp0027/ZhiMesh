package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;

import java.util.List;
import java.util.Set;

/** Request-level gate that can reject every retrieval candidate. */
final class RetrievalRelevanceGate {

    private RetrievalRelevanceGate() {
    }

    /**
     * CPU-only gate before the remote cross-encoder call. It rejects only a
     * single-route, low-score vector candidate with no informative lexical
     * overlap; graph and corroborated hybrid evidence are preserved.
     */
    static List<RetrievedCandidate> preFilter(String rawQuery, List<RetrievedCandidate> candidates,
                                               ZhiMeshProperties.Retrieval properties) {
        if (candidates.isEmpty() || !properties.isPreRerankGateEnabled()) return candidates;
        Set<String> terms = informativeTerms(rawQuery);
        if (terms.isEmpty()) return candidates;
        double vectorFloor = Math.max(0D, Math.min(1D, properties.getPreRerankVectorScoreFloor()));
        return candidates.stream().filter(candidate -> {
            Double vectorScore = candidate.vectorScore();
            if (vectorScore == null || vectorScore >= vectorFloor
                    || candidate.routeRanks().size() > 1) {
                return true;
            }
            String evidence = candidate.text().toLowerCase(java.util.Locale.ROOT);
            return terms.stream().anyMatch(evidence::contains);
        }).toList();
    }

    static List<RetrievedCandidate> filter(String rawQuery, List<RetrievedCandidate> ranked,
                                           boolean rerankSuccessful, boolean rerankerConfigured,
                                           ZhiMeshProperties.Retrieval properties) {
        if (ranked.isEmpty()) return ranked;
        if (rerankSuccessful) {
            double floor = Math.max(0D, properties.getRerankAbsoluteScoreThreshold());
            return ranked.stream()
                    .filter(candidate -> candidate.rerankScore() != null && candidate.rerankScore() >= floor)
                    .toList();
        }
        if (rerankerConfigured && properties.isRelevanceGateFailOpen()) {
            return ranked;
        }
        Set<String> terms = informativeTerms(rawQuery);
        double highConfidence = Math.max(0D, properties.getFallbackHighConfidenceVectorScore());
        return ranked.stream().filter(candidate -> {
            if (candidate.vectorScore() != null && candidate.vectorScore() >= highConfidence) return true;
            String evidence = candidate.text().toLowerCase(java.util.Locale.ROOT);
            return terms.stream().anyMatch(evidence::contains);
        }).toList();
    }

    static Set<String> informativeTerms(String rawQuery) {
        return QueryInformationAnalyzer.informativeTerms(rawQuery);
    }
}
