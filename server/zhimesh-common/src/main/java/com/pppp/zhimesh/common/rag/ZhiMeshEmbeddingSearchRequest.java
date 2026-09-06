package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;

import java.util.List;

public class ZhiMeshEmbeddingSearchRequest extends EmbeddingSearchRequest {

    private List<String> ids;

    public ZhiMeshEmbeddingSearchRequest(List<String> ids, Embedding queryEmbedding, Integer maxResults, Double minScore, Filter filter) {
        super(queryEmbedding, maxResults, minScore, filter);
        this.ids = ids;
    }

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }

    public static ZhiMeshEmbeddingSearchRequestBuilder adiBuilder() {
        return new ZhiMeshEmbeddingSearchRequestBuilder();
    }

    public static class ZhiMeshEmbeddingSearchRequestBuilder {
        private List<String> ids;
        private Embedding queryEmbedding;
        private Integer maxResults;
        private Double minScore;
        private Filter filter;

        ZhiMeshEmbeddingSearchRequestBuilder() {
        }

        public ZhiMeshEmbeddingSearchRequestBuilder ids(List<String> ids) {
            this.ids = ids;
            return this;
        }

        public ZhiMeshEmbeddingSearchRequestBuilder queryEmbedding(Embedding queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
            return this;
        }

        public ZhiMeshEmbeddingSearchRequestBuilder maxResults(Integer maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public ZhiMeshEmbeddingSearchRequestBuilder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        public ZhiMeshEmbeddingSearchRequestBuilder filter(Filter filter) {
            this.filter = filter;
            return this;
        }

        public ZhiMeshEmbeddingSearchRequest build() {
            return new ZhiMeshEmbeddingSearchRequest(this.ids, this.queryEmbedding, this.maxResults, this.minScore, this.filter);
        }

        public String toString() {
            return "ZhiMeshEmbeddingSearchRequest.ZhiMeshEmbeddingSearchRequestBuilder(queryEmbedding=" + this.queryEmbedding + ", maxResults=" + this.maxResults + ", minScore=" + this.minScore + ", filter=" + this.filter + ")";
        }
    }
}