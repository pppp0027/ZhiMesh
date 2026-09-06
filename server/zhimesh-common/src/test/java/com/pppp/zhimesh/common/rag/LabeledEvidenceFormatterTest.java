package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabeledEvidenceFormatterTest {

    private static Content content(String text, Map<String, String> metadata) {
        return Content.from(TextSegment.from(text, new Metadata(metadata)));
    }

    @Test
    void labelsDocumentSegmentsAndGraphRelationsWithStableNumbering() {
        Content document = content("订单服务依赖库存服务", Map.of());
        Content relation = content("订单服务——影响——>库存服务", Map.of(
                RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.GRAPH_RELATION));

        String rendered = LabeledEvidenceFormatter.format(List.of(document, relation));

        assertEquals("""
                [1]【文档片段】
                订单服务依赖库存服务

                [2]【图谱关系·推断】
                订单服务——影响——>库存服务""", rendered);
    }

    @Test
    void marksRelationsWithoutPreciseDocumentProvenanceAsGeneralized() {
        Content relation = content("微服务——通信——>网关", Map.of(
                RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.GRAPH_RELATION,
                GraphStoreContentRetriever.GRAPH_PROVENANCE_AMBIGUOUS, "true"));

        assertEquals("[1]【图谱关系·泛化】\n微服务——通信——>网关",
                LabeledEvidenceFormatter.format(List.of(relation)));
    }

    @Test
    void disabledLabelingKeepsPlainJoinedText() {
        Content one = content("first chunk", Map.of());
        Content two = content("second chunk", Map.of(
                RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.GRAPH_RELATION));

        assertEquals("first chunk\nsecond chunk",
                LabeledEvidenceFormatter.format(false, List.of(one, two)));
        assertEquals("", LabeledEvidenceFormatter.format(false, List.of()));
    }
}
