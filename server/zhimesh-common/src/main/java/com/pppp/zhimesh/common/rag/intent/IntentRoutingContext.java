package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import dev.langchain4j.data.embedding.Embedding;

import java.util.Set;

/** Request-scoped inputs. A model-produced source hint never expands this authorized scope. */
public record IntentRoutingContext(String query,
                                   Embedding queryEmbedding,
                                   RetrievalMode explicitMode,
                                   Set<KnowledgeSourceType> authorizedSources,
                                   Set<RetrievalRoute> availableRoutes,
                                   String embeddingModelId) {
    public IntentRoutingContext {
        query = query == null ? "" : query;
        authorizedSources = authorizedSources == null
                ? Set.of(KnowledgeSourceType.DOCUMENT_KB) : Set.copyOf(authorizedSources);
        availableRoutes = availableRoutes == null
                ? Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH) : Set.copyOf(availableRoutes);
        embeddingModelId = embeddingModelId == null ? "unknown" : embeddingModelId;
    }
}
