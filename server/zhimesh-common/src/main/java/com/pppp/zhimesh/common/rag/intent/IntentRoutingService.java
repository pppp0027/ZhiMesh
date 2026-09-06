package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Fail-safe application facade used by chat entry points. */
@Slf4j
@Service
public class IntentRoutingService {
    private final LinearRoutingIntentRecognizer linearRoutingRecognizer;
    private final PrototypeRouteIntentRecognizer prototypeRouteRecognizer;
    private final IntentRoutingPolicy policy;
    private final ZhiMeshProperties properties;

    public IntentRoutingService(LinearRoutingIntentRecognizer linearRoutingRecognizer,
                                PrototypeRouteIntentRecognizer prototypeRouteRecognizer,
                                IntentRoutingPolicy policy,
                                ZhiMeshProperties properties) {
        this.linearRoutingRecognizer = linearRoutingRecognizer;
        this.prototypeRouteRecognizer = prototypeRouteRecognizer;
        this.policy = policy;
        this.properties = properties;
    }

    public RetrievalPlan route(String query, Embedding queryEmbedding, RetrievalMode explicitMode) {
        Set<KnowledgeSourceType> sources = Set.of(KnowledgeSourceType.DOCUMENT_KB);
        // This convenience overload has no knowledge-base scope and therefore
        // cannot prove BM25 readiness. Keep the established V+G baseline here;
        // scoped callers must use the overload below and explicitly pass BM25
        // only after their readiness check.
        return route(query, queryEmbedding, explicitMode, sources,
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH));
    }

    /**
     * Request-scoped capability entry point. Callers should pass BM25 only when
     * every knowledge base in the authorized scope has a ready lexical index.
     */
    public RetrievalPlan route(String query, Embedding queryEmbedding, RetrievalMode explicitMode,
                               Set<KnowledgeSourceType> authorizedSources,
                               Set<RetrievalRoute> availableRoutes) {
        Set<KnowledgeSourceType> sources = authorizedSources == null
                ? Set.of(KnowledgeSourceType.DOCUMENT_KB) : Set.copyOf(authorizedSources);
        IntentRoutingContext context = new IntentRoutingContext(query, queryEmbedding, explicitMode,
                sources, availableRoutes, properties.getEmbeddingModel());
        return route(context);
    }

    public RetrievalPlan route(IntentRoutingContext context) {
        if (context.explicitMode() != null) {
            return policy.plan(IntentDecision.uncertain(Set.of(), "explicit", "recognition bypassed"), context);
        }
        if (!properties.getIntentRouting().isEnabled()) {
            RetrievalSelection baseline = policy.baseline(context);
            return new RetrievalPlan(IntentDecision.uncertain(Set.of(), "disabled", "intent routing is disabled"),
                    baseline, baseline, false, "intent routing is disabled");
        }
        try {
            // The current production path uses explainable rules plus route
            // prototypes. The trained three-head classifier remains available
            // behind an explicit switch for a later model release.
            IntentRecognizer recognizer = properties.getIntentRouting().isClassifierEnabled()
                    ? linearRoutingRecognizer : prototypeRouteRecognizer;
            IntentDecision decision = recognizer.recognize(context);
            RetrievalPlan plan = policy.plan(decision, context);
            log.info("Intent routing: intent={}, confidence={}, margin={}, routes={}, fallback={}, reason={}",
                    decision.intent(), decision.confidence(), decision.margin(), plan.proposed().routes(),
                    plan.fallback(), plan.reason());
            return plan;
        } catch (RuntimeException exception) {
            log.warn("Intent recognition failed; keeping request baseline retrieval: {}", exception.getMessage());
            IntentDecision fallback = IntentDecision.uncertain(Set.of(), "fallback",
                    exception.getClass().getSimpleName());
            RetrievalSelection baseline = policy.baseline(context);
            return new RetrievalPlan(fallback, baseline, baseline, true,
                    "recognition failure; using request baseline fallback");
        }
    }

    public RetrievalMode effectiveLegacyMode(RetrievalPlan plan) {
        if (plan.effective().routes().isEmpty()) return null;
        return LegacyRetrievalModeAdapter.toMode(plan.effective().routes());
    }

    public Set<RetrievalRoute> effectiveRoutes(RetrievalPlan plan) {
        return plan == null || plan.effective() == null
                ? Set.of() : Set.copyOf(plan.effective().routes());
    }
}
