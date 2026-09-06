package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.vo.*;
import org.apache.commons.lang3.tuple.Triple;

import java.util.List;

public interface GraphStore {
    boolean addVertexes(List<GraphVertex> vertexes);

    boolean addVertex(GraphVertex vertex);

    GraphVertex updateVertex(GraphVertexUpdateInfo updateInfo);

    /** Update exactly one vertex without relying on an ambiguous name/type lookup. */
    GraphVertex updateVertexById(String id, GraphVertex newData);

    /** Resolve exactly one persisted vertex by its graph-native identity. */
    GraphVertex getVertexById(String id);

    GraphVertex getVertex(GraphVertexSearch search);

    List<GraphVertex> getVertices(List<String> ids);

    List<GraphVertex> searchVertices(GraphVertexSearch search);

    List<Triple<GraphVertex, GraphEdge, GraphVertex>> getEdges(List<String> ids);

    List<Triple<GraphVertex, GraphEdge, GraphVertex>> searchEdges(GraphEdgeSearch search);

    Triple<GraphVertex, GraphEdge, GraphVertex> getEdge(GraphEdgeSearch search);

    Triple<GraphVertex, GraphEdge, GraphVertex> addEdge(GraphEdgeAddInfo addInfo);

    /** Resolve and create relationships by the already-selected endpoint identities. */
    default Triple<GraphVertex, GraphEdge, GraphVertex> getEdgeByVertexIds(String sourceId,
                                                                          String targetId) {
        return getEdgeByVertexIds(sourceId, targetId, null, null);
    }

    /**
     * Resolve one directed edge by its semantic identity. Different predicates
     * or polarities between the same endpoints must remain separate edges.
     */
    Triple<GraphVertex, GraphEdge, GraphVertex> getEdgeByVertexIds(
            String sourceId, String targetId, String relationType, Boolean polarity);

    Triple<GraphVertex, GraphEdge, GraphVertex> addEdgeByVertexIds(String sourceId, String targetId,
                                                                  GraphEdge edge);

    Triple<GraphVertex, GraphEdge, GraphVertex> updateEdge(GraphEdgeEditInfo edgeEditInfo);

    Triple<GraphVertex, GraphEdge, GraphVertex> updateEdgeById(String edgeId, GraphEdge edge);

    void deleteVertices(GraphSearchCondition filter, boolean includeEdges);

    void deleteEdges(GraphSearchCondition filter);

    /** Delete the exact vertices identified by AGE graph ids. */
    void deleteVerticesByIds(List<String> ids);

    /** Delete the exact edges identified by AGE graph ids. */
    void deleteEdgesByIds(List<String> ids);
}
