package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationCandidateResp;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationResultMapperTest {

    @Test
    void mapsStableCandidateMetadataAndDocumentNames() {
        Content content = Content.from(TextSegment.from("evidence", new Metadata(Map.ofEntries(
                Map.entry("content_type", "original_segment"),
                Map.entry("retrieval_routes", "vector,graph,bm25"),
                Map.entry("source_document_ids", "doc-1,doc-2"),
                Map.entry("source_segment_ids", "segment-1"),
                Map.entry("vector_score", 0.87D),
                Map.entry("vector_rank", 1),
                Map.entry("graph_rank", 2),
                Map.entry("bm25_rank", 3),
                Map.entry("bm25_score", 4.2D),
                Map.entry("route_ranks", "vector=1,graph=2,bm25=3"),
                Map.entry("rerank_score", 0.93D)
        ))));
        TokenCountEstimator estimator = new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                return text.length();
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return 0;
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                return 0;
            }
        };

        RagEvaluationCandidateResp result = RagEvaluationResultMapper.candidates(
                List.of(content), List.of(content),
                Map.of("doc-1", "first.txt", "doc-2", "second.txt"), estimator).get(0);

        assertEquals(List.of("vector", "graph", "bm25"), result.getRoutes());
        assertEquals(List.of("doc-1", "doc-2"), result.getSourceDocumentIds());
        assertEquals(List.of("first.txt", "second.txt"), result.getSourceDocumentNames());
        assertEquals(0.93D, result.getRerankScore());
        assertEquals(4.2D, result.getBm25Score());
        assertEquals(3, result.getBm25Rank());
        assertEquals(Map.of("vector", 1, "graph", 2, "bm25", 3), result.getRouteRanks());
        assertEquals(8, result.getTokenCount());
        assertTrue(result.isSelected());
    }
}
