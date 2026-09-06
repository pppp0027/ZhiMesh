package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphElementSourceService;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphSegmentService;
import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GraphRagCleanupTest {

    @Test
    void cleanupKnowledgeBaseRemovesScopedGraphProvenanceAndSegments() {
        GraphStore graphStore = mock(GraphStore.class);
        RecordingElementSourceService elementSourceService = new RecordingElementSourceService();
        RecordingSegmentService segmentService = new RecordingSegmentService();
        GraphRag graphRag = new GraphRag("test", graphStore,
                elementSourceService, segmentService);

        graphRag.cleanupKnowledgeBase("kb-1");

        ArgumentCaptor<GraphSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(GraphSearchCondition.class);
        verify(graphStore).deleteVertices(conditionCaptor.capture(), eq(true));
        GraphSearchCondition condition = conditionCaptor.getValue();
        // One scoped DETACH DELETE by kb_uuid: edges fall with their vertices.
        assertNull(condition.getNames());
        IsEqualTo kbFilter = (IsEqualTo) condition.getMetadataFilter();
        assertEquals(ZhiMeshConstant.MetadataKey.KB_UUID, kbFilter.key());
        assertEquals("kb-1", kbFilter.comparisonValue());

        assertEquals(List.of("kb-1"), elementSourceService.removedKbUuids);
        assertEquals(List.of("kb-1"), segmentService.removedKbUuids);
    }

    @Test
    void cleanupKnowledgeBaseRemovesGraphBeforeDroppingProvenance() {
        GraphStore graphStore = mock(GraphStore.class);
        List<String> events = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            events.add("deleteVertices");
            return null;
        }).when(graphStore).deleteVertices(
                org.mockito.ArgumentMatchers.any(GraphSearchCondition.class), eq(true));
        RecordingEventSourceService elementSourceService = new RecordingEventSourceService(events);
        RecordingEventSegmentService segmentService = new RecordingEventSegmentService(events);
        GraphRag graphRag = new GraphRag("test", graphStore,
                elementSourceService, segmentService);

        graphRag.cleanupKnowledgeBase("kb-2");

        // The physical deletion must precede the provenance removal, otherwise
        // a failure in between loses the row list needed to retry the cleanup.
        assertEquals(List.of("deleteVertices", "removeSources", "removeSegments"), events);
    }

    private static final class RecordingElementSourceService
            extends KnowledgeBaseGraphElementSourceService {
        private final List<String> removedKbUuids = new ArrayList<>();

        @Override
        public void removeKnowledgeBaseSources(String kbUuid) {
            removedKbUuids.add(kbUuid);
        }
    }

    private static final class RecordingSegmentService extends KnowledgeBaseGraphSegmentService {
        private final List<String> removedKbUuids = new ArrayList<>();

        @Override
        public void removeKnowledgeBaseSegments(String kbUuid) {
            removedKbUuids.add(kbUuid);
        }
    }

    private static final class RecordingEventSourceService
            extends KnowledgeBaseGraphElementSourceService {
        private final List<String> events;

        private RecordingEventSourceService(List<String> events) {
            this.events = events;
        }

        @Override
        public void removeKnowledgeBaseSources(String kbUuid) {
            events.add("removeSources");
        }
    }

    private static final class RecordingEventSegmentService
            extends KnowledgeBaseGraphSegmentService {
        private final List<String> events;

        private RecordingEventSegmentService(List<String> events) {
            this.events = events;
        }

        @Override
        public void removeKnowledgeBaseSegments(String kbUuid) {
            events.add("removeSegments");
        }
    }
}
