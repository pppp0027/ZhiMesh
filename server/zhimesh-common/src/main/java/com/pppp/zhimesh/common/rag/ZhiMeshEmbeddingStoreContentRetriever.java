package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.exception.BaseException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.spi.model.embedding.EmbeddingModelFactory;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.function.Function;

import static com.pppp.zhimesh.common.enums.ErrorEnum.B_BREAK_SEARCH;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.*;
import static dev.langchain4j.spi.ServiceHelper.loadFactories;

/**
 * 复制dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever并做了少许改动；
 * 增加支持：缓存命中的向量以便后续记录到数据库中
 */
@Slf4j
public class ZhiMeshEmbeddingStoreContentRetriever implements ContentRetriever {

    public static final Function<Query, Integer> DEFAULT_MAX_RESULTS = (query) -> 3;
    public static final Function<Query, Double> DEFAULT_MIN_SCORE = (query) -> 0.0;
    public static final Function<Query, Filter> DEFAULT_FILTER = (query) -> null;

    public static final String DEFAULT_DISPLAY_NAME = "Default";

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final Embedding queryEmbedding;
    private final List<Content> prefetchedContents;

    private final Function<Query, Integer> maxResultsProvider;
    private final Function<Query, Double> minScoreProvider;
    private final Function<Query, Filter> filterProvider;

    private final String displayName;

    /**
     * 新增的特性: 命中的向量及对应的分数
     */
    private final Map<String, Double> embeddingToScore = new HashMap<>();

    private final boolean breakIfSearchMissed;

    public ZhiMeshEmbeddingStoreContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                             EmbeddingModel embeddingModel) {
        this(
                DEFAULT_DISPLAY_NAME,
                embeddingStore,
                embeddingModel,
                null,
                null,
                DEFAULT_MAX_RESULTS,
                DEFAULT_MIN_SCORE,
                DEFAULT_FILTER,
                false
        );
    }

    public ZhiMeshEmbeddingStoreContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                             EmbeddingModel embeddingModel,
                                             int maxResults) {
        this(
                DEFAULT_DISPLAY_NAME,
                embeddingStore,
                embeddingModel,
                null,
                null,
                (query) -> maxResults,
                DEFAULT_MIN_SCORE,
                DEFAULT_FILTER,
                false
        );
    }

    public ZhiMeshEmbeddingStoreContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                             EmbeddingModel embeddingModel,
                                             Integer maxResults,
                                             Double minScore) {
        this(
                DEFAULT_DISPLAY_NAME,
                embeddingStore,
                embeddingModel,
                null,
                null,
                (query) -> maxResults,
                (query) -> minScore,
                DEFAULT_FILTER,
                false
        );
    }

    private ZhiMeshEmbeddingStoreContentRetriever(String displayName,
                                              EmbeddingStore<TextSegment> embeddingStore,
                                              EmbeddingModel embeddingModel,
                                              Embedding queryEmbedding,
                                              List<Content> prefetchedContents,
                                              Function<Query, Integer> dynamicMaxResults,
                                              Function<Query, Double> dynamicMinScore,
                                              Function<Query, Filter> dynamicFilter,
                                              Boolean breakIfSearchMissed) {
        this.displayName = getOrDefault(displayName, DEFAULT_DISPLAY_NAME);
        this.embeddingStore = ensureNotNull(embeddingStore, "embeddingStore");
        this.embeddingModel = ensureNotNull(
                getOrDefault(embeddingModel, ZhiMeshEmbeddingStoreContentRetriever::loadEmbeddingModel),
                "embeddingModel"
        );
        this.queryEmbedding = queryEmbedding;
        this.prefetchedContents = prefetchedContents == null ? null : List.copyOf(prefetchedContents);
        this.maxResultsProvider = getOrDefault(dynamicMaxResults, DEFAULT_MAX_RESULTS);
        this.minScoreProvider = getOrDefault(dynamicMinScore, DEFAULT_MIN_SCORE);
        this.filterProvider = getOrDefault(dynamicFilter, DEFAULT_FILTER);
        this.breakIfSearchMissed = Boolean.TRUE.equals(breakIfSearchMissed);
    }

    private static EmbeddingModel loadEmbeddingModel() {
        Collection<EmbeddingModelFactory> factories = loadFactories(EmbeddingModelFactory.class);
        if (factories.size() > 1) {
            throw new RuntimeException("Conflict: multiple embedding models have been found in the classpath. " +
                    "Please explicitly specify the one you wish to use.");
        }

        for (EmbeddingModelFactory factory : factories) {
            return factory.create();
        }

        return null;
    }

    public static ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder builder() {
        return new ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder();
    }

    public static class ZhiMeshEmbeddingStoreContentRetrieverBuilder {

        private String displayName;
        private EmbeddingStore<TextSegment> embeddingStore;
        private EmbeddingModel embeddingModel;
        private Embedding queryEmbedding;
        private List<Content> prefetchedContents;
        private Function<Query, Integer> dynamicMaxResults;
        private Function<Query, Double> dynamicMinScore;
        private Function<Query, Filter> dynamicFilter;

        private Boolean breakIfSearchMissed;

        ZhiMeshEmbeddingStoreContentRetrieverBuilder() {
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder maxResults(Integer maxResults) {
            if (maxResults != null) {
                dynamicMaxResults = (query) -> ensureGreaterThanZero(maxResults, "maxResults");
            }
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder minScore(Double minScore) {
            if (minScore != null) {
                dynamicMinScore = (query) -> ensureBetween(minScore, 0, 1, "minScore");
            }
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder filter(Filter filter) {
            if (filter != null) {
                dynamicFilter = (query) -> filter;
            }
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder embeddingStore(EmbeddingStore<TextSegment> embeddingStore) {
            this.embeddingStore = embeddingStore;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder queryEmbedding(Embedding queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetrieverBuilder prefetchedContents(List<Content> prefetchedContents) {
            this.prefetchedContents = prefetchedContents;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder dynamicMaxResults(Function<Query, Integer> dynamicMaxResults) {
            this.dynamicMaxResults = dynamicMaxResults;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder dynamicMinScore(Function<Query, Double> dynamicMinScore) {
            this.dynamicMinScore = dynamicMinScore;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder dynamicFilter(Function<Query, Filter> dynamicFilter) {
            this.dynamicFilter = dynamicFilter;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetrieverBuilder breakIfSearchMissed(boolean breakIfSearchMissed) {
            this.breakIfSearchMissed = breakIfSearchMissed;
            return this;
        }

        public ZhiMeshEmbeddingStoreContentRetriever build() {
            return new ZhiMeshEmbeddingStoreContentRetriever(this.displayName, this.embeddingStore,
                    this.embeddingModel, this.queryEmbedding, this.prefetchedContents,
                    this.dynamicMaxResults, this.dynamicMinScore, this.dynamicFilter,
                    this.breakIfSearchMissed);
        }


        public String toString() {
            return "ZhiMeshEmbeddingStoreContentRetriever.ZhiMeshEmbeddingStoreContentRetrieverBuilder(displayName=" + this.displayName + ", embeddingStore=" + this.embeddingStore + ", embeddingModel=" + this.embeddingModel + ", hasQueryEmbedding=" + (this.queryEmbedding != null) + ", dynamicMaxResults=" + this.dynamicMaxResults + ", dynamicMinScore=" + this.dynamicMinScore + ", dynamicFilter=" + this.dynamicFilter + ", breakIfSearchMissed=" + this.breakIfSearchMissed + ")";
        }
    }

    /**
     * Creates an instance of an {@code EmbeddingStoreContentRetriever} from the specified {@link EmbeddingStore}
     * and {@link EmbeddingModel} found through SPI (see {@link EmbeddingModelFactory}).
     */
    public static ZhiMeshEmbeddingStoreContentRetriever from(EmbeddingStore<TextSegment> embeddingStore) {
        return builder().embeddingStore(embeddingStore).build();
    }

    @Override
    public List<Content> retrieve(Query query) {

        if (prefetchedContents != null && !prefetchedContents.isEmpty()) {
            int maxResults = maxResultsProvider.apply(query);
            double minScore = minScoreProvider.apply(query);
            List<Content> result = prefetchedContents.stream()
                    .filter(content -> vectorScore(content) >= minScore)
                    .limit(maxResults)
                    .toList();
            for (Content content : result) {
                Object embeddingId = content.textSegment().metadata().toMap().get("embedding_id");
                if (embeddingId != null) {
                    embeddingToScore.put(String.valueOf(embeddingId), vectorScore(content));
                }
            }
            if (breakIfSearchMissed && result.isEmpty()) {
                throw new BaseException(B_BREAK_SEARCH);
            }
            return result;
        }

        Embedding embeddedQuery = queryEmbedding != null
                ? queryEmbedding
                : embeddingModel.embed(query.text()).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddedQuery)
                .maxResults(maxResultsProvider.apply(query))
                .minScore(minScoreProvider.apply(query))
                .filter(filterProvider.apply(query))
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        List<Content> result = new ArrayList<>();
        int rank = 0;
        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            rank++;
            embeddingToScore.put(match.embeddingId(), match.score());
            log.info("embeddingToScore,embeddingId:{},score:{}", match.embeddingId(), match.score());
            TextSegment embedded = match.embedded();
            Map<String, Object> metadata = new LinkedHashMap<>(embedded.metadata().toMap());
            metadata.put(RetrievedCandidate.ROUTE, "vector");
            metadata.put(RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.ORIGINAL_SEGMENT);
            metadata.put(RetrievedCandidate.VECTOR_SCORE, match.score());
            metadata.put(RetrievedCandidate.VECTOR_RANK, rank);
            metadata.put("embedding_id", match.embeddingId());
            result.add(Content.from(TextSegment.from(embedded.text(), new Metadata(metadata))));
        }

//Determine whether to forcibly interrupt the query, if no match then stop further operations
        //判断是否要强行中断查询，没有命中则不再进行下一步操作（比如说请求LLM），直接抛出异常中断流程
        if (breakIfSearchMissed && CollectionUtils.isEmpty(result)) {
            log.warn("Embedding search missed,query:{}", query.text());
            throw new BaseException(B_BREAK_SEARCH);
        }
        return result;
    }

    private static double vectorScore(Content content) {
        Object value = content.textSegment().metadata().toMap().get(RetrievedCandidate.VECTOR_SCORE);
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return -1D;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1D;
        }
    }

    /**
     * zhimesh新增方法
     *
     * @return
     */
    public Map<String, Double> getRetrievedEmbeddingToScore() {
        return this.embeddingToScore;
    }

    /** Removes raw matches that were rejected by the request-level relevance gate. */
    public void retainRetrievedEmbeddings(Set<String> acceptedEmbeddingIds) {
        if (acceptedEmbeddingIds == null || acceptedEmbeddingIds.isEmpty()) {
            embeddingToScore.clear();
            return;
        }
        embeddingToScore.keySet().retainAll(acceptedEmbeddingIds);
    }

    @Override
    public String toString() {
        return "ZhiMeshEmbeddingStoreContentRetriever{" +
                "displayName='" + displayName + '\'' +
                '}';
    }
}
