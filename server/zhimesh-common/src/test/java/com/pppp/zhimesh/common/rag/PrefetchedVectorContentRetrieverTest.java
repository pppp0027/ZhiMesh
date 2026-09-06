package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PrefetchedVectorContentRetrieverTest {

    @Test
    @SuppressWarnings("unchecked")
    void filtersAndReusesScopedMatchesWithoutEmbeddingOrStoreCall() {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        Content strong = content("相关内容", "strong-id", 0.83D);
        Content weak = content("弱相关内容", "weak-id", 0.59D);
        ZhiMeshEmbeddingStoreContentRetriever retriever =
                ZhiMeshEmbeddingStoreContentRetriever.builder()
                        .embeddingStore(store)
                        .embeddingModel(model)
                        .prefetchedContents(List.of(strong, weak))
                        .maxResults(3)
                        .minScore(0.60D)
                        .build();

        assertEquals(List.of(strong), retriever.retrieve(Query.from("问题")));
        assertEquals(Map.of("strong-id", 0.83D), retriever.getRetrievedEmbeddingToScore());
        verifyNoInteractions(store, model);
    }

    private static Content content(String text, String embeddingId, double score) {
        return Content.from(TextSegment.from(text, new Metadata(Map.of(
                "embedding_id", embeddingId,
                RetrievedCandidate.VECTOR_SCORE, score))));
    }
}
