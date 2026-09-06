package com.pppp.zhimesh.common.rag;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.GraphStoreUtil;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.vo.*;
import lombok.Builder;
import org.apache.age.jdbc.base.Agtype;
import org.apache.age.jdbc.base.type.AgtypeMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.postgresql.jdbc.PgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pppp.zhimesh.common.enums.ErrorEnum.B_DB_ERROR;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.*;

public class ApacheAgeGraphStore implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(ApacheAgeGraphStore.class);

    private final String host;
    private final Integer port;
    private final String user;
    private final String password;
    private final String database;
    private final String graph;

    @Builder
    public ApacheAgeGraphStore(String host,
                               Integer port,
                               String user,
                               String password,
                               String database,
                               String graphName,
                               Boolean createGraph,
                               Boolean dropGraphFirst) {
        this.host = ensureNotBlank(host, "host");
        this.port = ensureGreaterThanZero(port, "port");
        this.user = ensureNotBlank(user, "user");
        this.password = ensureNotBlank(password, "password");
        this.database = ensureNotBlank(database, "database");
        if (!graphName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("graphName must contain only alphanumeric characters and underscores");
        }
        this.graph = graphName;

        createGraph = getOrDefault(createGraph, true);
        dropGraphFirst = getOrDefault(dropGraphFirst, false);

        try (Connection connection = setupConnection();
             Statement stmt = connection.createStatement()) {
            if (Boolean.TRUE.equals(dropGraphFirst)) {
                stmt.executeUpdate(String.format("SELECT * FROM drop_graph('%s', true)", graph));
            }
            if (Boolean.TRUE.equals(createGraph)) {
                ResultSet resultSet = stmt.executeQuery(String.format("SELECT * FROM ag_graph WHERE name = '%s'", graph));
                if (!resultSet.isBeforeFirst() && resultSet.getRow() == 0) {
                    stmt.execute(String.format("SELECT * FROM ag_catalog.create_graph('%s')", graph));
                }
            }
        } catch (SQLException e) {
            log.error("ApacheAgeGraphStore init error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public boolean addVertexes(List<GraphVertex> vertexes) {
        ensureNotEmpty(vertexes, vertexes.toString());
        try (Connection connection = setupConnection()) {
            for (GraphVertex vertex : vertexes) {
                String label = vertex.getLabel();
                String prepareSql = """
                        SELECT *
                        FROM cypher('%s', $$
                            create (%s {name:$name,canonical_name:$canonical_name,
                                aliases_json:$aliases_json,properties_json:$properties_json,
                                salience:$salience,text_segment_id:$text_segment_id,
                                description:$description,metadata:$metadata})
                        $$, ?) as (a agtype);
                        """.formatted(graph, StringUtils.isNotBlank(label) ? ":" + label : "");
                log.info("addVertex prepareSql:{}", prepareSql);
                try (PreparedStatement upsertStmt = connection.prepareStatement(prepareSql)) {
                    Map<String, Object> args = new HashMap<>();
                    args.put("name", vertex.getName());
                    args.put("canonical_name", StringUtils.defaultIfBlank(
                            vertex.getCanonicalName(), vertex.getName()));
                    args.put("aliases_json", JsonUtil.toJson(
                            vertex.getAliases() == null ? List.of() : vertex.getAliases()));
                    args.put("properties_json", JsonUtil.toJson(
                            vertex.getProperties() == null ? Map.of() : vertex.getProperties()));
                    args.put("salience", vertex.getSalience() == null ? 5D : vertex.getSalience());
                    args.put("text_segment_id", StringUtils.defaultString(vertex.getTextSegmentId()));
                    args.put("description", StringUtils.defaultString(vertex.getDescription()));
                    args.put("metadata", vertex.getMetadata() == null ? Map.of() : vertex.getMetadata());
                    Agtype agtype = new Agtype();
                    agtype.setValue(JsonUtil.toJson(args));
                    upsertStmt.setObject(1, agtype);
                    upsertStmt.execute();
                }
            }
        } catch (SQLException e) {
            log.error("addVertex error", e);
            throw new BaseException(B_DB_ERROR);
        }
        return true;
    }

    @Override
    public boolean addVertex(GraphVertex vertex) {
        log.info("Add vertex:{}", vertex);
        ensureNotNull(vertex, vertex.toString());
        ensureNotEmpty(vertex.getMetadata(), "Metadata");
        return addVertexes(List.of(vertex));
    }

    /**
     * Update vertex
     *
     * @param updateInfo
     * @return
     */
    @Override
    public GraphVertex updateVertex(GraphVertexUpdateInfo updateInfo) {
        log.info("Update vertex:{}", updateInfo.getNewData());
        ensureNotNull(updateInfo.getMetadataFilter(), "Metadata filter");
        GraphVertex newData = updateInfo.getNewData();
        ensureNotNull(newData, newData.toString());

        try (Connection connection = setupConnection()) {

            GraphSearchCondition whereCondition = GraphSearchCondition.builder()
                    .names(List.of(updateInfo.getName()))
                    .metadataFilter(updateInfo.getMetadataFilter())
                    .build();
            String whereClause = GraphStoreUtil.buildWhereClause(whereCondition, "v");
            String setClause = GraphStoreUtil.buildSetClause(updateInfo.getNewData().getMetadata());
            String prepareSql = """
                    select * from cypher('%s', $$
                       match (v)
                       where %s
                       set v.text_segment_id=$new_text_segment_id,v.description=$new_description%s
                       return v
                       limit 1
                    $$, ?) as (v agtype);
                    """.formatted(graph, whereClause, setClause);
            log.info("updateVertex prepareSql:{}", prepareSql);
            try (PreparedStatement stmt = connection.prepareStatement(prepareSql)) {
                Map<String, Object> whereArgs = GraphStoreUtil.buildWhereArgs(whereCondition, "v");
                Map<String, Object> setArgs = GraphStoreUtil.buildSetArgs(updateInfo.getNewData().getMetadata());
                whereArgs.putAll(setArgs);
                whereArgs.putAll(Map.of("new_text_segment_id", newData.getTextSegmentId(), "new_description", newData.getDescription()));
                log.info("updateVertex args:{}", whereArgs);

                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs));
                stmt.setObject(1, agtype);
                stmt.execute();
                return getVertexFromResultSet(stmt.getResultSet());
            }
        } catch (SQLException e) {
            log.error("updateVertex error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public GraphVertex updateVertexById(String id, GraphVertex newData) {
        ensureNotBlank(id, "Vertex id");
        ensureNotNull(newData, "Vertex data");
        String prepareSql = """
                select * from cypher('%s', $$
                   match (v)
                   where id(v)=$vertex_id
                   set v.name=$new_name,
                       v.canonical_name=$new_canonical_name,
                       v.aliases_json=$new_aliases_json,
                       v.properties_json=$new_properties_json,
                       v.salience=$new_salience,
                       v.text_segment_id=$new_text_segment_id,
                       v.description=$new_description,
                       v.metadata=$new_metadata
                   return v
                $$, ?) as (v agtype);
                """.formatted(graph);
        try (Connection connection = setupConnection();
             PreparedStatement statement = connection.prepareStatement(prepareSql)) {
            Map<String, Object> args = new HashMap<>();
            args.put("vertex_id", Long.parseLong(id));
            args.put("new_name", StringUtils.defaultString(newData.getName()));
            args.put("new_canonical_name", StringUtils.defaultIfBlank(
                    newData.getCanonicalName(), newData.getName()));
            args.put("new_aliases_json", JsonUtil.toJson(
                    newData.getAliases() == null ? List.of() : newData.getAliases()));
            args.put("new_properties_json", JsonUtil.toJson(
                    newData.getProperties() == null ? Map.of() : newData.getProperties()));
            args.put("new_salience", newData.getSalience() == null ? 5D : newData.getSalience());
            args.put("new_text_segment_id", StringUtils.defaultString(newData.getTextSegmentId()));
            args.put("new_description", StringUtils.defaultString(newData.getDescription()));
            args.put("new_metadata", newData.getMetadata() == null ? Map.of() : newData.getMetadata());
            Agtype agtype = new Agtype();
            agtype.setValue(toAgeParameterJson(args, "vertex_id"));
            statement.setObject(1, agtype);
            statement.execute();
            return getVertexFromResultSet(statement.getResultSet());
        } catch (SQLException | NumberFormatException exception) {
            log.error("updateVertexById error, vertexId:{}", id, exception);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public GraphVertex getVertexById(String id) {
        ensureNotBlank(id, "Vertex id");
        String prepareSql = """
                select * from cypher('%s', $$
                   match (v)
                   where id(v)=$vertex_id
                   return v
                   limit 1
                $$, ?) as (v agtype);
                """.formatted(graph);
        try (Connection connection = setupConnection();
             PreparedStatement statement = connection.prepareStatement(prepareSql)) {
            Agtype agtype = new Agtype();
            agtype.setValue(toAgeParameterJson(
                    Map.of("vertex_id", Long.parseLong(id)), "vertex_id"));
            statement.setObject(1, agtype);
            return getVertexFromResultSet(statement.executeQuery());
        } catch (SQLException | NumberFormatException exception) {
            log.error("getVertexById error, vertexId:{}", id, exception);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public GraphVertex getVertex(GraphVertexSearch search) {
        List<GraphVertex> list = this.searchVertices(search);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public List<GraphVertex> getVertices(List<String> ids) {
        List<Long> longIds = ids.stream().map(Long::parseLong).toList();
        try (Connection connection = setupConnection()) {
            String query = """
                    select * from cypher('%s', $$
                        match (v)
                        where id(v) in [%s]
                        return v
                    $$) as (v agtype);
                    """.formatted(graph, Joiner.on(",").join(longIds));
            log.info("getVertices query:{}", query);
            try (Statement stmt = connection.createStatement()) {
                ResultSet resultSet = stmt.executeQuery(query);
                return getVerticesFromResultSet(resultSet);
            }
        } catch (SQLException e) {
            log.error("getVertices error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    /**
     * @param search
     * @return
     */
    @Override
    public List<GraphVertex> searchVertices(GraphVertexSearch search) {
        try (Connection connection = setupConnection()) {
            String label = search.getLabel();
            String whereClause = GraphStoreUtil.buildWhereClause(search, "v");
            String query = """
                    select * from cypher('%s', $$
                        match (%s)
                        with v
                        order by id(v) desc
                        where %s and id(v) < %d
                        return v
                        limit %d
                    $$,?) as (v agtype);
                    """.formatted(graph, StringUtils.isNotBlank(label) ? "v:" + label : "v", whereClause, search.getMaxId(), search.getLimit());
            log.info("SearchVertices prepareSql:{}", query);
            try (PreparedStatement selectStmt = connection.prepareStatement(query)) {
                Map<String, Object> whereArgs = GraphStoreUtil.buildWhereArgs(search, "v");
                log.info("getVertex args:{}", whereArgs);
                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs));
                selectStmt.setObject(1, agtype);
                ResultSet resultSet = selectStmt.executeQuery();
                return getVerticesFromResultSet(resultSet);
            }
        } catch (SQLException e) {
            log.error("searchVertices error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public List<Triple<GraphVertex, GraphEdge, GraphVertex>> getEdges(List<String> ids) {
        List<Long> longIds = ids.stream().map(Long::parseLong).toList();
        try (Connection connection = setupConnection()) {
            String query = """
                    select * from cypher('%s', $$
                        match (v1)-[e]->(v2)
                        where id(e) in [%s]
                        return v1,e,v2
                    $$) as (v1 agtype,e agtype,v2 agtype);
                    """.formatted(graph, Joiner.on(",").join(longIds));
            log.info("getEdges query:{}", query);
            try (Statement stmt = connection.createStatement()) {
                ResultSet resultSet = stmt.executeQuery(query);
                return getEdgesFromResultSet(resultSet);
            }
        } catch (SQLException e) {
            log.error("getEdges error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public List<Triple<GraphVertex, GraphEdge, GraphVertex>> searchEdges(GraphEdgeSearch search) {
        try (Connection connection = setupConnection()) {
            String filterClause1 = GraphStoreUtil.buildWhereClause(search.getSource(), "v1");
            String filterClause2 = GraphStoreUtil.buildWhereClause(search.getTarget(), "v2");
            String filterClause3 = GraphStoreUtil.buildWhereClause(search.getEdge(), "e");
            String filterClause = filterClause1;
            if (StringUtils.isNotBlank(filterClause2)) {
                filterClause += StringUtils.isNotBlank(filterClause) ? " and " + filterClause2 : filterClause2;
            }
            if (StringUtils.isNotBlank(filterClause3)) {
                filterClause += StringUtils.isNotBlank(filterClause) ? " and " + filterClause3 : filterClause3;
            }
            String query = """
                    select * from cypher('%s', $$
                        match (v1)-[e]-(v2)
                        with v1,e,v2
                        order by id(e) desc
                        where %s and id(e) < %d
                        return v1,e,v2
                        limit %d
                    $$,?) as (v1 agtype,e agtype,v2 agtype);
                    """.formatted(graph, filterClause, search.getMaxId(), search.getLimit());
            log.info("Search edges prepareSql:\n{}", query);
            try (PreparedStatement selectStmt = connection.prepareStatement(query)) {
                Map<String, Object> whereArgs = buildEdgeWhereArgs(search);
                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs));
                selectStmt.setObject(1, agtype);
                log.info("Search edges args:{}", agtype);
                ResultSet resultSet = selectStmt.executeQuery();
                return getEdgesFromResultSet(resultSet);
            }
        } catch (SQLException e) {
            log.error("searchEdges error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    static Map<String, Object> buildEdgeWhereArgs(GraphEdgeSearch search) {
        Map<String, Object> whereArgs = new HashMap<>();
        whereArgs.putAll(GraphStoreUtil.buildWhereArgs(search.getSource(), "v1"));
        whereArgs.putAll(GraphStoreUtil.buildWhereArgs(search.getTarget(), "v2"));
        whereArgs.putAll(GraphStoreUtil.buildWhereArgs(search.getEdge(), "e"));
        return whereArgs;
    }

    @Override
    public Triple<GraphVertex, GraphEdge, GraphVertex> getEdge(GraphEdgeSearch search) {
        List<Triple<GraphVertex, GraphEdge, GraphVertex>> list = this.searchEdges(search);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public Triple<GraphVertex, GraphEdge, GraphVertex> addEdge(GraphEdgeAddInfo addInfo) {
        ensureNotNull(addInfo.getEdge(), "Grahp edge");
        try (Connection connection = setupConnection()) {
            String whereClause1 = GraphStoreUtil.buildWhereClause(addInfo.getSourceFilter(), "v1");
            String whereClause2 = GraphStoreUtil.buildWhereClause(addInfo.getTargetFilter(), "v2");
            String prepareSql = """
                    select * from cypher('%s', $$
                      match (v1), (v2)
                      where %s
                       create (v1)-[e:RELTYPE {text_segment_id:$text_segment_id,weight:$weight,
                            relation_type:$relation_type,polarity:$polarity,status:$status,
                            properties_json:$properties_json,evidence_count:$evidence_count,
                            description:$description,metadata:$metadata}]->(v2)
                      return v1,e,v2
                    $$, ?) as (v1 agtype,e agtype,v2 agtype);
                    """.formatted(graph, whereClause1 + " and " + whereClause2);
            log.info("Add edge prepareSql:{}", prepareSql);
            try (PreparedStatement preparedStatement = connection.prepareStatement(prepareSql)) {
                Map<String, Object> whereArgs1 = GraphStoreUtil.buildWhereArgs(addInfo.getSourceFilter(), "v1");
                Map<String, Object> whereArgs2 = GraphStoreUtil.buildWhereArgs(addInfo.getTargetFilter(), "v2");
                whereArgs1.putAll(whereArgs2);
                whereArgs1.putAll(JsonUtil.toMap(addInfo.getEdge()));
                GraphEdge edge = addInfo.getEdge();
                whereArgs1.put("relation_type", GraphRelationshipSemantics.normalizeType(edge.getRelationType()));
                whereArgs1.put("polarity", edge.getPolarity() == null || edge.getPolarity());
                whereArgs1.put("status", GraphRelationshipSemantics.normalizeStatus(edge.getStatus()));
                whereArgs1.put("properties_json", JsonUtil.toJson(
                        edge.getProperties() == null ? Map.of() : edge.getProperties()));
                whereArgs1.put("evidence_count", edge.getEvidenceCount() == null
                        ? 1 : edge.getEvidenceCount());
                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs1));
                preparedStatement.setObject(1, agtype);
                preparedStatement.execute();
                return getEdgeFromResultSet(preparedStatement.getResultSet());
            }
        } catch (SQLException e) {
            log.error("addEdge error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public Triple<GraphVertex, GraphEdge, GraphVertex> getEdgeByVertexIds(
            String sourceId, String targetId, String relationType, Boolean polarity) {
        String semanticWhere = StringUtils.isBlank(relationType)
                ? "" : " and e.relation_type=$relation_type";
        semanticWhere += polarity == null ? "" : " and e.polarity=$polarity";
        String prepareSql = """
                select * from cypher('%s', $$
                   match (v1)-[e]->(v2)
                   where id(v1)=$source_id and id(v2)=$target_id%s
                   return v1,e,v2
                   limit 1
                $$, ?) as (v1 agtype,e agtype,v2 agtype);
                """.formatted(graph, semanticWhere);
        try (Connection connection = setupConnection();
             PreparedStatement statement = connection.prepareStatement(prepareSql)) {
            Map<String, Object> args = new HashMap<>();
            args.put("source_id", Long.parseLong(sourceId));
            args.put("target_id", Long.parseLong(targetId));
            if (StringUtils.isNotBlank(relationType)) {
                args.put("relation_type", GraphRelationshipSemantics.normalizeType(relationType));
            }
            if (polarity != null) args.put("polarity", polarity);
            Agtype agtype = new Agtype();
            agtype.setValue(toAgeParameterJson(args, "source_id", "target_id"));
            statement.setObject(1, agtype);
            return getEdgeFromResultSet(statement.executeQuery());
        } catch (SQLException | NumberFormatException exception) {
            log.error("getEdgeByVertexIds error, sourceId:{}, targetId:{}",
                    sourceId, targetId, exception);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public Triple<GraphVertex, GraphEdge, GraphVertex> addEdgeByVertexIds(String sourceId,
                                                                          String targetId,
                                                                          GraphEdge edge) {
        ensureNotNull(edge, "Graph edge");
        String prepareSql = """
                select * from cypher('%s', $$
                   match (v1), (v2)
                   where id(v1)=$source_id and id(v2)=$target_id
                   create (v1)-[e:RELTYPE {text_segment_id:$text_segment_id,
                            weight:$weight,relation_type:$relation_type,polarity:$polarity,
                            status:$status,properties_json:$properties_json,
                            evidence_count:$evidence_count,description:$description,
                            metadata:$metadata}]->(v2)
                   return v1,e,v2
                $$, ?) as (v1 agtype,e agtype,v2 agtype);
                """.formatted(graph);
        try (Connection connection = setupConnection();
             PreparedStatement statement = connection.prepareStatement(prepareSql)) {
            Map<String, Object> args = new HashMap<>();
            args.put("source_id", Long.parseLong(sourceId));
            args.put("target_id", Long.parseLong(targetId));
            args.put("text_segment_id", StringUtils.defaultString(edge.getTextSegmentId()));
            args.put("weight", edge.getWeight() == null ? 0D : edge.getWeight());
            args.put("relation_type", GraphRelationshipSemantics.normalizeType(edge.getRelationType()));
            args.put("polarity", edge.getPolarity() == null || edge.getPolarity());
            args.put("status", GraphRelationshipSemantics.normalizeStatus(edge.getStatus()));
            args.put("properties_json", JsonUtil.toJson(
                    edge.getProperties() == null ? Map.of() : edge.getProperties()));
            args.put("evidence_count", edge.getEvidenceCount() == null ? 1 : edge.getEvidenceCount());
            args.put("description", StringUtils.defaultString(edge.getDescription()));
            args.put("metadata", edge.getMetadata() == null ? Map.of() : edge.getMetadata());
            Agtype agtype = new Agtype();
            agtype.setValue(toAgeParameterJson(args, "source_id", "target_id"));
            statement.setObject(1, agtype);
            statement.execute();
            return getEdgeFromResultSet(statement.getResultSet());
        } catch (SQLException | NumberFormatException exception) {
            log.error("addEdgeByVertexIds error, sourceId:{}, targetId:{}",
                    sourceId, targetId, exception);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public Triple<GraphVertex, GraphEdge, GraphVertex> updateEdge(GraphEdgeEditInfo edgeEditInfo) {
        log.info("Update edge:{}", edgeEditInfo);
        ensureNotNull(edgeEditInfo.getEdge(), "Graph edit info");
        GraphEdge newData = edgeEditInfo.getEdge();
        try (Connection connection = setupConnection()) {
            String whereClause1 = GraphStoreUtil.buildWhereClause(edgeEditInfo.getSourceFilter(), "v1");
            String whereClause2 = GraphStoreUtil.buildWhereClause(edgeEditInfo.getTargetFilter(), "v2");
            String setClause = GraphStoreUtil.buildSetClause(edgeEditInfo.getEdge().getMetadata());
            String prepareSql = """
                    select * from cypher('%s', $$
                       match (v1)-[e]->(v2)
                       where %s
                       set e.weight=$new_weight,e.text_segment_id=$new_text_segment_id,e.description=$new_description %s
                       return v1,e,v2
                    $$, ?) as (v1 agtype,e agtype,v2 agtype);
                    """.formatted(graph, whereClause1 + " and " + whereClause2, setClause);
            log.info("updateEdge prepareSql:{}", prepareSql);
            try (PreparedStatement upsertStmt = connection.prepareStatement(prepareSql)) {
                Map<String, Object> whereArgs1 = GraphStoreUtil.buildWhereArgs(edgeEditInfo.getSourceFilter(), "v1");
                Map<String, Object> whereArgs2 = GraphStoreUtil.buildWhereArgs(edgeEditInfo.getTargetFilter(), "v2");
                Map<String, Object> setArgs = GraphStoreUtil.buildSetArgs(edgeEditInfo.getEdge().getMetadata());
                whereArgs1.putAll(whereArgs2);
                whereArgs1.putAll(setArgs);
                whereArgs1.putAll(
                        Map.of(
                                "new_text_segment_id", newData.getTextSegmentId(),
                                "new_weight", newData.getWeight(),
                                "new_description", newData.getDescription()
                        )
                );
                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs1));
                upsertStmt.setObject(1, agtype);
                upsertStmt.execute();
                return getEdgeFromResultSet(upsertStmt.getResultSet());
            }
        } catch (SQLException e) {
            log.error("updateEdge error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public Triple<GraphVertex, GraphEdge, GraphVertex> updateEdgeById(String edgeId, GraphEdge edge) {
        ensureNotBlank(edgeId, "Edge id");
        ensureNotNull(edge, "Graph edge");
        String prepareSql = """
                select * from cypher('%s', $$
                   match (v1)-[e]->(v2)
                   where id(e)=$edge_id
                   set e.weight=$new_weight,
                       e.relation_type=$new_relation_type,
                       e.polarity=$new_polarity,
                       e.status=$new_status,
                       e.properties_json=$new_properties_json,
                       e.evidence_count=$new_evidence_count,
                       e.text_segment_id=$new_text_segment_id,
                       e.description=$new_description,
                       e.metadata=$new_metadata
                   return v1,e,v2
                $$, ?) as (v1 agtype,e agtype,v2 agtype);
                """.formatted(graph);
        try (Connection connection = setupConnection();
             PreparedStatement statement = connection.prepareStatement(prepareSql)) {
            Map<String, Object> args = new HashMap<>();
            args.put("edge_id", Long.parseLong(edgeId));
            args.put("new_weight", edge.getWeight() == null ? 0D : edge.getWeight());
            args.put("new_relation_type", GraphRelationshipSemantics.normalizeType(edge.getRelationType()));
            args.put("new_polarity", edge.getPolarity() == null || edge.getPolarity());
            args.put("new_status", GraphRelationshipSemantics.normalizeStatus(edge.getStatus()));
            args.put("new_properties_json", JsonUtil.toJson(
                    edge.getProperties() == null ? Map.of() : edge.getProperties()));
            args.put("new_evidence_count", edge.getEvidenceCount() == null
                    ? 1 : edge.getEvidenceCount());
            args.put("new_text_segment_id", StringUtils.defaultString(edge.getTextSegmentId()));
            args.put("new_description", StringUtils.defaultString(edge.getDescription()));
            args.put("new_metadata", edge.getMetadata() == null ? Map.of() : edge.getMetadata());
            Agtype agtype = new Agtype();
            agtype.setValue(toAgeParameterJson(args, "edge_id"));
            statement.setObject(1, agtype);
            statement.execute();
            return getEdgeFromResultSet(statement.getResultSet());
        } catch (SQLException | NumberFormatException exception) {
            log.error("updateEdgeById error, edgeId:{}", edgeId, exception);
            throw new BaseException(B_DB_ERROR);
        }
    }

    /**
     * 删除顶点(以及边)
     *
     * @param filter
     * @param includeEdges
     */
    @Override
    public void deleteVertices(GraphSearchCondition filter, boolean includeEdges) {
        ensureNotNull(filter, "Data filter");
        ensureNotNull(filter.getMetadataFilter(), "Metadata filter");
        try (Connection connection = setupConnection()) {
            String whereClause = GraphStoreUtil.buildWhereClause(filter, "v");
            String prepareSql = """
                     select * from cypher('%s', $$
                      match (v)
                      where %s %s
                    $$,?) as (v agtype);
                    """.formatted(graph, whereClause, includeEdges ? "DETACH DELETE v" : "DELETE v");
            log.info("deleteVertices prepareSql:{}", prepareSql);
            try (PreparedStatement upsertStmt = connection.prepareStatement(prepareSql)) {
                Map<String, Object> whereArgs = GraphStoreUtil.buildWhereArgs(filter, "v");
                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs));
                upsertStmt.setObject(1, agtype);
                upsertStmt.execute();
            }
        } catch (SQLException e) {
            log.error("deleteVertices error", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    /**
     * 单独删除边
     *
     * @param filter
     */
    @Override
    public void deleteEdges(GraphSearchCondition filter) {
        ensureNotNull(filter, "Data filter");
        try (Connection connection = setupConnection()) {
            String whereClause = GraphStoreUtil.buildWhereClause(filter, "r");
            String prepareSql = """
                    select * from cypher('%s', $$
                        match ()-[r]->()
                        where %s
                        delete r
                    $$,?) as (r agtype);
                    """.formatted(graph, whereClause);
            log.info("deleteEdges prepareSql:{}", prepareSql);
            try (PreparedStatement upsertStmt = connection.prepareStatement(prepareSql)) {
                Map<String, Object> whereArgs = GraphStoreUtil.buildWhereArgs(filter, "r");
                Agtype agtype = new Agtype();
                agtype.setValue(JsonUtil.toJson(whereArgs));
                upsertStmt.setObject(1, agtype);
                upsertStmt.execute();
            }
        } catch (SQLException e) {
            log.error("deleteEdges sql exception", e);
            throw new BaseException(B_DB_ERROR);
        }
    }

    @Override
    public void deleteVerticesByIds(List<String> ids) {
        List<Long> longIds = toGraphIds(ids);
        if (longIds.isEmpty()) {
            return;
        }
        String query = """
                select * from cypher('%s', $$
                    match (v)
                    where id(v) in [%s]
                    detach delete v
                $$) as (v agtype);
                """.formatted(graph, Joiner.on(",").join(longIds));
        executeDeleteByIds("vertices", query);
    }

    @Override
    public void deleteEdgesByIds(List<String> ids) {
        List<Long> longIds = toGraphIds(ids);
        if (longIds.isEmpty()) {
            return;
        }
        String query = """
                select * from cypher('%s', $$
                    match ()-[e]->()
                    where id(e) in [%s]
                    delete e
                $$) as (e agtype);
                """.formatted(graph, Joiner.on(",").join(longIds));
        executeDeleteByIds("edges", query);
    }

    private List<Long> toGraphIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(StringUtils::isNotBlank)
                .map(Long::parseLong)
                .distinct()
                .toList();
    }

    /**
     * Serializes Apache AGE's graph-native identifiers as JSON numbers.
     *
     * <p>The application's shared {@link JsonUtil} intentionally serializes boxed
     * {@link Long} values as strings for browser-facing JSON. Cypher's
     * {@code id(...)} function, however, returns a numeric graphid and does not
     * match a string parameter. Build the regular parameter tree with the shared
     * mapper to preserve existing metadata serialization, then explicitly restore
     * graph ID fields to integral JSON nodes.</p>
     */
    static String toAgeParameterJson(Map<String, Object> arguments, String... graphIdKeys) {
        ObjectNode parameters = JsonUtil.getObjectMapper().valueToTree(arguments);
        for (String graphIdKey : graphIdKeys) {
            Object value = arguments.get(graphIdKey);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(
                        "Apache AGE graph ID parameter must be numeric: " + graphIdKey);
            }
            parameters.put(graphIdKey, number.longValue());
        }
        return parameters.toString();
    }

    private void executeDeleteByIds(String elementType, String query) {
        log.info("Delete graph {} by ids query:{}", elementType, query);
        try (Connection connection = setupConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(query);
        } catch (SQLException exception) {
            log.error("Delete graph {} by ids failed", elementType, exception);
            throw new BaseException(B_DB_ERROR);
        }
    }

    private List<Triple<GraphVertex, GraphEdge, GraphVertex>> getEdgesFromResultSet(ResultSet resultSet) {
        List<Triple<GraphVertex, GraphEdge, GraphVertex>> result = new ArrayList<>();
        try {
            while (resultSet.next()) {
                Agtype source = resultSet.getObject(1, Agtype.class);
                Agtype edge = resultSet.getObject(2, Agtype.class);
                Agtype target = resultSet.getObject(3, Agtype.class);
                result.add(Triple.of(agTypeToVertex(source), agTypeToEdge(edge), agTypeToVertex(target)));
            }
        } catch (SQLException e) {
            log.error("getEdgesFromResultSet error", e);
            throw new BaseException(B_DB_ERROR);
        }
        return result;
    }

    public Triple<GraphVertex, GraphEdge, GraphVertex> getEdgeFromResultSet(ResultSet resultSet) {
        List<Triple<GraphVertex, GraphEdge, GraphVertex>> list = getEdgesFromResultSet(resultSet);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    public GraphVertex getVertexFromResultSet(ResultSet resultSet) {
        List<GraphVertex> vertices = getVerticesFromResultSet(resultSet);
        if (vertices.isEmpty()) {
            return null;
        }
        return vertices.get(0);
    }

    public List<GraphVertex> getVerticesFromResultSet(ResultSet resultSet) {
        List<GraphVertex> vertices = new ArrayList<>();
        try {
            while (resultSet.next()) {
                Agtype returnedAgtype = resultSet.getObject(1, Agtype.class);
                vertices.add(agTypeToVertex(returnedAgtype));
            }
        } catch (SQLException e) {
            log.error("getVerticesFromResultSet error", e);
            throw new BaseException(B_DB_ERROR);
        }
        return vertices;
    }

    public GraphVertex agTypeToVertex(Agtype agtype) {
        AgtypeMap agtypeMap = agtype.getMap();
        String id = String.valueOf(agtypeMap.getLong("id"));
        String label = agtypeMap.getObject("label").toString();
        AgtypeMap nodeProps = agtypeMap.getMap("properties");
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Object> entry : nodeProps.getMap("metadata").entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return GraphVertex.builder()
                .id(id)
                .label(label)
                .name(nodeProps.getString("name"))
                .canonicalName(stringProperty(nodeProps, "canonical_name",
                        nodeProps.getString("name")))
                .aliases(stringListProperty(nodeProps, "aliases_json"))
                .properties(mapProperty(nodeProps, "properties_json"))
                .salience(doubleProperty(nodeProps, "salience", 5D))
                .description(nodeProps.getString("description"))
                .textSegmentId(nodeProps.getString("text_segment_id"))
                .metadata(map)
                .build();
    }

    private GraphEdge agTypeToEdge(Agtype agtype) {
        AgtypeMap agtypeMap = agtype.getMap();
        Long id = agtypeMap.getLong("id");
        Long startId = agtypeMap.getLong("start_id");
        Long endId = agtypeMap.getLong("end_id");
        String nodeLabel = agtypeMap.getObject("label").toString();
        AgtypeMap nodeProps = agtypeMap.getMap("properties");
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Object> entry : nodeProps.getMap("metadata").entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return GraphEdge.builder()
                .id(id + "")
                .startId(startId + "")
                .endId(endId + "")
                .label(nodeLabel)
                .weight(null == nodeProps.getObject("weight") ? 0 : nodeProps.getDouble("weight"))
                .relationType(GraphRelationshipSemantics.normalizeType(
                        stringProperty(nodeProps, "relation_type",
                                GraphRelationshipSemantics.DEFAULT_TYPE)))
                .polarity(booleanProperty(nodeProps, "polarity", true))
                .status(GraphRelationshipSemantics.normalizeStatus(
                        stringProperty(nodeProps, "status",
                                GraphRelationshipSemantics.DEFAULT_STATUS)))
                .properties(mapProperty(nodeProps, "properties_json"))
                .evidenceCount(integerProperty(nodeProps, "evidence_count", 1))
                .description(nodeProps.getString("description"))
                .textSegmentId(nodeProps.getString("text_segment_id"))
                .metadata(map)
                .build();
    }

    private String stringProperty(AgtypeMap properties, String key, String defaultValue) {
        Object value = properties.getObject(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Double doubleProperty(AgtypeMap properties, String key, double defaultValue) {
        Object value = properties.getObject(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private Integer integerProperty(AgtypeMap properties, String key, int defaultValue) {
        Object value = properties.getObject(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private Boolean booleanProperty(AgtypeMap properties, String key, boolean defaultValue) {
        Object value = properties.getObject(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private List<String> stringListProperty(AgtypeMap properties, String key) {
        List<String> values = JsonUtil.toList(stringProperty(properties, key, "[]"), String.class);
        return values == null ? List.of() : values;
    }

    private Map<String, Object> mapProperty(AgtypeMap properties, String key) {
        try {
            return JsonUtil.toMap(stringProperty(properties, key, "{}"));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("java:S2095")
    private Connection setupConnection() throws SQLException {
        PgConnection connection = DriverManager.getConnection(
                String.format("jdbc:postgresql://%s:%s/%s", host, port, database),
                user,
                password
        ).unwrap(PgConnection.class);
        try (Statement stmt = connection.createStatement()) {
            connection.addDataType("agtype", Agtype.class);
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public;");
        }
        return connection;
    }
}
