package com.pppp.zhimesh.common.rag.intent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverCapabilityRegistryTest {

    @Test
    void bm25CanBeRegisteredAndRemovedWithIndexReadiness() {
        RetrieverCapabilityRegistry registry = new RetrieverCapabilityRegistry();

        assertThat(registry.supports(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25)).isFalse();

        registry.register(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        assertThat(registry.routesFor(Set.of(KnowledgeSourceType.DOCUMENT_KB)))
                .contains(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25);

        registry.unregister(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        assertThat(registry.supports(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25)).isFalse();
    }
}
