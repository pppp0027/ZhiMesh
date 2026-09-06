package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEvidenceOrderingTest {

    @Test
    void scoresRelationsAndSourcesTogetherWhileKeepingFourOriginalChunks() {
        List<Content> sources = List.of(
                content("source-a-1", "doc-a", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("source-a-2", "doc-a", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("source-a-3", "doc-a", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("source-b-1", "doc-b", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("source-c-1", "doc-c", RetrievedCandidate.ORIGINAL_SEGMENT));
        List<Content> relations = List.of(
                content("Microsoft invested in OpenAI", "doc-b", RetrievedCandidate.GRAPH_RELATION),
                content("OpenAI uses Azure", "doc-c", RetrievedCandidate.GRAPH_RELATION),
                content("Microsoft supplies infrastructure", "doc-a", RetrievedCandidate.GRAPH_RELATION));

        List<Content> ordered = GraphStoreContentRetriever.orderEvidence(
                "What is the relationship between Microsoft and OpenAI?",
                sources, relations, 8, 5, 2);
        List<Content> topFive = ordered.stream().limit(5).toList();

        assertTrue(topFive.stream().filter(GraphEvidenceOrderingTest::isRelation).count() <= 1);
        assertTrue(topFive.stream().filter(content -> !isRelation(content)).count() >= 4);
        assertTrue(topFive.stream().anyMatch(GraphEvidenceOrderingTest::isRelation));
    }

    @Test
    void penalizesAmbiguousRelationComparedWithEquallyRelevantOriginalChunk() {
        Content original = content("Microsoft invested in OpenAI", "doc-a",
                RetrievedCandidate.ORIGINAL_SEGMENT);
        Content ambiguousRelation = Content.from(TextSegment.from(
                "Microsoft invested in OpenAI",
                new Metadata(Map.of(
                        RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.GRAPH_RELATION,
                        GraphStoreContentRetriever.GRAPH_SOURCE_DOCUMENT_COUNT, 20))));

        List<Content> ordered = GraphStoreContentRetriever.orderEvidence(
                "Microsoft invested in OpenAI",
                List.of(original), List.of(ambiguousRelation), 2, 2, 1);

        assertEquals(original, ordered.get(0));
    }

    @Test
    void preservesMultipleDocumentsBeforeRefillingFromOneDocument() {
        List<Content> sources = List.of(
                content("alpha relevant evidence one", "doc-a", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("alpha relevant evidence two", "doc-a", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("alpha relevant evidence three", "doc-a", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("beta evidence", "doc-b", RetrievedCandidate.ORIGINAL_SEGMENT),
                content("gamma evidence", "doc-c", RetrievedCandidate.ORIGINAL_SEGMENT));

        List<Content> ranked = GraphStoreContentRetriever.rankWithDocumentDiversity(
                "alpha relevant evidence", sources, 5, 2);
        List<String> documents = ranked.stream()
                .map(content -> content.textSegment().metadata()
                        .getString(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID))
                .toList();

        assertTrue(documents.indexOf("doc-b") < documents.lastIndexOf("doc-a"));
        assertTrue(documents.indexOf("doc-c") < documents.lastIndexOf("doc-a"));
    }

    private static boolean isRelation(Content content) {
        return RetrievedCandidate.GRAPH_RELATION.equals(
                content.textSegment().metadata().getString(RetrievedCandidate.CONTENT_TYPE));
    }

    private static Content content(String text, String documentId, String type) {
        return Content.from(TextSegment.from(text, new Metadata(Map.of(
                ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, documentId,
                RetrievedCandidate.CONTENT_TYPE, type))));
    }
}
