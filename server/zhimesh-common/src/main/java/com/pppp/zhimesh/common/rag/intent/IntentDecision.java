package com.pppp.zhimesh.common.rag.intent;

import java.util.Set;
import java.util.Map;

/** Explainable recognition output. Scores are cosine similarities for prototype recognition. */
public record IntentDecision(QueryIntent intent,
                             double confidence,
                             double margin,
                             Set<IntentSignal> signals,
                             Set<KnowledgeSourceType> sourceHints,
                             String recognizer,
                             String reason,
                             Map<RoutingCapability, Double> routeScores,
                             Set<RoutingCapability> activatedCapabilities) {
    public IntentDecision {
        intent = intent == null ? QueryIntent.UNCERTAIN : intent;
        signals = signals == null ? Set.of() : Set.copyOf(signals);
        sourceHints = sourceHints == null ? Set.of() : Set.copyOf(sourceHints);
        recognizer = recognizer == null ? "unknown" : recognizer;
        reason = reason == null ? "" : reason;
        routeScores = routeScores == null ? Map.of() : Map.copyOf(routeScores);
        activatedCapabilities = activatedCapabilities == null
                ? Set.of() : Set.copyOf(activatedCapabilities);
    }

    public IntentDecision(QueryIntent intent, double confidence, double margin,
                          Set<IntentSignal> signals, Set<KnowledgeSourceType> sourceHints,
                          String recognizer, String reason) {
        this(intent, confidence, margin, signals, sourceHints, recognizer, reason,
                Map.of(), Set.of());
    }

    public static IntentDecision uncertain(Set<IntentSignal> signals, String recognizer, String reason) {
        return new IntentDecision(QueryIntent.UNCERTAIN, 0D, 0D, signals,
                Set.of(), recognizer, reason);
    }
}
