package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrieverCapabilityRegistry;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.springframework.stereotype.Component;

/** Factory registered beside the existing vector and graph RAG contexts. */
@Component
public class Bm25Rag {

    @Getter
    private final String name = ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE;

    private final Bm25Repository repository;
    private final Bm25Tokenizer tokenizer;
    private final Bm25ReadinessService readinessService;
    private final ZhiMeshProperties properties;
    private final RetrieverCapabilityRegistry capabilityRegistry;

    public Bm25Rag(Bm25Repository repository,
                   Bm25Tokenizer tokenizer,
                   Bm25ReadinessService readinessService,
                   ZhiMeshProperties properties,
                   RetrieverCapabilityRegistry capabilityRegistry) {
        this.repository = repository;
        this.tokenizer = tokenizer;
        this.readinessService = readinessService;
        this.properties = properties;
        this.capabilityRegistry = capabilityRegistry;
    }

    @PostConstruct
    void register() {
        Bm25RagContext.add(this);
        if (properties.getRetrieval().getBm25().isEnabled()) {
            capabilityRegistry.register(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        }
    }

    @PreDestroy
    void unregister() {
        capabilityRegistry.unregister(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        Bm25RagContext.remove(name);
    }

    public Bm25ContentRetriever createRetriever(RetrieverCreateParam param) {
        if (!properties.getRetrieval().getBm25().isEnabled()) {
            throw new Bm25UnavailableException("BM25 retrieval is disabled");
        }
        if (param == null || param.getKnowledgeBaseUuids() == null
                || param.getKnowledgeBaseUuids().isEmpty()) {
            throw new Bm25UnavailableException(
                    "BM25 retrieval requires an authorized knowledge-base scope");
        }
        ZhiMeshProperties.Retrieval.Bm25 config = properties.getRetrieval().getBm25();
        int configuredTopK = Math.max(1, config.getTopK());
        return new Bm25ContentRetriever(repository, tokenizer, readinessService,
                param.getKnowledgeBaseUuids(), configuredTopK,
                config.getMaxQueryTerms(), config.getK1(), config.getB(),
                config.getMaxDocumentFrequencyRatio(),
                config.getDocumentFrequencyFilterMinDocuments());
    }
}
