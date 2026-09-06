package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphElementSourceService;
import com.pppp.zhimesh.common.vo.GraphEdge;
import com.pppp.zhimesh.common.vo.GraphVertex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphKnowledgeBaseScopeTest {

    @Test
    void collectsEveryKnowledgeBaseFromGraphEvidence() {
        GraphEdge edge = GraphEdge.builder().metadata(Map.of(
                ZhiMeshConstant.MetadataKey.KB_UUID, "kb-a,kb-b")).build();
        GraphVertex vertex = GraphVertex.builder().metadata(Map.of(
                ZhiMeshConstant.MetadataKey.KB_UUID, "kb-c")).build();

        assertEquals(Set.of("kb-a", "kb-b", "kb-c"),
                GraphStoreContentRetriever.findKbUuids(List.of(vertex), List.of(edge)));
    }

    @Test
    void multiKnowledgeBaseResolutionFailsClosedWithoutScope() {
        KnowledgeBaseGraphElementSourceService service = new KnowledgeBaseGraphElementSourceService();

        assertEquals(List.of(), service.sourceSegmentUuidsForKnowledgeBases(
                Set.of(), List.of("edge-1"), List.of("vertex-1"), 10, 2));
    }
}
