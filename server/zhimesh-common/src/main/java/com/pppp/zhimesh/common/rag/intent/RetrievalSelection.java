package com.pppp.zhimesh.common.rag.intent;

import java.util.Set;

/** Orthogonal source and method selection. */
public record RetrievalSelection(Set<KnowledgeSourceType> sources,
                                 Set<RetrievalRoute> routes) {
    public RetrievalSelection {
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        routes = routes == null ? Set.of() : Set.copyOf(routes);
    }
}
