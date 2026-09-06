package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.vo.RetrievalQueryContext;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryEmbeddingReuseTest {

    @Test
    void reusesOneQueryEmbeddingAcrossThreeVectorStores() {
        CountingEmbeddingModel embeddingModel = new CountingEmbeddingModel();
        RetrievalQueryContext queryContext = RetrievalQueryContext.create("How should I prepare?", embeddingModel);
        List<EmbeddingStore<TextSegment>> stores = List.of(emptyStore(), emptyStore(), emptyStore());

        for (int index = 0; index < stores.size(); index++) {
            ZhiMeshEmbeddingStoreContentRetriever retriever = new EmbeddingRag(
                    "route-" + index, embeddingModel, stores.get(index))
                    .createRetriever(RetrieverCreateParam.builder()
                            .queryEmbedding(queryContext.embedding())
                            .maxResults(3)
                            .minScore(0.6D)
                            .build());

            retriever.retrieve(Query.from(queryContext.text()));
        }

        assertThat(embeddingModel.invocations()).isEqualTo(1);
        stores.forEach(store -> verify(store).search(argThat(
                request -> request.queryEmbedding() == queryContext.embedding())));
    }

    @Test
    void fallsBackToEmbeddingTheQueryWhenNoPrecomputedEmbeddingIsProvided() {
        CountingEmbeddingModel embeddingModel = new CountingEmbeddingModel();
        EmbeddingStore<TextSegment> store = emptyStore();
        ZhiMeshEmbeddingStoreContentRetriever retriever = new EmbeddingRag(
                "fallback", embeddingModel, store)
                .createRetriever(RetrieverCreateParam.builder()
                        .maxResults(3)
                        .minScore(0.6D)
                        .build());

        retriever.retrieve(Query.from("legacy query"));

        assertThat(embeddingModel.invocations()).isEqualTo(1);
        verify(store).search(argThat(request -> request.queryEmbedding() != null));
    }

    @Test
    void preservesTheSharedEmbeddingWhenCompositeRagCopiesRouteParameters() {
        Embedding queryEmbedding = Embedding.from(new float[]{0.25F, 0.75F});
        RetrieverCreateParam source = RetrieverCreateParam.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(3)
                .minScore(0.6D)
                .breakIfSearchMissed(true)
                .build();

        RetrieverCreateParam copied = CompositeRag.copyForSoftRoute(source);

        assertThat(copied.getQueryEmbedding()).isSameAs(queryEmbedding);
        assertThat(copied.isBreakIfSearchMissed()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static EmbeddingStore<TextSegment> emptyStore() {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        return store;
    }

    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            invocations.incrementAndGet();
            List<Embedding> embeddings = textSegments.stream()
                    .map(ignored -> Embedding.from(new float[]{0.25F, 0.75F}))
                    .toList();
            return Response.from(embeddings);
        }

        int invocations() {
            return invocations.get();
        }
    }
}
