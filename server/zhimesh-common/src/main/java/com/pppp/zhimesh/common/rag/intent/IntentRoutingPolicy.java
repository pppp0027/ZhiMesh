package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Converts a recognition result directly into the route executed by the request. */
@Component
public class IntentRoutingPolicy {
    private final RetrieverCapabilityRegistry capabilityRegistry;
    private final ZhiMeshProperties properties;

    public IntentRoutingPolicy(RetrieverCapabilityRegistry capabilityRegistry, ZhiMeshProperties properties) {
        this.capabilityRegistry = capabilityRegistry;
        this.properties = properties;
    }

    public RetrievalPlan plan(IntentDecision decision, IntentRoutingContext context) {
        if (context.explicitMode() != null) {
            RetrievalSelection explicit = selection(Set.of(KnowledgeSourceType.DOCUMENT_KB),
                    LegacyRetrievalModeAdapter.toRoutes(context.explicitMode()));
            return new RetrievalPlan(decision, explicit, explicit, false,
                    "explicit retrieval mode has priority");
        }

        RetrievalSelection baseline = baseline(context);
        RetrievalSelection selected = proposed(decision, context, baseline);
        boolean abstained = decision.intent() == QueryIntent.UNCERTAIN
                && decision.routeScores().isEmpty();
        // fallback 标志按"没有激活任何确定路由"判定，而不再要求与宽基线完全
        // 相等——窄回退（VECTOR-only）本来就不等于基线，但同样是兜底。
        // The fallback flag marks "no confident route activated"; it must not
        // require equality with the wide baseline, because the narrow fallback
        // (VECTOR-only) is itself a fallback that differs from the baseline.
        boolean fallback = decision.intent() != QueryIntent.NO_RAG
                && decision.activatedCapabilities().isEmpty()
                && (abstained || !decision.routeScores().isEmpty()
                        || selected.routes().equals(baseline.routes()));
        return new RetrievalPlan(decision, selected, selected, fallback,
                fallback
                        ? properties.getIntentRouting().isGraphOnUncertain()
                                ? "classifier abstained; using wide request baseline"
                                : "classifier abstained; using narrow vector fallback"
                        : "recognized route is executed directly");
    }

