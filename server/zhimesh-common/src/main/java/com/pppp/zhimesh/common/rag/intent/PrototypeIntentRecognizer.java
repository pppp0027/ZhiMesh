package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Top-k prototype classifier using the request's already-computed query embedding. */
@Component
public class PrototypeIntentRecognizer implements IntentRecognizer {
    private static final int TOP_K = 3;
    private final RuleIntentRecognizer ruleRecognizer;
    private final IntentPrototypeCatalog catalog;
    private final ZhiMeshProperties properties;

    public PrototypeIntentRecognizer(RuleIntentRecognizer ruleRecognizer,
                                     IntentPrototypeCatalog catalog,
                                     ZhiMeshProperties properties) {
        this.ruleRecognizer = ruleRecognizer;
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override
    public IntentDecision recognize(IntentRoutingContext context) {
        IntentDecision ruleDecision = ruleRecognizer.recognize(context);
        if (ruleDecision.intent() != QueryIntent.UNCERTAIN
                || ruleDecision.signals().contains(IntentSignal.AMBIGUOUS_CONTEXT)) {
            return ruleDecision;
        }
        if (context.queryEmbedding() == null) {
            return new IntentDecision(QueryIntent.UNCERTAIN, 0D, 0D,
                    ruleDecision.signals(), ruleDecision.sourceHints(), "prototype", "query embedding is unavailable");
        }
        float[] queryVector = context.queryEmbedding().vector();
        IntentPrototypeCatalog.EmbeddedCatalog embeddedCatalog = catalog.get();
        validateQueryVector(queryVector, embeddedCatalog.dimension());

        EnumMap<QueryIntent, Double> scores = new EnumMap<>(QueryIntent.class);
        embeddedCatalog.vectors().forEach((intent, prototypes) ->
                scores.put(intent, topKMean(queryVector, prototypes, TOP_K)));
        List<Map.Entry<QueryIntent, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<QueryIntent, Double>comparingByValue(Comparator.reverseOrder()));
        if (ranked.isEmpty()) {
            return IntentDecision.uncertain(ruleDecision.signals(), "prototype", "prototype catalog is empty");
        }
        double topScore = ranked.get(0).getValue();
        double secondScore = ranked.size() > 1 ? ranked.get(1).getValue() : -1D;
        double margin = topScore - secondScore;
        QueryIntent intent = topScore >= properties.getIntentRouting().getMinTopScore()
                && margin >= properties.getIntentRouting().getMinScoreMargin()
                ? ranked.get(0).getKey() : QueryIntent.UNCERTAIN;
        return new IntentDecision(intent, topScore, margin, ruleDecision.signals(),
                ruleDecision.sourceHints(), "prototype:" + embeddedCatalog.version(),
                intent == QueryIntent.UNCERTAIN ? "score or margin below safety threshold" : "top-k prototype match");
    }

    static double topKMean(float[] query, List<float[]> prototypes, int topK) {
        if (prototypes == null || prototypes.isEmpty()) return -1D;
        return prototypes.stream()
                .map(vector -> cosine(query, vector))
                .sorted(Comparator.reverseOrder())
                .limit(Math.max(1, topK))
                .mapToDouble(Double::doubleValue)
                .average().orElse(-1D);
    }

    static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("Embedding vectors must be non-empty and have equal dimensions");
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            float leftValue = left[index];
            float rightValue = right[index];
            if (!Float.isFinite(leftValue) || !Float.isFinite(rightValue)) {
                throw new IllegalArgumentException("Embedding vector contains a non-finite value");
            }
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            throw new IllegalArgumentException("Embedding vector must not be a zero vector");
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static void validateQueryVector(float[] vector, int expectedDimension) {
        if (vector == null || vector.length != expectedDimension) {
            throw new IllegalArgumentException("Query embedding dimension does not match prototype catalog");
        }
        // cosine performs finite and zero-vector validation.
    }
}
