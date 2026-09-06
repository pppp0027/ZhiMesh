package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.interfaces.IRAGService;
import com.pppp.zhimesh.common.util.InputAdaptor;
import com.pppp.zhimesh.common.vo.EmbeddingIngestParam;
import com.pppp.zhimesh.common.vo.InputAdaptorMsg;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.*;
import static com.pppp.zhimesh.common.vo.InputAdaptorMsg.TOKEN_TOO_MUCH_QUESTION;

@Slf4j
public class EmbeddingRag implements IRAGService {

    /**
     * RAG名称，用于区分不同的实例
     */
    @Getter
    private final String name;

    private final EmbeddingModel embeddingModel;

    private final EmbeddingStore<TextSegment> embeddingStore;

    public EmbeddingRag(String name, EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.name = name;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 对文档切块、向量化并存储到数据库
     *
     * @param document 知识库文档
     * @param params   入库配置参数
     */
    @Override
    public void ingest(Document document, EmbeddingIngestParam params) {
        log.info("EmbeddingRag ingest, strategy:{}, maxSegmentSize:{}, TokenCountEstimator:{}",
                params.getStrategy(), params.getMaxSegmentSize(), params.getTokenEstimator());
        DocumentSplitter documentSplitter = DocumentSplitterFactory.create(
                params.getStrategy(), params.getMaxSegmentSize(), params.getOverlap(),
                params.getCustomSeparator(), TokenEstimatorFactory.create(params.getTokenEstimator()));
        EmbeddingStoreIngestor embeddingStoreIngestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(documentSplitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
        embeddingStoreIngestor.ingest(document);
    }

    /**
     * Ingests the canonical segments prepared once for every retrieval index.
     * Their metadata contains the stable chunk/chunk-set identifiers shared by
     * Vector and BM25, so this path deliberately performs no second split.
     */
    public void ingestSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("segments must not be empty");
        }
        var embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings == null || embeddings.size() != segments.size()) {
            throw new IllegalStateException("Embedding model returned an unexpected segment count");
        }
        embeddingStore.addAll(embeddings, segments);
    }

    /**
     * 创建召回器
     *
     * @param param 条件
     * @return ContentRetriever
     */
    @Override
    public ZhiMeshEmbeddingStoreContentRetriever createRetriever(RetrieverCreateParam param) {
        return ZhiMeshEmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .queryEmbedding(param.getQueryEmbedding())
                .prefetchedContents(param.getPrefetchedVectorContents())
                .maxResults(param.getMaxResults() <= 0 ? 3 : param.getMaxResults())
                .minScore(param.getMinScore() <= 0 ? RAG_MIN_SCORE : param.getMinScore())
                .filter(param.getFilter())
                .breakIfSearchMissed(param.isBreakIfSearchMissed())
                .build();
    }

    /**
     * 根据模型的contentWindow计算使用该模型最多召回的文档数量
     * <br/>以分块时的最大文本段对应的token数量{maxSegmentSizeInTokens}为计算因子
     *
     * @param userQuestion   用户的问题
     * @param maxInputTokens AI模型所能容纳的窗口大小
     * @return
     */
    public static int getRetrieveMaxResults(String userQuestion, int maxInputTokens) {
        if (maxInputTokens == 0) {
            return RAG_RETRIEVE_NUMBER_MAX;
        }
        InputAdaptorMsg inputAdaptorMsg = InputAdaptor.isQuestionValid(userQuestion, maxInputTokens);
        if (inputAdaptorMsg.getTokenTooMuch() == TOKEN_TOO_MUCH_QUESTION) {
            log.warn("User question too long, not enough tokens left for retrieved content");
            return 0;
        } else {
            int maxRetrieveDocLength = maxInputTokens - inputAdaptorMsg.getUserQuestionTokenCount();
            if (maxRetrieveDocLength > RAG_RETRIEVE_NUMBER_MAX * RAG_MAX_SEGMENT_SIZE_IN_TOKENS) {
                return RAG_RETRIEVE_NUMBER_MAX;
            } else {
                return maxRetrieveDocLength / RAG_MAX_SEGMENT_SIZE_IN_TOKENS;
            }
        }

    }
}