    private RetrievalSelection proposed(IntentDecision decision, IntentRoutingContext context,
                                        RetrievalSelection baseline) {
        EnumSet<KnowledgeSourceType> sources = EnumSet.noneOf(KnowledgeSourceType.class);
        if (!decision.sourceHints().isEmpty()) sources.addAll(decision.sourceHints());
        if (sources.isEmpty()) sources.add(KnowledgeSourceType.DOCUMENT_KB);
        sources.retainAll(context.authorizedSources());
        if (sources.isEmpty() && context.authorizedSources().contains(KnowledgeSourceType.DOCUMENT_KB)) {
            sources.add(KnowledgeSourceType.DOCUMENT_KB);
        }

        EnumSet<RetrievalRoute> routes;
        if (decision.intent() == QueryIntent.UNCERTAIN && decision.routeScores().isEmpty()) {
            // Rules/prototypes did not produce a reliable route. The narrow
            // fallback keeps only the cheapest safe route (VECTOR); the wide
            // legacy baseline stays available behind graph-on-uncertain=true.
            return uncertainFallback(decision, baseline);
        }
        if (!decision.routeScores().isEmpty()) {
            // 有分数但未激活任何路由能力（GRAPH_REQUIRED/VECTOR_SUFFICIENT/
            // BM25_REQUIRED 皆无）时 classifierRoutes 兜底——这条路和弃权路
            // 一样属于"意图不明确"，同样走窄回退；确定的意图不受影响。
            routes = classifierRoutes(decision, uncertainFallback(decision, baseline));
        } else {
            routes = switch (decision.intent()) {
                case NO_RAG -> EnumSet.noneOf(RetrievalRoute.class);
                case KNOWLEDGE_LOOKUP -> EnumSet.of(RetrievalRoute.VECTOR);
                case RELATIONSHIP, UNCERTAIN -> EnumSet.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);
            };
        }
        if (decision.signals().contains(IntentSignal.EXACT_IDENTIFIER)) routes.add(RetrievalRoute.BM25);
        EnumSet<RetrievalRoute> supported = EnumSet.noneOf(RetrievalRoute.class);
        supported.addAll(context.availableRoutes());
        supported.retainAll(capabilityRegistry.routesFor(sources));
        // A required branch must not be silently dropped. If it is not ready
        // or not authorized for this request, use the complete safe baseline.
        if (decision.intent() != QueryIntent.NO_RAG && !supported.containsAll(routes)) {
            return baseline;
        }
        routes.retainAll(supported);
        if (decision.intent() != QueryIntent.NO_RAG && routes.isEmpty()) return baseline;
        return selection(sources, routes);
    }

    /**
     * 弃权/歧义回退宽度（zhimesh.intent-routing.graph-on-uncertain）。
     * false（默认）= 窄回退：只保留 VECTOR——最便宜且最稳的一路；BM25 仅在
     * EXACT_IDENTIFIER 明确信号且索引就绪时附加；GRAPH 只在确定 RELATIONSHIP
     * 意图（GRAPH_REQUIRED 激活）时执行，绝不进入"不确定"的默认套餐。
     * true = 旧版宽回退（V+G+B 有什么查什么），供回滚对照。
     * Fallback width for an ambiguous intent: narrow (VECTOR only, BM25 only
     * on an explicit EXACT_IDENTIFIER signal) by default; graph-on-uncertain
     * =true restores the legacy wide baseline for rollback comparison.
     */
    private RetrievalSelection uncertainFallback(IntentDecision decision,
                                                 RetrievalSelection baseline) {
        RetrievalSelection fallback = properties.getIntentRouting().isGraphOnUncertain()
                ? baseline : narrowToVector(baseline);
        if (!decision.signals().contains(IntentSignal.EXACT_IDENTIFIER)
                || !baseline.routes().contains(RetrievalRoute.BM25)
                || fallback.routes().contains(RetrievalRoute.BM25)) {
            return fallback;
        }
        EnumSet<RetrievalRoute> routes = EnumSet.copyOf(fallback.routes());
        routes.add(RetrievalRoute.BM25);
        return new RetrievalSelection(fallback.sources(), routes);
    }

    private static RetrievalSelection narrowToVector(RetrievalSelection baseline) {
        if (baseline.routes().contains(RetrievalRoute.VECTOR)) {
            return new RetrievalSelection(baseline.sources(), EnumSet.of(RetrievalRoute.VECTOR));
        }
        // VECTOR 不可用（极端配置）时保持原基线，保证回退永远有路可走。
        // Keep the original baseline when VECTOR is unavailable so the
        // fallback always has at least one executable route.
        return baseline;
    }

    private static EnumSet<RetrievalRoute> classifierRoutes(IntentDecision decision,
                                                            RetrievalSelection baseline) {
        if (decision.intent() == QueryIntent.NO_RAG) return EnumSet.noneOf(RetrievalRoute.class);
        Set<RoutingCapability> activated = decision.activatedCapabilities();
        boolean vectorSufficient = activated.contains(RoutingCapability.VECTOR_SUFFICIENT);
        boolean graphRequired = activated.contains(RoutingCapability.GRAPH_REQUIRED);
        boolean bm25Required = activated.contains(RoutingCapability.BM25_REQUIRED);
        if (vectorSufficient && !graphRequired && !bm25Required) {
            return EnumSet.of(RetrievalRoute.VECTOR);
        }
        EnumSet<RetrievalRoute> routes = EnumSet.of(RetrievalRoute.VECTOR);
        if (graphRequired) routes.add(RetrievalRoute.GRAPH);
        if (bm25Required) routes.add(RetrievalRoute.BM25);
        if (!graphRequired && !bm25Required) {
            routes.clear();
            routes.addAll(baseline.routes());
        }
        return routes;
    }

    RetrievalSelection baseline(IntentRoutingContext context) {
        EnumSet<RetrievalRoute> routes = EnumSet.noneOf(RetrievalRoute.class);
        routes.addAll(context.availableRoutes());
        Set<KnowledgeSourceType> sources = context.authorizedSources().contains(KnowledgeSourceType.DOCUMENT_KB)
                ? Set.of(KnowledgeSourceType.DOCUMENT_KB) : context.authorizedSources();
        routes.retainAll(capabilityRegistry.routesFor(sources));
        return selection(sources, routes);
    }

    private static RetrievalSelection selection(Set<KnowledgeSourceType> sources, Set<RetrievalRoute> routes) {
        return new RetrievalSelection(sources, routes);
    }

    public Set<RetrievalRoute> availableRoutesFor(Set<KnowledgeSourceType> sources) {
        return capabilityRegistry.routesFor(sources);
    }
}
