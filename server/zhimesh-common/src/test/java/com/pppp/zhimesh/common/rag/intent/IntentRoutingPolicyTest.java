package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRoutingPolicyTest {
    @Test
    void recognizedVectorRouteIsExecutedDirectly() {
        RetrievalPlan plan = policy().plan(
                decision(QueryIntent.KNOWLEDGE_LOOKUP, Map.of(
                        RoutingCapability.VECTOR_SUFFICIENT, 0.96D),
                        Set.of(RoutingCapability.VECTOR_SUFFICIENT)),
                context(null, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH)));

        assertThat(plan.proposed().routes()).containsExactly(RetrievalRoute.VECTOR);
        assertThat(plan.effective().routes()).containsExactly(RetrievalRoute.VECTOR);
    }

    @Test
    void recognizedThreeRoutePlanIsExecutedDirectly() {
        RetrieverCapabilityRegistry registry = new RetrieverCapabilityRegistry();
        registry.register(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        RetrievalPlan plan = new IntentRoutingPolicy(registry).plan(
                decision(QueryIntent.RELATIONSHIP, Map.of(
                        RoutingCapability.GRAPH_REQUIRED, 0.96D,
                        RoutingCapability.BM25_REQUIRED, 0.91D),
                        Set.of(RoutingCapability.GRAPH_REQUIRED, RoutingCapability.BM25_REQUIRED)),
                context(null, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25)));

        assertThat(plan.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25);
        assertThat(plan.fallback()).isFalse();
    }

    @Test
    void classifierAbstentionUsesCurrentRequestBaseline() {
        RetrieverCapabilityRegistry registry = new RetrieverCapabilityRegistry();
        registry.register(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        IntentDecision decision = decision(QueryIntent.UNCERTAIN,
                Map.of(RoutingCapability.VECTOR_SUFFICIENT, 0.2D,
                        RoutingCapability.GRAPH_REQUIRED, 0.2D,
                        RoutingCapability.BM25_REQUIRED, 0.2D), Set.of());

        RetrievalPlan plan = new IntentRoutingPolicy(registry).plan(decision,
                context(null, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25)));

        assertThat(plan.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25);
        assertThat(plan.fallback()).isTrue();
    }

    @Test
    void ruleUncertaintyUsesCompleteRequestBaseline() {
        RetrieverCapabilityRegistry registry = new RetrieverCapabilityRegistry();
        registry.register(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        IntentDecision decision = new IntentDecision(QueryIntent.UNCERTAIN, 0D, 0D,
                Set.of(), Set.of(), "rule", "no decisive rule");

        RetrievalPlan plan = new IntentRoutingPolicy(registry).plan(decision,
                context(null, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25)));

        assertThat(plan.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25);
        assertThat(plan.fallback()).isTrue();
    }

    @Test
    void requiredRouteThatIsUnavailableFallsBackWithoutSilentlyDroppingIt() {
        IntentDecision decision = decision(QueryIntent.KNOWLEDGE_LOOKUP,
                Map.of(RoutingCapability.BM25_REQUIRED, 0.96D),
                Set.of(RoutingCapability.BM25_REQUIRED));

        RetrievalPlan plan = policy().plan(decision,
                context(null, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH)));

        assertThat(plan.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);
    }

    @Test
    void noRagIsExecutedWithoutAnAdditionalModeSwitch() {
        RetrievalPlan plan = policy().plan(
                new IntentDecision(QueryIntent.NO_RAG, 1D, 1D, Set.of(), Set.of(),
                        "rule", "test"), context(null));

        assertThat(plan.effective().routes()).isEmpty();
    }

    @Test
    void explicitModeHasPriorityOverClassifier() {
        RetrievalPlan plan = policy().plan(
                decision(QueryIntent.RELATIONSHIP, Map.of(
                        RoutingCapability.GRAPH_REQUIRED, 0.96D),
                        Set.of(RoutingCapability.GRAPH_REQUIRED)), context(RetrievalMode.VECTOR));

        assertThat(plan.effective().routes()).containsExactly(RetrievalRoute.VECTOR);
        assertThat(plan.reason()).contains("explicit");
    }

    private static IntentRoutingPolicy policy() {
        return new IntentRoutingPolicy(new RetrieverCapabilityRegistry());
    }

    private static IntentDecision decision(QueryIntent intent,
                                           Map<RoutingCapability, Double> scores,
                                           Set<RoutingCapability> activated) {
        return new IntentDecision(intent, 0.96D, 0.7D, Set.of(), Set.of(),
                "linear-router:test", "test", scores, activated);
    }

    private static IntentRoutingContext context(RetrievalMode mode) {
        return context(mode, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH));
    }

    private static IntentRoutingContext context(RetrievalMode mode, Set<RetrievalRoute> routes) {
        return new IntentRoutingContext("query", null, mode,
                Set.of(KnowledgeSourceType.DOCUMENT_KB), routes, "test");
    }
}
