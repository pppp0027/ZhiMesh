package com.pppp.zhimesh.common.rag.intent;

import dev.langchain4j.rag.content.Content;

import java.util.List;
import java.util.Set;

/** Three-state KB-scope decision; only UNRELATED skips document routing. */
public record KnowledgeScopeDecision(
        Status status,
        double maxVectorScore,
        String reason,
        List<Content> prefetchedVectorContents,
        long durationMs,
        Set<String> retrievalKnowledgeBaseUuids,
        boolean retrievalScopeResolved
) {
    public KnowledgeScopeDecision(Status status, double maxVectorScore, String reason,
                                  List<Content> prefetchedVectorContents, long durationMs) {
        this(status, maxVectorScore, reason, prefetchedVectorContents, durationMs,
                Set.of(), false);
    }

    public enum Status { RELATED, UNCERTAIN, UNRELATED, NOT_APPLICABLE }

    public KnowledgeScopeDecision {
        prefetchedVectorContents = prefetchedVectorContents == null
                ? List.of() : List.copyOf(prefetchedVectorContents);
        retrievalKnowledgeBaseUuids = retrievalKnowledgeBaseUuids == null
                ? Set.of() : Set.copyOf(retrievalKnowledgeBaseUuids);
    }

    public boolean skipKnowledgeBaseRouting() {
        return status == Status.UNRELATED
                || (retrievalScopeResolved && retrievalKnowledgeBaseUuids.isEmpty());
    }

    /**
     * True when preflight evaluated attached knowledge bases independently and
     * the UUID set is the request-scoped retrieval scope. An empty set is
     * meaningful here: every attached scope was safely classified unrelated.
     */
    public boolean hasRetrievalScope() {
        return retrievalScopeResolved;
    }
}
