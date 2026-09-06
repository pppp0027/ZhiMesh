package com.pppp.zhimesh.common.rag.intent;

import dev.langchain4j.data.embedding.Embedding;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Multi-label linear routing head over the frozen request embedding. */
@Component
public class LinearRoutingIntentRecognizer implements IntentRecognizer {
    private final RuleIntentRecognizer ruleRecognizer;
    private final RoutingClassifierModelCatalog modelCatalog;

    public LinearRoutingIntentRecognizer(RuleIntentRecognizer ruleRecognizer,
                                         RoutingClassifierModelCatalog modelCatalog) {
        this.ruleRecognizer = ruleRecognizer;
        this.modelCatalog = modelCatalog;
    }

    @Override
    public IntentDecision recognize(IntentRoutingContext context) {
        IntentDecision ruleDecision = ruleRecognizer.recognize(context);
        if (ruleDecision.intent() == QueryIntent.NO_RAG
                || ruleDecision.signals().contains(IntentSignal.AMBIGUOUS_CONTEXT)) {
            return ruleDecision;
        }
        Embedding embedding = context.queryEmbedding();
        if (embedding == null) {
            return new IntentDecision(QueryIntent.UNCERTAIN, 0D, 0D,
                    ruleDecision.signals(), ruleDecision.sourceHints(), "linear-router",
                    "query embedding is unavailable");
        }
        RoutingClassifierModelCatalog.RoutingClassifierModel model = modelCatalog.get();
        double[] features = l2Normalize(embedding.vector(), model.embeddingDimension());
        EnumMap<RoutingCapability, Double> scores = new EnumMap<>(RoutingCapability.class);
        EnumSet<RoutingCapability> activated = EnumSet.noneOf(RoutingCapability.class);
        model.heads().forEach((capability, head) -> {
            if (!head.enabled()) return;
            double probability = sigmoid(dot(head.weights(), features) + head.bias());
            scores.put(capability, probability);
            if (probability >= head.threshold()) activated.add(capability);
        });

        boolean vectorSufficient = activated.contains(RoutingCapability.VECTOR_SUFFICIENT);
        boolean graphRequired = activated.contains(RoutingCapability.GRAPH_REQUIRED);
        QueryIntent intent = graphRequired ? QueryIntent.RELATIONSHIP
                : vectorSufficient ? QueryIntent.KNOWLEDGE_LOOKUP : QueryIntent.UNCERTAIN;
        double vectorScore = scores.getOrDefault(RoutingCapability.VECTOR_SUFFICIENT, 0D);
        double graphScore = scores.getOrDefault(RoutingCapability.GRAPH_REQUIRED, 0D);
        double confidence = intent == QueryIntent.RELATIONSHIP ? graphScore
                : intent == QueryIntent.KNOWLEDGE_LOOKUP ? vectorScore : Math.max(vectorScore, graphScore);
        return new IntentDecision(intent, confidence, Math.abs(vectorScore - graphScore),
                ruleDecision.signals(), ruleDecision.sourceHints(),
                "linear-router:" + model.modelVersion(),
                intent == QueryIntent.UNCERTAIN ? "routing heads abstained" : "routing head threshold passed",
                Map.copyOf(scores), Set.copyOf(activated));
    }

    static double[] l2Normalize(float[] vector, int expectedDimension) {
        if (vector == null || vector.length != expectedDimension) {
            throw new IllegalArgumentException("Query embedding dimension does not match routing classifier");
        }
        double normSquared = 0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Query embedding contains non-finite value");
            normSquared += value * value;
        }
        if (normSquared == 0D) throw new IllegalArgumentException("Query embedding is a zero vector");
        double norm = Math.sqrt(normSquared);
        double[] result = new double[vector.length];
        for (int index = 0; index < vector.length; index++) result[index] = vector[index] / norm;
        return result;
    }

    static double dot(double[] weights, double[] features) {
        if (weights.length != features.length) throw new IllegalArgumentException("Feature dimensions differ");
        double result = 0D;
        for (int index = 0; index < weights.length; index++) result += weights[index] * features[index];
        return result;
    }

    static double sigmoid(double value) {
        if (value >= 0D) return 1D / (1D + Math.exp(-value));
        double exponential = Math.exp(value);
        return exponential / (1D + exponential);
    }
}
