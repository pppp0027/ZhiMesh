package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.util.LocalDateTimeUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.CREATE_TIME;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.IMPORTANCE;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.OCCURRED_AT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.TIME_PRECISION;

/** Adds bounded recency preference after episodic candidates pass relevance checks. */
final class EpisodicRecencyRanker {

    private static final Pattern RECENCY_QUERY = Pattern.compile(
            "(上次|上回|最近|刚才|刚刚|前一次|最新|前几天|昨天|last time|recently|latest|just now)",
            Pattern.CASE_INSENSITIVE);

    private EpisodicRecencyRanker() {
    }

    static List<RetrievedCandidate> rank(String query, List<RetrievedCandidate> candidates,
                                         double configuredWeight, double configuredHalfLifeDays) {
        return rank(query, candidates, configuredWeight, configuredHalfLifeDays,
                0D, LocalDateTime.now());
    }

    static List<RetrievedCandidate> rank(String query, List<RetrievedCandidate> candidates,
                                         double configuredWeight, double configuredHalfLifeDays,
                                         LocalDateTime now) {
        return rank(query, candidates, configuredWeight, configuredHalfLifeDays, 0D, now);
    }

    static List<RetrievedCandidate> rank(String query, List<RetrievedCandidate> candidates,
                                         double configuredWeight, double configuredHalfLifeDays,
                                         double configuredImportanceWeight, LocalDateTime now) {
        if (candidates.size() < 2 || query == null
                || !RECENCY_QUERY.matcher(query.toLowerCase(Locale.ROOT)).find()) {
            return candidates;
        }
        // Recency is intentionally capped at 50% so an old but strongly related
        // event cannot be displaced by a recent, only marginally related event.
        double weight = Math.max(0D, Math.min(0.5D, configuredWeight));
        double importanceWeight = Math.max(0D, Math.min(0.1D, configuredImportanceWeight));
        if (weight + importanceWeight > 0.5D) {
            importanceWeight = Math.max(0D, 0.5D - weight);
        }
        if (weight == 0D && importanceWeight == 0D) return candidates;
        double halfLifeDays = Math.max(1D, configuredHalfLifeDays);
        double finalImportanceWeight = importanceWeight;
        return candidates.stream()
                .sorted(Comparator.comparingDouble(candidate ->
                        -combinedScore(candidate, weight, finalImportanceWeight, halfLifeDays, now)))
                .toList();
    }

    private static double combinedScore(RetrievedCandidate candidate, double recencyWeight,
                                        double importanceWeight,
                                        double halfLifeDays, LocalDateTime now) {
        double relevance = candidate.rerankScore() != null
                ? candidate.rerankScore()
                : candidate.vectorScore() != null ? candidate.vectorScore() : 0D;
        return (1D - recencyWeight - importanceWeight) * clamp01(relevance)
                + recencyWeight * eventRecencyScore(candidate, halfLifeDays, now)
                + importanceWeight * importanceScore(candidate.metadataValue(IMPORTANCE));
    }

    private static double eventRecencyScore(RetrievedCandidate candidate,
                                            double halfLifeDays, LocalDateTime now) {
        Object occurredAt = candidate.metadataValue(OCCURRED_AT);
        if (occurredAt != null) {
            return recencyScore(occurredAt, halfLifeDays, now, 0.5D);
        }
        // New rows explicitly marked UNKNOWN are neutral rather than being
        // treated as recent merely because the user mentioned them today.
        if (candidate.metadataValue(TIME_PRECISION) != null) return 0.5D;
        // Legacy rows had only create_time. Retain a bounded compatibility signal
        // until they are backfilled, but never grant them full temporal confidence.
        return 0.5D * recencyScore(candidate.metadataValue(CREATE_TIME), halfLifeDays, now, 0D);
    }

    private static double recencyScore(Object rawTime, double halfLifeDays,
                                       LocalDateTime now, double invalidFallback) {
        if (rawTime == null) return invalidFallback;
        try {
            LocalDateTime createdAt = rawTime instanceof LocalDateTime dateTime
                    ? dateTime : LocalDateTimeUtil.parse(String.valueOf(rawTime));
            double ageDays = Math.max(0D,
                    Duration.between(createdAt, now).toMinutes() / (24D * 60D));
            return Math.pow(0.5D, ageDays / halfLifeDays);
        } catch (RuntimeException ignored) {
            return invalidFallback;
        }
    }

    private static double importanceScore(Object rawImportance) {
        if (rawImportance == null) return 0.5D;
        try {
            int value = Integer.parseInt(String.valueOf(rawImportance));
            return (Math.max(1, Math.min(5, value)) - 1D) / 4D;
        } catch (NumberFormatException ignored) {
            return 0.5D;
        }
    }

    private static double clamp01(double score) {
        return Math.max(0D, Math.min(1D, score));
    }
}
