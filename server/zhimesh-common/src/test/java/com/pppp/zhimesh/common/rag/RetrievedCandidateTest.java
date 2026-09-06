package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievedCandidateTest {

    @Test
    void mergesRouteProvenanceAndKeepsScores() {
        Content vector = Content.from(TextSegment.from("same evidence", new Metadata(Map.of(
                ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, "doc-vector",
                RetrievedCandidate.VECTOR_SCORE, 0.82D))));
        Content graph = Content.from(TextSegment.from("same evidence", new Metadata(Map.of(
                ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, "doc-graph",
                RetrievedCandidate.GRAPH_ELEMENT_ID, "edge-1"))));

        RetrievedCandidate merged = RetrievedCandidate.from(vector, "vector", 1);
        merged.merge(RetrievedCandidate.from(graph, "graph", 2));
        merged.calculateRrfScore();
        Map<String, Object> metadata = merged.toContent().textSegment().metadata().toMap();

        assertEquals("vector,graph", metadata.get(RetrievedCandidate.ROUTES));
        assertEquals("doc-vector,doc-graph", metadata.get(RetrievedCandidate.SOURCE_DOCUMENT_IDS));
        assertEquals("edge-1", metadata.get(RetrievedCandidate.GRAPH_ELEMENT_IDS));
        assertEquals(0.82D, metadata.get(RetrievedCandidate.VECTOR_SCORE));
        assertTrue((Double) metadata.get(RetrievedCandidate.RRF_SCORE) > 0D);
    }
}
