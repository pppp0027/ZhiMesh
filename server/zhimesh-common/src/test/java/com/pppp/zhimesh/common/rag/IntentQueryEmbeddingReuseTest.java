package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.rag.intent.*;
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

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentQueryEmbeddingReuseTest {
    @Test
    @SuppressWarnings("unchecked")
    void recognitionAndVectorRetrievalShareTheSingleQueryEmbedding() {
        AtomicInteger queryEmbeddingCalls = new AtomicInteger();
        EmbeddingModel model = segments -> {
            queryEmbeddingCalls.incrementAndGet();
            return Response.from(segments.stream().map(ignored -> Embedding.from(new float[]{1F, 0F})).toList());
        };
        RetrievalQueryContext query = RetrievalQueryContext.create("如何部署", model);
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setEnabled(true);
        properties.getIntentRouting().setClassifierEnabled(true);
        LinearRoutingIntentRecognizer classifier = mock(LinearRoutingIntentRecognizer.class);
        when(classifier.recognize(any())).thenReturn(new IntentDecision(
                QueryIntent.KNOWLEDGE_LOOKUP, 0.96D, 0.7D, Set.of(), Set.of(),
                "linear-router:test", "test",
                Map.of(RoutingCapability.VECTOR_SUFFICIENT, 0.96D),
                Set.of(RoutingCapability.VECTOR_SUFFICIENT)));
        IntentRoutingService routing = new IntentRoutingService(
                classifier, mock(PrototypeRouteIntentRecognizer.class),
                new IntentRoutingPolicy(new RetrieverCapabilityRegistry()), properties);
        RetrievalPlan plan = routing.route(query.text(), query.embedding(), null);

        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of()));
        new EmbeddingRag("test", model, store).createRetriever(RetrieverCreateParam.builder()
                .queryEmbedding(query.embedding()).maxResults(2).minScore(0.5D).build())
                .retrieve(Query.from(query.text()));

        assertThat(plan.effective().routes()).containsExactly(RetrievalRoute.VECTOR);
        assertThat(queryEmbeddingCalls).hasValue(1);
    }
}
