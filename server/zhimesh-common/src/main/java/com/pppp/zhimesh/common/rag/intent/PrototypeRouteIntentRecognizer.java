package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Rules plus route-plan prototypes; no supervised training is required. */
@Component
public class PrototypeRouteIntentRecognizer implements IntentRecognizer {
    private static final int TOP_K = 5;
    private final RuleIntentRecognizer ruleRecognizer;
    private final RetrievalRoutePrototypeCatalog catalog;
    private final ZhiMeshProperties properties;

    public PrototypeRouteIntentRecognizer(RuleIntentRecognizer ruleRecognizer,
                                          RetrievalRoutePrototypeCatalog catalog,
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
            return IntentDecision.uncertain(ruleDecision.signals(), "route-prototype",
                    "query embedding is unavailable");
        }
        RetrievalRoutePrototypeCatalog.EmbeddedCatalog embeddedCatalog = catalog.get();
        float[] queryVector = context.queryEmbedding().vector();
        if (queryVector == null || queryVector.length != embeddedCatalog.dimension()) {
            throw new IllegalArgumentException("Query embedding dimension does not match route prototypes");
        }

        EnumMap<RetrievalRouteProfile, Double> scores = new EnumMap<>(RetrievalRouteProfile.class);
        embeddedCatalog.vectors().forEach((profile, vectors) ->
                scores.put(profile, PrototypeIntentRecognizer.topKMean(queryVector, vectors, TOP_K)));
        List<Map.Entry<RetrievalRouteProfile, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<RetrievalRouteProfile, Double>comparingByValue(Comparator.reverseOrder()));
        if (ranked.isEmpty()) return IntentDecision.uncertain(ruleDecision.signals(), "route-prototype", "catalog is empty");

        double topScore = ranked.get(0).getValue();
        double secondScore = ranked.size() > 1 ? ranked.get(1).getValue() : -1D;
        double margin = topScore - secondScore;
        if (topScore < properties.getIntentRouting().getMinTopScore()
                || margin < properties.getIntentRouting().getMinScoreMargin()) {
            return new IntentDecision(QueryIntent.UNCERTAIN, topScore, margin,
                    ruleDecision.signals(), ruleDecision.sourceHints(),
                    "route-prototype:" + embeddedCatalog.version(),
                    "score or margin below safety threshold", capabilityScores(scores), java.util.Set.of());
        }

        RetrievalRouteProfile profile = ranked.get(0).getKey();
        QueryIntent intent = profile == RetrievalRouteProfile.VECTOR_GRAPH
                || profile == RetrievalRouteProfile.VECTOR_GRAPH_BM25
                ? QueryIntent.RELATIONSHIP : QueryIntent.KNOWLEDGE_LOOKUP;
        return new IntentDecision(intent, topScore, margin, ruleDecision.signals(),
                ruleDecision.sourceHints(), "route-prototype:" + embeddedCatalog.version(),
                "route prototype threshold passed", capabilityScores(scores), profile.capabilities());
    }

    private static Map<RoutingCapability, Double> capabilityScores(
            Map<RetrievalRouteProfile, Double> profileScores) {
        EnumMap<RoutingCapability, Double> scores = new EnumMap<>(RoutingCapability.class);
        scores.put(RoutingCapability.VECTOR_SUFFICIENT,
                profileScores.getOrDefault(RetrievalRouteProfile.VECTOR_ONLY, 0D));
        scores.put(RoutingCapability.GRAPH_REQUIRED, Math.max(
                profileScores.getOrDefault(RetrievalRouteProfile.VECTOR_GRAPH, 0D),
                profileScores.getOrDefault(RetrievalRouteProfile.VECTOR_GRAPH_BM25, 0D)));
        scores.put(RoutingCapability.BM25_REQUIRED, Math.max(
                profileScores.getOrDefault(RetrievalRouteProfile.VECTOR_BM25, 0D),
                profileScores.getOrDefault(RetrievalRouteProfile.VECTOR_GRAPH_BM25, 0D)));
        return Map.copyOf(scores);
    }
}
