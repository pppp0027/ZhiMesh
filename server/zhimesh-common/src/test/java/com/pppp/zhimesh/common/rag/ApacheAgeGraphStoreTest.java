package com.pppp.zhimesh.common.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.vo.GraphEdgeSearch;
import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApacheAgeGraphStoreTest {

    @Test
    void buildsMutableArgumentsForTargetOnlyEdgeSearch() {
        GraphSearchCondition target = GraphSearchCondition.builder()
                .names(List.of("辰海矩阵"))
                .build();
        GraphEdgeSearch search = GraphEdgeSearch.builder()
                .target(target)
                .build();

        Map<String, Object> arguments = ApacheAgeGraphStore.buildEdgeWhereArgs(search);
        arguments.put("extra", "value");

        assertNull(search.getSource());
        assertEquals("辰海矩阵", arguments.get("v2_name_0"));
        assertEquals("value", arguments.get("extra"));
    }

    @Test
    void serializesGraphNativeIdsAsIntegralAgeParameters() {
        long vertexId = 844424930132539L;
        long edgeId = 1125899906842631L;
        long sourceId = 844424930132488L;
        long targetId = 281474976710661L;
        Map<String, Object> arguments = Map.of(
                "vertex_id", vertexId,
                "edge_id", edgeId,
                "source_id", sourceId,
                "target_id", targetId,
                "metadata", Map.of("browser_facing_long", vertexId));

        String json = ApacheAgeGraphStore.toAgeParameterJson(
                arguments, "vertex_id", "edge_id", "source_id", "target_id");
        JsonNode parameters = JsonUtil.toJsonNode(json);

        assertTrue(parameters.path("vertex_id").isIntegralNumber());
        assertEquals(vertexId, parameters.path("vertex_id").longValue());
        assertTrue(parameters.path("edge_id").isIntegralNumber());
        assertEquals(edgeId, parameters.path("edge_id").longValue());
        assertTrue(parameters.path("source_id").isIntegralNumber());
        assertEquals(sourceId, parameters.path("source_id").longValue());
        assertTrue(parameters.path("target_id").isIntegralNumber());
        assertEquals(targetId, parameters.path("target_id").longValue());
        // Non-ID values retain the application's existing serialization contract.
        assertTrue(parameters.path("metadata").path("browser_facing_long").isTextual());
    }

    @Test
    void rejectsNonNumericGraphNativeIdParameters() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ApacheAgeGraphStore.toAgeParameterJson(
                        Map.of("vertex_id", "844424930132539"), "vertex_id"));

        assertTrue(exception.getMessage().contains("vertex_id"));
    }
}
