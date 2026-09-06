package com.pppp.zhimesh.common.rag.bm25;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Bm25ContentRetrieverTest {

    @Mock
    private Bm25Repository repository;
    @Mock
    private Bm25ReadinessService readinessService;

    private Bm25Tokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new Bm25Tokenizer("test-analyzer-v1");
    }

    @Test
    void emptyQueryReturnsEmptyWithoutCheckingReadiness() {
        Bm25ContentRetriever retriever = retriever(Set.of("kb-a"), 5, 16);
        Query blankQuery = mock(Query.class);
        when(blankQuery.text()).thenReturn("   ");

        assertEquals(List.of(), retriever.retrieve(null));
        assertEquals(List.of(), retriever.retrieve(blankQuery));
        verifyNoInteractions(readinessService, repository);
    }

    @Test
    void missingAuthorizedScopeFailsClosed() {
        Bm25ContentRetriever retriever = retriever(Set.of(), 5, 16);

        assertThrows(Bm25UnavailableException.class,
                () -> retriever.retrieve(Query.from("knowledge retrieval")));
        verifyNoInteractions(readinessService, repository);
    }

    @Test
    void lowInformationOnlyQueryDoesNotSearchRepository() {
        Bm25ContentRetriever retriever = retriever(Set.of("kb-a"), 5, 16);

        assertEquals(List.of(), retriever.retrieve(Query.from("是谁？")));
        assertEquals(List.of(), retriever.getRetrievedTerms());
        verifyNoInteractions(readinessService, repository);
    }

    @Test
    void incompleteReadinessFailsBeforeSearching() {
        Bm25ContentRetriever retriever = retriever(Set.of("kb-a", "kb-b"), 5, 16);
        doThrow(new Bm25UnavailableException("kb-b is not ready"))
                .when(readinessService).requireReady(Set.of("kb-a", "kb-b"));

        assertThrows(Bm25UnavailableException.class,
                () -> retriever.retrieve(Query.from("knowledge retrieval")));
        verifyNoInteractions(repository);
    }

    @Test
    void returnsRankedCanonicalChunksWithBm25Provenance() {
        Bm25ContentRetriever retriever = retriever(Set.of("kb-a"), 2, 3);
        when(repository.search(
                eq(Set.of("kb-a")),
                eq(List.of("getusername", "get", "user")),
                eq("test-analyzer-v1"), eq(1.2D), eq(0.75D), eq(2),
                eq(0.85D), eq(20)))
                .thenReturn(List.of(
                        new Bm25SearchHit("chunk-1", "build-1", "kb-a", "item-1",
                                "set-1", "first canonical chunk", 4.5D),
                        new Bm25SearchHit("chunk-2", "build-1", "kb-a", "item-1",
                                "set-1", "second canonical chunk", 3.25D)));

        List<Content> contents = retriever.retrieve(Query.from("getUserName"));

        assertEquals(List.of("first canonical chunk", "second canonical chunk"),
                contents.stream().map(Content::textSegment).map(segment -> segment.text()).toList());
        Map<String, Object> first = contents.get(0).textSegment().metadata().toMap();
        assertEquals("original_segment", first.get("content_type"));
        assertEquals("kb-a", first.get("kb_uuid"));
        assertEquals("item-1", first.get("kb_item_uuid"));
        assertEquals("chunk-1", first.get(Bm25ContentRetriever.CHUNK_UUID));
        assertEquals("set-1", first.get(Bm25ContentRetriever.CHUNK_SET_UUID));
        assertEquals("build-1", first.get(Bm25ContentRetriever.INDEX_BUILD_UUID));
        assertEquals("item-1", first.get(Bm25ContentRetriever.SOURCE_DOCUMENT_IDS));
        assertEquals("chunk-1", first.get(Bm25ContentRetriever.SOURCE_SEGMENT_IDS));
        assertEquals(4.5D, first.get(Bm25ContentRetriever.BM25_SCORE));
        assertEquals(4.5D, first.get(Bm25ContentRetriever.RAW_SCORE));
        assertEquals(1, first.get(Bm25ContentRetriever.BM25_RANK));
        assertEquals(2, contents.get(1).textSegment().metadata().toMap()
                .get(Bm25ContentRetriever.BM25_RANK));
        assertEquals(List.of("getusername", "get", "user"), retriever.getRetrievedTerms());
        assertEquals(List.of("chunk-1", "chunk-2"), retriever.getRetrievedHits().stream()
                .map(Bm25ContentRetriever.Bm25RetrievedHit::chunkUuid).toList());
        assertEquals(List.of(1, 2), retriever.getRetrievedHits().stream()
                .map(Bm25ContentRetriever.Bm25RetrievedHit::rank).toList());
        verify(readinessService).requireReady(Set.of("kb-a"));
    }

    private Bm25ContentRetriever retriever(Set<String> scope, int maxResults, int maxQueryTerms) {
        return new Bm25ContentRetriever(repository, tokenizer, readinessService, scope,
                maxResults, maxQueryTerms, 1.2D, 0.75D);
    }
}
