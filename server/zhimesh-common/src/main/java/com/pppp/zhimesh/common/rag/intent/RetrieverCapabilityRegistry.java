package com.pppp.zhimesh.common.rag.intent;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Declares method capabilities independently from authorization and intent. */
@Component
public class RetrieverCapabilityRegistry {
    private final Map<KnowledgeSourceType, Set<RetrievalRoute>> capabilities =
            new EnumMap<>(KnowledgeSourceType.class);

    public RetrieverCapabilityRegistry() {
        capabilities.put(KnowledgeSourceType.DOCUMENT_KB,
                EnumSet.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH));
        capabilities.put(KnowledgeSourceType.CHARACTER_MEMORY,
                EnumSet.of(RetrievalRoute.VECTOR));
        capabilities.put(KnowledgeSourceType.EPISODIC_MEMORY,
                EnumSet.of(RetrievalRoute.VECTOR));
        // BM25 is registered by its lifecycle only when the retriever is enabled.
    }

    public synchronized Set<RetrievalRoute> routesFor(Set<KnowledgeSourceType> sources) {
        EnumSet<RetrievalRoute> routes = EnumSet.noneOf(RetrievalRoute.class);
        if (sources != null) {
            sources.forEach(source -> routes.addAll(capabilities.getOrDefault(source, Set.of())));
        }
        return Set.copyOf(routes);
    }

    public synchronized boolean supports(KnowledgeSourceType source, RetrievalRoute route) {
        return capabilities.getOrDefault(source, Set.of()).contains(route);
    }

    /** Registers a route only after its implementation and readiness checks are available. */
    public synchronized void register(KnowledgeSourceType source, RetrievalRoute route) {
        if (source == null || route == null) {
            throw new IllegalArgumentException("source and route are required");
        }
        capabilities.computeIfAbsent(source, ignored -> EnumSet.noneOf(RetrievalRoute.class)).add(route);
    }

    /** Removes a route immediately when the backing index is no longer ready. */
    public synchronized void unregister(KnowledgeSourceType source, RetrievalRoute route) {
        if (source == null || route == null) return;
        Set<RetrievalRoute> registered = capabilities.get(source);
        if (registered != null) registered.remove(route);
    }
}
