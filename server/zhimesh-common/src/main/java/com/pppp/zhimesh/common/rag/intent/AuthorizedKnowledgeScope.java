package com.pppp.zhimesh.common.rag.intent;

import java.util.Set;

/** Server-resolved source scope. User text and model output cannot add sources to it. */
public record AuthorizedKnowledgeScope(Set<KnowledgeSourceType> sources) {
    public AuthorizedKnowledgeScope {
        sources = sources == null ? Set.of() : Set.copyOf(sources);
    }

    public boolean allows(KnowledgeSourceType sourceType) {
        return sources.contains(sourceType);
    }
}
