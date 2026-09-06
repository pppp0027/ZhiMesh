package com.pppp.zhimesh.common.rag.intent;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Converts a recognition result directly into the route executed by the request. */
@Component
public class IntentRoutingPolicy {
    private final RetrieverCapabilityRegistry capabilityRegistry;

    public IntentRoutingPolicy(RetrieverCapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
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
        boolean fallback = decision.intent() != QueryIntent.NO_RAG
                && selected.routes().equals(baseline.routes())
                && (abstained || !decision.routeScores().isEmpty())
                && decision.activatedCapabilities().isEmpty();
        return new RetrievalPlan(decision, selected, selected, fallback,
                fallback ? "classifier abstained; using current request baseline"
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
            // Rules/prototypes did not produce a reliable route. The safe
            // behavior is the complete request-scoped baseline (V/G and BM25
            // when its index is ready), not a silently narrowed V/G subset.
            return baseline;
        }
        if (!decision.routeScores().isEmpty()) {
            routes = classifierRoutes(decision, baseline);
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
