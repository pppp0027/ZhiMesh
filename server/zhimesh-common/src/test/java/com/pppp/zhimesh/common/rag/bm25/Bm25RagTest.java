package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrieverCapabilityRegistry;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Bm25RagTest {

    @Test
    void enabledLifecycleRegistersContextAndDocumentCapability() {
        ZhiMeshProperties properties = properties(true);
        RetrieverCapabilityRegistry capabilities = new RetrieverCapabilityRegistry();
        Bm25Rag rag = rag(properties, capabilities);

        rag.register();
        try {
            assertSame(rag, Bm25RagContext.get(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE));
            assertTrue(capabilities.supports(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25));
        } finally {
            rag.unregister();
        }

        assertNull(Bm25RagContext.get(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE));
        assertFalse(capabilities.supports(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25));
    }

    @Test
    void disabledLifecycleDoesNotAdvertiseBm25Capability() {
        ZhiMeshProperties properties = properties(false);
        RetrieverCapabilityRegistry capabilities = new RetrieverCapabilityRegistry();
        Bm25Rag rag = rag(properties, capabilities);

        rag.register();
        try {
            assertSame(rag, Bm25RagContext.get(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE));
            assertFalse(capabilities.supports(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25));
        } finally {
            rag.unregister();
        }
    }

    @Test
    void createRetrieverUsesIndependentConfiguredTopK() {
        ZhiMeshProperties properties = properties(true);
        properties.getRetrieval().getBm25().setTopK(37);
        Bm25Repository repository = mock(Bm25Repository.class);
        Bm25ReadinessService readiness = mock(Bm25ReadinessService.class);
        Bm25Rag rag = new Bm25Rag(repository, new Bm25Tokenizer("test-analyzer-v1"),
                readiness, properties, new RetrieverCapabilityRegistry());
        RetrieverCreateParam request = RetrieverCreateParam.builder()
                .knowledgeBaseUuids(Set.of("kb-a"))
                .maxResults(2)
                .build();

        rag.createRetriever(request).retrieve(Query.from("alpha"));

        verify(repository).search(eq(Set.of("kb-a")), eq(List.of("alpha")),
                eq("test-analyzer-v1"), eq(properties.getRetrieval().getBm25().getK1()),
                eq(properties.getRetrieval().getBm25().getB()), eq(37),
                eq(properties.getRetrieval().getBm25().getMaxDocumentFrequencyRatio()),
                eq(properties.getRetrieval().getBm25().getDocumentFrequencyFilterMinDocuments()));
    }

    private static Bm25Rag rag(ZhiMeshProperties properties,
                               RetrieverCapabilityRegistry capabilities) {
        return new Bm25Rag(mock(Bm25Repository.class), new Bm25Tokenizer("test-analyzer-v1"),
                mock(Bm25ReadinessService.class), properties, capabilities);
    }

    private static ZhiMeshProperties properties(boolean enabled) {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getRetrieval().getBm25().setEnabled(enabled);
        return properties;
    }
}
