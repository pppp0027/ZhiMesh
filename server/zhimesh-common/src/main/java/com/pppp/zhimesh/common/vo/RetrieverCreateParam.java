package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.rag.BgeReranker;
import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Content Retriever创建参数
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class RetrieverCreateParam {
    /** Precomputed query embedding shared by vector routes in the current request. */
    private Embedding queryEmbedding;

    /**
     * Request-scoped tokenizer used when packing the retrieved context.  This is
     * deliberately carried with the request instead of relying on a ThreadLocal,
     * because streaming retrieval may execute on a different thread.
     */
    private TokenCountEstimator tokenEstimator;

    /** Retrieval routes enabled for this request. */
    private RetrievalMode retrievalMode;

    /**
     * Route-set contract used by the generalized retrieval engine. When empty,
     * {@link #retrievalMode} remains the backward-compatible source of routes.
     */
    @Builder.Default
    private Set<RetrievalRoute> retrievalRoutes = Set.of();

    /** Authorized knowledge bases for route implementations that query an external index. */
    @Builder.Default
    private Set<String> knowledgeBaseUuids = Set.of();

    /**
     * 用来生成图谱，生成向量时不使用
     */
    private ChatModel chatModel;
    /**
     * 过滤条件
     */
    private Filter filter;
    /**
     * 最大返回数量
     */
    private int maxResults;
    /**
     * 最小命中分数
     */
    private double minScore;
    /**
     * 如果数据库中搜索不到数据，是否强行中断该搜索，不继续往下执行（即不继续请求LLM进行回答）
     */
    private boolean breakIfSearchMissed;

    @Builder.Default
    private int graphHopDepth = 1;

    private BgeReranker reranker;

    @Builder.Default
    private int rerankTopN = 5;

    /** Answer model input limit used to derive the RAG token budget. */
    private int maxInputTokens;

    /** System prompt is counted before retrieved context is packed. */
    private String systemMessage;

    /** Apply request-level relevance rejection after retrieval and reranking. */
    private boolean relevanceGateEnabled;

    /** Force cross-encoder scoring when relevance must be validated even for one candidate. */
    private boolean rerankSingleCandidate;

    /** Scoped preflight vector matches reused by the later vector route to avoid a duplicate store query. */
    @Builder.Default
    private java.util.List<Content> prefetchedVectorContents = java.util.List.of();
}
