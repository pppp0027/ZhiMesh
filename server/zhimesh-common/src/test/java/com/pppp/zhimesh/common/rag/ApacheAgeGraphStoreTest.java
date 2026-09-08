package com.pppp.zhimesh.common.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.vo.GraphEdgeSearch;
import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import com.pppp.zhimesh.common.vo.GraphVertexSearch;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void searchVerticesSqlFiltersBeforeSorting() {
        GraphVertexSearch search = GraphVertexSearch.builder()
                .names(List.of("知识图谱"))
                .metadataFilter(new IsEqualTo("kb_uuid", "kb-123"))
                .label("Entity")
                .limit(5)
                .build();

        String sql = ApacheAgeGraphStore.buildSearchVerticesSql("adi_knowledge_base_graph", search);

        // Filtering commutes with sorting on the unique id(v) total order, so
        // putting WHERE first is result-identical while letting PG skip the
        // full-graph sort.
        assertTrue(sql.indexOf("where") < sql.indexOf("order by id(v) desc"),
                "filter must run before sort: " + sql);
        assertTrue(sql.contains("id(v) < 9223372036854775807"), "cursor fence must survive");
        assertTrue(sql.contains("match (v:Entity)"));
        assertTrue(sql.contains("limit 5"));
        assertTrue(sql.contains("$v_name_0"));
        assertTrue(sql.contains("$v_metadata_kb_uuid_0"));
        assertFalse(sql.contains("kb-123"), "kb_uuid must only travel as a parameter");
    }

    @Test
    void searchEdgesSqlFiltersBeforeSortingAndMergesAliasArgs() {
        GraphEdgeSearch search = GraphEdgeSearch.builder()
                .source(GraphSearchCondition.builder()
                        .metadataFilter(new IsEqualTo("kb_uuid", "kb-123"))
                        .build())
                .target(GraphSearchCondition.builder()
                        .names(List.of("辰海矩阵"))
                        .build())
                .build();

        String sql = ApacheAgeGraphStore.buildSearchEdgesSql("adi_knowledge_base_graph", search);

        assertTrue(sql.indexOf("where") < sql.indexOf("order by id(e) desc"),
                "filter must run before sort: " + sql);
        assertTrue(sql.contains("id(e) < 9223372036854775807"));
        assertFalse(sql.contains("kb-123"));

        Map<String, Object> args = ApacheAgeGraphStore.buildEdgeWhereArgs(search);
        assertEquals("kb-123", args.get("v1_metadata_kb_uuid_0"));
        assertEquals("辰海矩阵", args.get("v2_name_0"));
    }

    @Test
    void setupConnectionPrefersPooledDataSource() throws Exception {
        Connection pooled = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(pooled.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(pooled);

        // createGraph/dropGraphFirst off keeps the constructor from issuing
        // statements, so the store can be built against a stub DataSource.
        ApacheAgeGraphStore store = ApacheAgeGraphStore.builder()
                .host("localhost")
                .port(5432)
                .user("test")
                .password("test")
                .database("postgres")
                .graphName("adi_knowledge_base_graph")
                .createGraph(false)
                .dropGraphFirst(false)
                .dataSource(dataSource)
                .build();

        assertSame(pooled, store.setupConnection());
        // Once for the constructor's init connection, once for the explicit call.
        verify(dataSource, atLeast(2)).getConnection();
    }
}
