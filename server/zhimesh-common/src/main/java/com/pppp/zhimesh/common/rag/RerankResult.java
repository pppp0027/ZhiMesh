package com.pppp.zhimesh.common.rag;

import java.util.List;

public record RerankResult(
        boolean successful,
        boolean circuitOpen,
        List<RerankScore> scores,
        long durationMs,
        String failureReason
) {
    public record RerankScore(int index, double score) {}

    public static RerankResult success(List<RerankScore> scores, long durationMs) {
        return new RerankResult(true, false, List.copyOf(scores), durationMs, null);
    }

    public static RerankResult failure(long durationMs, String reason) {
        return new RerankResult(false, false, List.of(), durationMs, reason);
    }

    public static RerankResult openCircuitFallback() {
        return new RerankResult(false, true, List.of(), 0L, "circuit_open");
    }
}
