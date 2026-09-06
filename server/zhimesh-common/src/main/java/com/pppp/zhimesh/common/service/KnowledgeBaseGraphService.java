package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.rag.GraphStore;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

@Service
public class KnowledgeBaseGraphService {

    @Resource
    private GraphStore kbGraphStore;

    @Resource
    private KnowledgeBaseGraphElementSourceService knowledgeBaseGraphElementSourceService;

    public List<GraphVertex> listVerticesByKbUuid(String kbUuid, long maxId, int limit) {
        Filter filter = new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid);
        return kbGraphStore.searchVertices(GraphVertexSearch.builder()
                .metadataFilter(filter)
                .maxId(maxId)
                .limit(limit)
                .build());
    }

    public List<GraphVertex> listVerticesByKbItemUuid(String kbItemUuid, long maxId, int limit) {
        List<String> ids = knowledgeBaseGraphElementSourceService.elementIds(kbItemUuid, KnowledgeBaseGraphElementSourceService.VERTEX);
        if (!ids.isEmpty()) return kbGraphStore.getVertices(ids).stream().filter(v -> Long.parseLong(v.getId()) < maxId).limit(limit).toList();
        if (knowledgeBaseGraphElementSourceService.hasSources(kbItemUuid)) return Collections.emptyList();
        Filter filter = new GraphContains(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, kbItemUuid);
        return kbGraphStore.searchVertices(
                GraphVertexSearch.builder()
                        .limit(limit)
                        .maxId(maxId)
                        .metadataFilter(filter)
                        .build()
        );
    }

    public List<Triple<GraphVertex, GraphEdge, GraphVertex>> listEdgesByKbUuid(String kbUuid, long maxId, int limit) {
        Filter filter = new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid);
        return kbGraphStore.searchEdges(GraphEdgeSearch.builder()
                .edge(GraphSearchCondition.builder().metadataFilter(filter).build())
                .maxId(maxId)
                .limit(limit)
                .build());
    }

    public List<Triple<GraphVertex, GraphEdge, GraphVertex>> listEdgesByKbItemUuid(String kbItemUuid, long maxId, int limit) {
        List<String> ids = knowledgeBaseGraphElementSourceService.elementIds(kbItemUuid, KnowledgeBaseGraphElementSourceService.EDGE);
        if (!ids.isEmpty()) return kbGraphStore.getEdges(ids).stream().filter(e -> Long.parseLong(e.getMiddle().getId()) < maxId).limit(limit).toList();
        if (knowledgeBaseGraphElementSourceService.hasSources(kbItemUuid)) return Collections.emptyList();
        Filter filter = new GraphContains(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, kbItemUuid);
        return kbGraphStore.searchEdges(GraphEdgeSearch.builder()
                .edge(GraphSearchCondition.builder().metadataFilter(filter).build())
                .maxId(maxId)
                .limit(limit)
                .build());
    }

    public Pair<List<GraphVertex>, List<GraphEdge>> getFromTriple(List<Triple<GraphVertex, GraphEdge, GraphVertex>> triples) {
        List<GraphVertex> vertices = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (Triple<GraphVertex, GraphEdge, GraphVertex> triple : triples) {
            vertices.add(triple.getLeft());
            vertices.add(triple.getRight());
            edges.add(triple.getMiddle());
        }
        return Pair.of(vertices, edges);
    }
}
