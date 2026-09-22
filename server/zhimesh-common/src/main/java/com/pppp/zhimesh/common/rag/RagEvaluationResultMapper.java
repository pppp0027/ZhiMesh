package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationCandidateResp;
import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationRouteResp;
import com.pppp.zhimesh.common.rag.intent.IntentDecision;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopeDecision;
import com.pppp.zhimesh.common.rag.intent.RetrievalPlan;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.rag.content.Content;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts retrieval internals to the stable, JSON-friendly evaluation contract. */
public final class RagEvaluationResultMapper {

    private RagEvaluationResultMapper() {
    }

    public static List<RagEvaluationCandidateResp> candidates(List<Content> candidates,
                                                               List<Content> selected,
                                                               Map<String, String> documentNames,
                                                               TokenCountEstimator estimator) {
        Set<String> selectedFingerprints = selected.stream()
                .map(RagEvaluationResultMapper::fingerprint)
                .collect(java.util.stream.Collectors.toSet());
        return candidates.stream().map(content -> {
            Map<String, Object> metadata = content.textSegment().metadata().toMap();
            List<String> documentIds = csv(metadata.get("source_document_ids"));
            List<String> names = documentIds.stream()
                    .map(documentNames::get)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            return RagEvaluationCandidateResp.builder()
                    .content(content.textSegment().text())
                    .contentType(string(metadata.get("content_type"), "original_segment"))
                    .routes(csv(metadata.get("retrieval_routes")))
                    .sourceDocumentIds(documentIds)
                    .sourceDocumentNames(names)
                    .sourceSegmentIds(csv(metadata.get("source_segment_ids")))
                    .graphElementIds(csv(metadata.get("graph_element_ids")))
                    .vectorScore(decimal(metadata.get("vector_score")))
                    .bm25Score(decimal(metadata.get("bm25_score")))
                    .vectorRank(integer(metadata.get("vector_rank")))
                    .graphRank(integer(metadata.get("graph_rank")))
                    .bm25Rank(integer(metadata.get("bm25_rank")))
                    .routeRanks(routeRanks(metadata))
                    .rrfScore(decimal(metadata.get("rrf_score")))
                    .rerankScore(decimal(metadata.get("rerank_score")))
                    .tokenCount(estimator.estimateTokenCountInText(content.textSegment().text()))
                    .selected(selectedFingerprints.contains(fingerprint(content)))
                    .build();
        }).toList();
    }

    public static List<RagEvaluationRouteResp> routes(List<RetrievalRouteResult> results) {
        return results.stream().map(result -> RagEvaluationRouteResp.builder()
                .route(result.route())
                .status(result.status().name().toLowerCase(java.util.Locale.ROOT))
                .durationMs(result.durationMs())
                .candidateCount(result.contents().size())
                .errorType(result.errorType())
                .errorMessage(result.errorMessage())
                .build()).toList();
    }

    public static Map<String, Object> rerank(RerankResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("configured", result != null);
        output.put("successful", result != null && result.successful());
        output.put("circuitOpen", result != null && result.circuitOpen());
        output.put("durationMs", result == null ? 0L : result.durationMs());
        output.put("failureReason", result == null ? null : result.failureReason());
        return output;
    }

    /**
     * Echoes the intent-routing decision for evaluation requests. Mirrors the
     * production INFO log fields; a null plan (scope preflight skipped routing)
     * keeps the decision keys null and an empty effective route list, matching
     * the NO_RAG/no-routing outcome.
     */
    public static Map<String, Object> intent(RetrievalPlan plan,
                                             KnowledgeScopeDecision scopeDecision,
                                             Set<RetrievalRoute> availableRoutes,
                                             boolean routingEnabled) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("routingEnabled", routingEnabled);
        Map<String, Object> scopePreflight = new LinkedHashMap<>();
        scopePreflight.put("status", scopeDecision == null || scopeDecision.status() == null
                ? null : scopeDecision.status().name().toLowerCase(Locale.ROOT));
        scopePreflight.put("score", scopeDecision == null ? null : scopeDecision.maxVectorScore());
        scopePreflight.put("skip", scopeDecision != null && scopeDecision.skipKnowledgeBaseRouting());
        scopePreflight.put("durationMs", scopeDecision == null ? 0L : scopeDecision.durationMs());
        scopePreflight.put("reason", scopeDecision == null ? null : scopeDecision.reason());
        output.put("scopePreflight", scopePreflight);
        output.put("availableRoutes", routeNames(availableRoutes));
        IntentDecision decision = plan == null ? null : plan.decision();
        output.put("intent", decision == null ? null
                : decision.intent().name().toLowerCase(Locale.ROOT));
        output.put("recognizer", decision == null ? null : decision.recognizer());
        output.put("confidence", decision == null ? null : decision.confidence());
        output.put("margin", decision == null ? null : decision.margin());
        output.put("proposedRoutes", plan == null || plan.proposed() == null
                ? null : routeNames(plan.proposed().routes()));
        output.put("effectiveRoutes", plan == null || plan.effective() == null
                ? List.of() : routeNames(plan.effective().routes()));
        output.put("fallback", plan == null ? null : plan.fallback());
        output.put("reason", plan == null ? null : plan.reason());
        output.put("recognitionReason", decision == null ? null : decision.reason());
        return output;
    }

    private static List<String> routeNames(Set<RetrievalRoute> routes) {
        if (routes == null || routes.isEmpty()) return List.of();
        return routes.stream()
                .sorted(Comparator.comparingInt(RetrievalRoute::ordinal))
                .map(RetrievalRoute::toJson)
                .toList();
    }

    public static List<String> documentIds(List<Content> contents) {
        return uniqueMetadata(contents, "source_document_ids");
    }

    public static List<String> segmentIds(List<Content> contents) {
        return uniqueMetadata(contents, "source_segment_ids");
    }

    private static List<String> uniqueMetadata(List<Content> contents, String key) {
        Set<String> values = new LinkedHashSet<>();
        contents.forEach(content -> values.addAll(csv(content.textSegment().metadata().toMap().get(key))));
        return List.copyOf(values);
    }

    private static String fingerprint(Content content) {
        Map<String, Object> metadata = content.textSegment().metadata().toMap();
        return content.textSegment().text() + '\u0000'
                + string(metadata.get("source_document_ids"), "") + '\u0000'
                + string(metadata.get("source_segment_ids"), "");
    }

    private static List<String> csv(Object value) {
        if (value == null) return List.of();
        return Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static Double decimal(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Map<String, Integer> routeRanks(Map<String, Object> metadata) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Object value = metadata.get("route_ranks");
        if (value instanceof Map<?, ?> ranks) {
            ranks.forEach((route, rank) -> {
                Integer parsed = integer(rank);
                if (route != null && parsed != null) result.put(String.valueOf(route), parsed);
            });
        } else if (value != null) {
            for (String entry : String.valueOf(value).split(",")) {
                int separator = entry.indexOf('=');
                if (separator <= 0) continue;
                Integer rank = integer(entry.substring(separator + 1).trim());
                if (rank != null) result.put(entry.substring(0, separator).trim(), rank);
            }
        }
        Integer vectorRank = integer(metadata.get("vector_rank"));
        Integer graphRank = integer(metadata.get("graph_rank"));
        Integer bm25Rank = integer(metadata.get("bm25_rank"));
        if (vectorRank != null) result.putIfAbsent("vector", vectorRank);
        if (graphRank != null) result.putIfAbsent("graph", graphRank);
        if (bm25Rank != null) result.putIfAbsent("bm25", bm25Rank);
        return Map.copyOf(result);
    }
}
