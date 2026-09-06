package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.service.CanonicalChunkSnapshot;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Bm25IndexServiceTest {

    @Mock
    private Bm25Repository repository;
    @Mock
    private Bm25ReadinessService readinessService;

    private Bm25IndexService service;

    @BeforeEach
    void setUp() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getRetrieval().getBm25().setEnabled(true);
        properties.getRetrieval().getBm25().setAnalyzerVersion("test-analyzer-v1");
        service = new Bm25IndexService(repository, new Bm25Tokenizer("test-analyzer-v1"),
                readinessService, properties);
    }

    @Test
    void rebuildPublishesDocumentsAndPostingsInOneOrderedRepositoryFlow() {
        KnowledgeBaseItem item = item();
        CanonicalChunkSnapshot snapshot = snapshot(2);
        when(repository.lockItemForUpdate("item-1")).thenReturn(Optional.of(item));
        when(repository.findActiveBuild(eq("item-1"), eq("set-1"),
                eq("test-analyzer-v1"), anyString())).thenReturn(Optional.empty());

        ArgumentCaptor<Bm25BuildRecord> buildCaptor = ArgumentCaptor.forClass(Bm25BuildRecord.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Bm25DocumentRecord>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Bm25PostingRecord>> postingsCaptor = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(repository);

        Bm25IndexBuildResult result = service.rebuild(item, snapshot);

        order.verify(repository).lockItemForUpdate("item-1");
        order.verify(repository).findActiveBuild(eq("item-1"), eq("set-1"),
                eq("test-analyzer-v1"), anyString());
        order.verify(repository).createBuildingIndex(buildCaptor.capture());
        order.verify(repository).deletePhysicalIndexForItem("item-1");
        order.verify(repository).insertDocuments(documentsCaptor.capture());
        order.verify(repository).insertPostings(postingsCaptor.capture());
        order.verify(repository).activateBuild(eq("item-1"), eq(result.indexBuildUuid()),
                eq("set-1"), any());

        Bm25BuildRecord build = buildCaptor.getValue();
        assertEquals(result.indexBuildUuid(), build.uuid());
        assertEquals("set-1", build.chunkSetUuid());
        assertEquals("test-analyzer-v1", build.analyzerVersion());
        assertEquals(64, build.buildKeyHash().length());

        List<Bm25DocumentRecord> documents = documentsCaptor.getValue();
        assertEquals(List.of("chunk-0", "chunk-1"),
                documents.stream().map(Bm25DocumentRecord::chunkUuid).toList());
        assertEquals(List.of(2, 1),
                documents.stream().map(Bm25DocumentRecord::documentLength).toList());
        assertTrue(documents.stream().allMatch(document ->
                result.indexBuildUuid().equals(document.indexBuildUuid())));

        List<Bm25PostingRecord> postings = postingsCaptor.getValue();
        assertEquals(Set.of("alpha", "beta"),
                postings.stream().map(Bm25PostingRecord::term).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, postings.stream().filter(posting -> posting.term().equals("alpha"))
                .findFirst().orElseThrow().termFrequency());
        assertEquals(2, result.documentCount());
        assertEquals(2, result.postingCount());
    }

    @Test
    void sameActiveBuildIsIdempotent() {
        KnowledgeBaseItem item = item();
        CanonicalChunkSnapshot snapshot = snapshot(2);
        when(repository.lockItemForUpdate("item-1")).thenReturn(Optional.of(item));
        when(repository.findActiveBuild(eq("item-1"), eq("set-1"),
                eq("test-analyzer-v1"), anyString()))
                .thenReturn(Optional.of(new ActiveBm25Build("existing-build")));
        when(repository.countDocumentsForBuild("existing-build")).thenReturn(2);

        Bm25IndexBuildResult result = service.rebuild(item, snapshot);

        assertEquals("existing-build", result.indexBuildUuid());
        assertEquals(2, result.documentCount());
        assertEquals(0, result.postingCount());
        verify(repository, never()).createBuildingIndex(any());
        verify(repository, never()).deletePhysicalIndexForItem(anyString());
        verify(repository, never()).insertDocuments(any());
        verify(repository, never()).insertPostings(any());
        verify(repository, never()).activateBuild(anyString(), anyString(), anyString(), any());
    }

    @Test
    void incompleteActiveBuildIsSupersededAndRebuilt() {
        KnowledgeBaseItem item = item();
        CanonicalChunkSnapshot snapshot = snapshot(2);
        when(repository.lockItemForUpdate("item-1")).thenReturn(Optional.of(item));
        when(repository.findActiveBuild(eq("item-1"), eq("set-1"),
                eq("test-analyzer-v1"), anyString()))
                .thenReturn(Optional.of(new ActiveBm25Build("incomplete-build")));
        when(repository.countDocumentsForBuild("incomplete-build")).thenReturn(1);

        Bm25IndexBuildResult result = service.rebuild(item, snapshot);

        verify(repository).supersedeBuild("item-1", "incomplete-build");
        verify(repository).createBuildingIndex(any());
        verify(repository).activateBuild(eq("item-1"), eq(result.indexBuildUuid()),
                eq("set-1"), any());
        assertFalse("incomplete-build".equals(result.indexBuildUuid()));
        assertEquals(2, result.documentCount());
    }

    @Test
    void rejectsSnapshotWhoseDeclaredChunkCountDoesNotMatch() {
        CanonicalChunkSnapshot snapshot = snapshot(3);
        when(repository.lockItemForUpdate("item-1")).thenReturn(Optional.of(item()));

        assertThrows(IllegalArgumentException.class, () -> service.rebuild(item(), snapshot));
        verify(repository).lockItemForUpdate("item-1");
        verify(repository, never()).findActiveBuild(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void convenienceRebuildLoadsOwningItem() {
        KnowledgeBaseItem item = item();
        CanonicalChunkSnapshot snapshot = snapshot(2);
        when(repository.lockItemForUpdate("item-1")).thenReturn(Optional.of(item));
        when(repository.findActiveBuild(eq("item-1"), eq("set-1"),
                eq("test-analyzer-v1"), anyString()))
                .thenReturn(Optional.of(new ActiveBm25Build("existing-build")));
        when(repository.countDocumentsForBuild("existing-build")).thenReturn(2);

        service.rebuild(snapshot);

        verify(repository).lockItemForUpdate("item-1");
        verify(repository).countDocumentsForBuild("existing-build");
    }

    @Test
    void deleteTrimsAndDelegatesItemUuid() {
        service.deleteByItemUuid("  item-1  ");

        verify(repository).lockItemForUpdate("item-1");
        verify(repository).deleteItemIndex("item-1");
        assertThrows(IllegalArgumentException.class, () -> service.deleteByItemUuid("  "));
    }

    @Test
    void readinessRequiresEveryRequestedKnowledgeBase() {
        when(readinessService.readyKnowledgeBases(Set.of("kb-a", "kb-b")))
                .thenReturn(Set.of("kb-a", "kb-b"), Set.of("kb-a"));

        assertTrue(service.isReady(Set.of("kb-a", "kb-b")));
        assertFalse(service.isReady(Set.of("kb-a", "kb-b")));
        assertFalse(service.isReady(Set.of()));
    }

    private static KnowledgeBaseItem item() {
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setId(11L);
        item.setKbId(7L);
        item.setKbUuid("kb-a");
        item.setUuid("item-1");
        item.setActiveChunkSetUuid("set-1");
        return item;
    }

    private static CanonicalChunkSnapshot snapshot(int declaredChunkCount) {
        KnowledgeBaseChunkSet chunkSet = new KnowledgeBaseChunkSet();
        chunkSet.setUuid("set-1");
        chunkSet.setKbId(7L);
        chunkSet.setKbUuid("kb-a");
        chunkSet.setKbItemId(11L);
        chunkSet.setKbItemUuid("item-1");
        chunkSet.setChunkCount(declaredChunkCount);
        chunkSet.setStatus("ACTIVE");
        chunkSet.setIsActive(true);

        KnowledgeBaseChunk first = chunk("chunk-0", 0, "alpha alpha");
        KnowledgeBaseChunk second = chunk("chunk-1", 1, "beta");
        // Deliberately reverse the input; the index must use canonical chunk order.
        return new CanonicalChunkSnapshot(chunkSet, List.of(second, first),
                List.of(TextSegment.from("beta"), TextSegment.from("alpha alpha")));
    }

    private static KnowledgeBaseChunk chunk(String uuid, int index, String content) {
        KnowledgeBaseChunk chunk = new KnowledgeBaseChunk();
        chunk.setUuid(uuid);
        chunk.setChunkSetUuid("set-1");
        chunk.setKbId(7L);
        chunk.setKbUuid("kb-a");
        chunk.setKbItemId(11L);
        chunk.setKbItemUuid("item-1");
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunk;
    }
}
