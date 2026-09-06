package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrievalRouteKey;
import com.pppp.zhimesh.common.rag.intent.RoutedRetriever;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutedRetrieverFusionTest {
    @Test
    void fusesArbitraryExplicitRoutesAndEmitsGenericAndLegacyMetadata() {
        Content shared = Content.from(TextSegment.from("shared evidence"));
        RoutedRetriever vector = new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.VECTOR, "kb-1"),
                query -> List.of(shared));
        RoutedRetriever bm25 = new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25, "kb-1"),
                query -> List.of(shared));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                List.of(vector, bm25), null, 5, false, null,
                new ZhiMeshProperties.Retrieval(), 0, null, true);

        List<Content> result = retriever.retrieve(Query.from("query"));
        Map<String, Object> metadata = result.get(0).textSegment().metadata().toMap();

        assertThat(retriever.getRouteResults()).extracting(RetrievalRouteResult::route)
                .containsExactly("vector", "bm25");
        assertThat(metadata.get(RetrievedCandidate.ROUTES)).isEqualTo("vector,bm25");
        assertThat(metadata.get(RetrievedCandidate.VECTOR_RANK)).isEqualTo(1);
        assertThat(metadata.get(RetrievedCandidate.ROUTE_RANKS)).isEqualTo("vector=1,bm25=1");
        assertThat(metadata.get(RetrievedCandidate.SOURCE_TYPE)).isEqualTo("document_kb");
        assertThat(metadata.get(RetrievedCandidate.SOURCE_INSTANCE_ID)).isEqualTo("kb-1");
        assertThat((Double) metadata.get(RetrievedCandidate.RRF_SCORE))
                .isEqualTo(2D / 61D);
    }

    @Test
    void fusesVectorGraphAndBm25WithoutLosingPerRouteRanks() {
        Content shared = Content.from(TextSegment.from("three route evidence"));
        List<RoutedRetriever> routes = List.of(
                routed(RetrievalRoute.VECTOR, shared),
                routed(RetrievalRoute.GRAPH, shared),
                routed(RetrievalRoute.BM25, shared));
        DeduplicatingContentRetriever retriever = new DeduplicatingContentRetriever(
                routes, null, 5, false, null,
                new ZhiMeshProperties.Retrieval(), 0, null, true);

        Map<String, Object> metadata = retriever.retrieve(Query.from("query"))
                .get(0).textSegment().metadata().toMap();

        assertThat(retriever.getRouteResults()).extracting(RetrievalRouteResult::route)
                .containsExactly("vector", "graph", "bm25");
        assertThat(metadata.get(RetrievedCandidate.ROUTES)).isEqualTo("vector,graph,bm25");
        assertThat(metadata.get(RetrievedCandidate.ROUTE_RANKS))
                .isEqualTo("vector=1,graph=1,bm25=1");
        assertThat((Double) metadata.get(RetrievedCandidate.RRF_SCORE))
                .isEqualTo(3D / 61D);
    }

    private static RoutedRetriever routed(RetrievalRoute route, Content content) {
        return new RoutedRetriever(
                new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, route, "kb-1"),
                query -> List.of(content));
    }
}
