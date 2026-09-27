package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphElementSourceService;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStoreIngestorIdentityTest {

    @Test
    void resolvesTypesBeforeConnectingRelationshipByVertexIdentity() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        String response = """
                ("entity"<|>曜穹机器人<|>PRODUCT<|>曜穹机器人是一家工业机器人公司)##
                ("entity"<|>赤脊七型<|>ORGANIZATION<|>赤脊七型是该公司发布的巡检产品)##
                ("relationship"<|>曜穹机器人<|>赤脊七型<|>曜穹机器人发布了赤脊七型<|>8)
                """;

        ingest(graphStore, response);

        GraphVertex organization = graphStore.vertex("曜穹机器人");
        GraphVertex product = graphStore.vertex("赤脊七型");
        assertEquals("ORGANIZATION", organization.getLabel());
        assertEquals("PRODUCT", product.getLabel());
        assertNotEquals(organization.getId(), product.getId());
        assertEquals(organization.getId(), graphStore.lastEdgeSourceId);
        assertEquals(product.getId(), graphStore.lastEdgeTargetId);
        assertEquals("doc-1", organization.getMetadata().get(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID));
        assertEquals("doc-1", product.getMetadata().get(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID));
    }

    @Test
    void failsWhenVertexUpdateDoesNotConfirmPersistedIdentity() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        graphStore.returnNullOnVertexUpdate = true;

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                ingest(graphStore, """
                        ("entity"<|>鹤纹感知材料公司<|>ORGANIZATION<|>一家感知材料公司)
                        """));

        assertTrue(exception.getMessage().contains(
                "Graph vertex disappeared while materializing contributions"));
        assertTrue(exception.getMessage().contains("鹤纹感知材料公司"));
        assertEquals(0, graphStore.edgeAddAttempts);
    }

    @Test
    void acceptsEdgeThatIsVisibleOnlyOnFollowUpRead() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        graphStore.returnNullAfterEdgeCreate = true;

        ingest(graphStore, craneRelationshipResponse());

        assertEquals(1, graphStore.edgeAddAttempts);
        assertEquals(1, graphStore.edges.size());
    }

    @Test
    void reportsBothEndpointIdentitiesWhenEdgeCannotBeCreated() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        graphStore.refuseEdgeCreate = true;

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                ingest(graphStore, craneRelationshipResponse()));

        assertTrue(exception.getMessage().contains("Unable to store graph relationship"));
        assertTrue(exception.getMessage().contains("sourceName:鹤纹感知材料公司"));
        assertTrue(exception.getMessage().contains("targetName:鹤川材料走廊"));
        assertTrue(exception.getMessage().contains("sourceExists:true"));
        assertTrue(exception.getMessage().contains("targetExists:true"));
    }

    @Test
    void stopsBeforeEdgeCreateWhenEndpointDisappears() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        graphStore.hideAfterFirstIdLookupName = "鹤川材料走廊";

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                ingest(graphStore, craneRelationshipResponse()));

        assertTrue(exception.getMessage().contains("Relationship endpoint is missing"));
        assertTrue(exception.getMessage().contains("role:target"));
        assertTrue(exception.getMessage().contains("endpointName:鹤川材料走廊"));
        assertEquals(0, graphStore.edgeAddAttempts);
    }

    @Test
    void keepsDifferentRelationshipSemanticsBetweenSameEndpointsSeparate() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        ingest(graphStore, """
                ("entity"<|>鹤纹感知材料公司<|>ORGANIZATION<|>材料公司<|>鹤纹感知材料公司<|>[]<|>{}<|>9)##
                ("entity"<|>曜穹机器人<|>ORGANIZATION<|>机器人公司<|>曜穹机器人<|>[]<|>{}<|>9)##
                ("relationship"<|>鹤纹感知材料公司<|>曜穹机器人<|>双方联合研发<|>8<|>COOPERATES_WITH<|>true<|>ASSERTED<|>{})##
                ("relationship"<|>鹤纹感知材料公司<|>曜穹机器人<|>收入占比接近一半<|>8<|>HAS_METRIC<|>true<|>ASSERTED<|>{})
                """);

        assertEquals(2, graphStore.edges.size());
    }

    @Test
    void aggregatesOnlySameSemanticEdgeWithBoundedWeightAndEvidenceCount() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        ingest(graphStore, """
                ("entity"<|>鹤纹感知材料公司<|>ORGANIZATION<|>材料公司<|>鹤纹感知材料公司<|>[]<|>{}<|>9)##
                ("entity"<|>曜穹机器人<|>ORGANIZATION<|>机器人公司<|>曜穹机器人<|>[]<|>{}<|>9)##
                ("relationship"<|>鹤纹感知材料公司<|>曜穹机器人<|>联合研发<|>8<|>COOPERATES_WITH<|>true<|>ASSERTED<|>{})##
                ("relationship"<|>鹤纹感知材料公司<|>曜穹机器人<|>签订合作合同<|>6<|>COOPERATES_WITH<|>true<|>ASSERTED<|>{})
                """);

        GraphEdge edge = graphStore.edges.values().iterator().next().getMiddle();
        assertEquals(1, graphStore.edges.size());
        assertEquals(8D, edge.getWeight());
        assertEquals(2, edge.getEvidenceCount());
    }

    @Test
    void mergesCanonicalNameAndAliasIntoOneVertex() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        ingest(graphStore, """
                ("entity"<|>曜穹机器人股份公司<|>ORGANIZATION<|>工业机器人公司<|>曜穹机器人股份公司<|>["曜穹机器人"]<|>{}<|>9)##
                ("entity"<|>曜穹机器人<|>ORGANIZATION<|>公司的简称<|>曜穹机器人股份公司<|>["曜穹机器人"]<|>{}<|>8)
                """);

        assertEquals(1, graphStore.vertices.size());
        GraphVertex vertex = graphStore.vertices.values().iterator().next();
        assertEquals("曜穹机器人股份公司", vertex.getCanonicalName());
        assertTrue(vertex.getAliases().contains("曜穹机器人"));
    }

    @Test
    void dropsSelfLoopRelationshipWhenIdentityMergeCollapsesBothEndpoints() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        ingest(graphStore, """
                ("entity"<|>浮灯云网基础设施档案<|>ORGANIZATION<|>记录公司沿革的基础设施档案<|>浮灯云网基础设施档案<|>["浮灯云网有限公司"]<|>{}<|>9)##
                ("entity"<|>浮灯云网有限公司<|>ORGANIZATION<|>云网基础设施公司<|>浮灯云网基础设施档案<|>["浮灯云网有限公司"]<|>{}<|>8)##
                ("relationship"<|>浮灯云网基础设施档案<|>浮灯云网有限公司<|>档案记录了公司的业务沿革<|>6<|>RELATED_TO<|>true<|>ASSERTED<|>{})
                """);

        // The archive entity and the company entity merge into one vertex through
        // the shared alias, so the meaningful relationship between them degenerates
        // into a self-loop after endpoint resolution and must not be stored.
        assertEquals(1, graphStore.vertices.size());
        assertEquals(0, graphStore.edges.size());
        assertEquals(0, graphStore.edgeAddAttempts);
    }

    @Test
    void keepsOneKbIdentityWhenLaterDocumentUsesConflictingType() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        ingest(graphStore, """
                ("entity"<|>浮灯云网<|>SYSTEM<|>跨区域数据系统<|>浮灯云网<|>[]<|>{}<|>9)
                """);
        ingest(graphStore, """
                ("entity"<|>浮灯云网<|>ORGANIZATION<|>文档误称为组织<|>浮灯云网<|>[]<|>{}<|>8)
                """);

        assertEquals(1, graphStore.vertices.size());
        assertEquals("SYSTEM", graphStore.vertices.values().iterator().next().getLabel());
    }

    @Test
    void mergesFullNameAndShortNameAcrossSegmentsWithoutInventingASecondVertex() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        ingest(graphStore, """
                ("entity"<|>曜穹机器人<|>ORGANIZATION<|>工业机器人公司<|>曜穹机器人<|>[]<|>{}<|>9)
                """);
        ingest(graphStore, """
                ("entity"<|>曜穹机器人股份公司<|>ORGANIZATION<|>公司的完整名称<|>曜穹机器人股份公司<|>[]<|>{}<|>9)
                """);

        assertEquals(1, graphStore.vertices.size());
        assertEquals("曜穹机器人股份公司",
                graphStore.vertices.values().iterator().next().getCanonicalName());
    }

    @Test
    void persistsFullEntityNameBeyondTwentyCharacters() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        String longName = "曙光学控一体机远程运维调度平台数据中心系统";
        assertTrue(longName.length() > 20, "fixture must be longer than the historical tail cut");

        ingest(graphStore, """
                ("entity"<|>%s<|>SYSTEM<|>区域运维调度系统<|>%s<|>[]<|>{}<|>9)
                """.formatted(longName, longName));

        GraphVertex vertex = graphStore.vertices.values().iterator().next();
        assertEquals(longName, vertex.getName());
        assertEquals(longName, vertex.getCanonicalName());
    }

    @Test
    void refusesToPersistEntityWhoseTypeStaysUnknown() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                ingest(graphStore, """
                        ("entity"<|>某未分类对象<|>UNKNOWN<|>一种无法归入核心类型的对象)
                        """));

        assertTrue(exception.getMessage().contains("unresolved type"));
        assertTrue(exception.getMessage().contains("某未分类对象"));
        assertTrue(graphStore.vertices.isEmpty());
        assertEquals(0, graphStore.edgeAddAttempts);
    }

    @Test
    void mergesBridgeAliasesTransitivelyWithoutStoringTheSameEntityTwice() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();

        ingest(graphStore, """
                ("entity"<|>北方重工集团<|>ORGANIZATION<|>北方的重型工业集团<|>北方重工集团<|>[]<|>{}<|>8)##
                ("entity"<|>南方重工集团<|>ORGANIZATION<|>南方的重型工业集团<|>南方重工集团<|>[]<|>{}<|>8)##
                ("entity"<|>南北联合控股集团<|>ORGANIZATION<|>两集团的联合控股主体<|>南北联合控股集团<|>["北方重工集团","南方重工集团"]<|>{}<|>9)
                """);

        // 北方重工集团 and 南方重工集团 do not overlap each other; both only
        // overlap the bridging third record. One transitive identity group must
        // produce one stored contribution, not two writes to the same vertex.
        assertEquals(1, graphStore.vertices.size());
        assertEquals(1, graphStore.totalVertexUpdates());
        GraphVertex vertex = graphStore.vertices.values().iterator().next();
        assertEquals("南北联合控股集团", vertex.getCanonicalName());
        assertTrue(vertex.getAliases().contains("北方重工集团"));
        assertTrue(vertex.getAliases().contains("南方重工集团"));
    }

    @Test
    void reusesSameNameVertexMissedByTheBoundedIdentitySnapshot() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        Map<String, Object> existingMetadata = new HashMap<>();
        existingMetadata.put(ZhiMeshConstant.MetadataKey.KB_UUID, "kb-1");
        existingMetadata.put(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, "doc-0");
        graphStore.addVertex(GraphVertex.builder()
                .label("ORGANIZATION")
                .name("曜穹机器人股份公司")
                .canonicalName("曜穹机器人股份公司")
                .aliases(List.of())
                .properties(Map.of())
                .salience(9D)
                .textSegmentId("seg-0")
                .description("已存在的公司")
                .metadata(existingMetadata)
                .build());
        // Simulates a same-name vertex outside the bounded KB identity snapshot
        // (for example a graph larger than the snapshot limit).
        graphStore.hideVerticesFromSnapshotSearch = true;
        // The setup write above is not part of what the assertion measures.
        graphStore.addVertexAttempts = 0;

        ingest(graphStore, """
                ("entity"<|>曜穹机器人股份公司<|>ORGANIZATION<|>工业机器人公司<|>曜穹机器人股份公司<|>[]<|>{}<|>9)
                """);

        assertEquals(1, graphStore.vertices.size());
        assertEquals(0, graphStore.addVertexAttempts);
        assertEquals("曜穹机器人股份公司",
                graphStore.vertices.values().iterator().next().getName());
    }

    @Test
    void ingestSegmentsPassesCanonicalChunksThroughWithoutSplitting() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        Metadata metadata = new Metadata();
        metadata.put(ZhiMeshConstant.MetadataKey.KB_UUID, "kb-1");
        metadata.put(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, "doc-1");
        metadata.put(ZhiMeshConstant.MetadataKey.CHUNK_UUID, "chunk-77");
        TextSegment canonicalSegment = TextSegment.from("canonical chunk text", metadata);
        List<TextSegment> received = new ArrayList<>();
        GraphStoreIngestor.builder()
                .graphStore(graphStore)
                .segmentsFunction(segments -> {
                    received.addAll(segments);
                    return List.of(Triple.of(segments.get(0), "seg-1", ""));
                })
                .identifyColumns(List.of(ZhiMeshConstant.MetadataKey.KB_UUID))
                .appendColumns(List.of(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID))
                .chunkSetUuid("chunk-set-1")
                .graphModelId(24L)
                .graphIndexVersionUuid("build-1")
                .elementSourceService(graphStore.contributionService)
                .build()
                .ingestSegments(List.of(canonicalSegment));

        // The segment must reach extraction exactly as provided: canonical
        // alignment only works when no splitter re-cuts the chunk text.
        assertEquals(List.of(canonicalSegment), received);
    }

    private void ingest(InMemoryGraphStore graphStore, String response) {
        Metadata metadata = new Metadata();
        metadata.put(ZhiMeshConstant.MetadataKey.KB_UUID, "kb-1");
        metadata.put(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, "doc-1");
        GraphStoreIngestor.builder()
                .graphStore(graphStore)
                .segmentsFunction(segments -> List.of(Triple.of(segments.get(0), "seg-1", response)))
                .identifyColumns(List.of(ZhiMeshConstant.MetadataKey.KB_UUID))
                .appendColumns(List.of(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID))
                .chunkSetUuid("chunk-set-1")
                .graphModelId(24L)
                .graphIndexVersionUuid("build-1")
                .elementSourceService(graphStore.contributionService)
                .build()
                .ingest(new DefaultDocument("测试图谱关系。", metadata));
    }

    private String craneRelationshipResponse() {
        return """
                ("entity"<|>鹤纹感知材料公司<|>ORGANIZATION<|>一家感知材料公司)##
                ("entity"<|>鹤川材料走廊<|>LOCATION<|>该公司的材料产业走廊)##
                ("relationship"<|>鹤纹感知材料公司<|>鹤川材料走廊<|>公司位于材料走廊<|>8)
                """;
    }

    private static final class InMemoryContributionService
            extends KnowledgeBaseGraphElementSourceService {
        private final Map<String, ContributionAggregate> aggregates = new HashMap<>();

        @Override
        public void record(String kbUuid, String kbItemUuid, String segmentUuid, String type,
                           String elementId, String description, Double weight,
                           String chunkSetUuid, String chunkUuid, Long graphModelId,
                           String graphIndexVersionUuid) {
            aggregates.put(type + ':' + elementId, new ContributionAggregate(
                    description, segmentUuid, weight == null ? 0D : weight,
                    Map.of(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid,
                             ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, kbItemUuid), 1));
        }

        @Override
        public void record(String kbUuid, String kbItemUuid, String segmentUuid, String type,
                           String elementId, String description, Double weight,
                           String canonicalName, List<String> aliases,
                           Map<String, Object> properties, Double salience,
                           String relationType, Boolean relationPolarity, String relationStatus,
                           String chunkSetUuid, String chunkUuid, Long graphModelId,
                           String graphIndexVersionUuid) {
            String key = type + ':' + elementId;
            ContributionAggregate previous = aggregates.get(key);
            if (previous == null) {
                aggregates.put(key, new ContributionAggregate(
                        description, segmentUuid, weight == null ? 0D : Math.min(10D, weight),
                        Map.of(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid,
                                ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, kbItemUuid), 1,
                        canonicalName, aliases, properties, salience,
                        relationType, relationPolarity, relationStatus));
                return;
            }
            String resolvedCanonical = canonicalName.length() > previous.canonicalName().length()
                    ? canonicalName : previous.canonicalName();
            java.util.LinkedHashSet<String> mergedAliases = new java.util.LinkedHashSet<>(previous.aliases());
            mergedAliases.addAll(aliases);
            mergedAliases.add(previous.canonicalName());
            mergedAliases.add(canonicalName);
            mergedAliases.remove(resolvedCanonical);
            Map<String, Object> mergedProperties = new LinkedHashMap<>(previous.properties());
            mergedProperties.putAll(properties);
            Double mergedSalience = previous.salience();
            if (mergedSalience == null || (salience != null && salience > mergedSalience)) {
                mergedSalience = salience;
            }
            aggregates.put(key, new ContributionAggregate(
                    previous.description() + "\n" + description,
                    previous.textSegmentId() + "," + segmentUuid,
                    Math.max(previous.weight(), weight == null ? 0D : Math.min(10D, weight)),
                    previous.metadata(), previous.sourceCount() + 1,
                    resolvedCanonical, List.copyOf(mergedAliases), mergedProperties, mergedSalience,
                    StringUtils.isBlank(previous.relationType()) ? relationType : previous.relationType(),
                    previous.polarity() == null ? relationPolarity : previous.polarity(),
                    StringUtils.isBlank(previous.status()) ? relationStatus : previous.status()));
        }

        @Override
        public ContributionAggregate aggregate(String type, String elementId) {
            return aggregates.get(type + ':' + elementId);
        }
    }

    private static final class InMemoryGraphStore implements GraphStore {
        private final Map<String, GraphVertex> vertices = new LinkedHashMap<>();
        private final Map<String, Triple<GraphVertex, GraphEdge, GraphVertex>> edges = new LinkedHashMap<>();
        private final InMemoryContributionService contributionService = new InMemoryContributionService();
        private int nextVertexId = 1;
        private int nextEdgeId = 100;
        private String lastEdgeSourceId;
        private String lastEdgeTargetId;
        private boolean returnNullOnVertexUpdate;
        private boolean returnNullAfterEdgeCreate;
        private boolean refuseEdgeCreate;
        private String hideAfterFirstIdLookupName;
        private boolean hideVerticesFromSnapshotSearch;
        private int edgeAddAttempts;
        private int addVertexAttempts;
        private final Map<String, Integer> vertexUpdateCounts = new HashMap<>();
        private final Map<String, Integer> vertexIdLookupCounts = new HashMap<>();

        GraphVertex vertex(String name) {
            return vertices.values().stream().filter(value -> name.equals(value.getName()))
                    .findFirst().orElseThrow();
        }

        int totalVertexUpdates() {
            return vertexUpdateCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public boolean addVertexes(List<GraphVertex> values) {
            values.forEach(this::addVertex);
            return true;
        }

        @Override
        public boolean addVertex(GraphVertex vertex) {
            addVertexAttempts++;
            vertex.setId(String.valueOf(nextVertexId++));
            vertices.put(vertex.getId(), vertex);
            return true;
        }

        @Override
        public GraphVertex updateVertex(GraphVertexUpdateInfo updateInfo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphVertex updateVertexById(String id, GraphVertex newData) {
            if (returnNullOnVertexUpdate) {
                return null;
            }
            vertexUpdateCounts.merge(id, 1, Integer::sum);
            GraphVertex existing = vertices.get(id);
            existing.setTextSegmentId(newData.getTextSegmentId());
            existing.setDescription(newData.getDescription());
            existing.setName(newData.getName());
            existing.setCanonicalName(newData.getCanonicalName());
            existing.setAliases(newData.getAliases());
            existing.setProperties(newData.getProperties());
            existing.setSalience(newData.getSalience());
            existing.setMetadata(new HashMap<>(newData.getMetadata()));
            return existing;
        }

        @Override
        public GraphVertex getVertexById(String id) {
            GraphVertex existing = vertices.get(id);
            int lookups = vertexIdLookupCounts.merge(id, 1, Integer::sum);
            if (existing != null && existing.getName().equals(hideAfterFirstIdLookupName)
                    && lookups > 1) {
                return null;
            }
            return existing;
        }

        @Override
        public GraphVertex getVertex(GraphVertexSearch search) {
            return searchVertices(search).stream().findFirst().orElse(null);
        }

        @Override
        public List<GraphVertex> getVertices(List<String> ids) {
            return ids.stream().map(vertices::get).toList();
        }

        @Override
        public List<GraphVertex> searchVertices(GraphVertexSearch search) {
            if (hideVerticesFromSnapshotSearch
                    && (search.getNames() == null || search.getNames().isEmpty())) {
                // Simulates the bounded KB identity snapshot missing this vertex.
                return List.of();
            }
            List<GraphVertex> result = new ArrayList<>();
            for (GraphVertex vertex : vertices.values()) {
                boolean nameMatches = search.getNames() == null || search.getNames().isEmpty()
                        || search.getNames().contains(vertex.getName());
                boolean labelMatches = search.getLabel() == null || search.getLabel().isBlank()
                        || search.getLabel().equals(vertex.getLabel());
                if (nameMatches && labelMatches) result.add(vertex);
            }
            return result.stream().limit(search.getLimit()).toList();
        }

        @Override
        public List<Triple<GraphVertex, GraphEdge, GraphVertex>> getEdges(List<String> ids) {
            return ids.stream().map(edges::get).toList();
        }

        @Override
        public List<Triple<GraphVertex, GraphEdge, GraphVertex>> searchEdges(GraphEdgeSearch search) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Triple<GraphVertex, GraphEdge, GraphVertex> getEdge(GraphEdgeSearch search) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Triple<GraphVertex, GraphEdge, GraphVertex> addEdge(GraphEdgeAddInfo addInfo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Triple<GraphVertex, GraphEdge, GraphVertex> getEdgeByVertexIds(
                String sourceId, String targetId, String relationType, Boolean polarity) {
            return edges.values().stream()
                    .filter(edge -> edge.getMiddle().getStartId().equals(sourceId)
                            && edge.getMiddle().getEndId().equals(targetId)
                            && (relationType == null || relationType.equals(
                            edge.getMiddle().getRelationType()))
                            && (polarity == null || polarity.equals(
                            edge.getMiddle().getPolarity())))
                    .findFirst().orElse(null);
        }

        @Override
        public Triple<GraphVertex, GraphEdge, GraphVertex> addEdgeByVertexIds(String sourceId,
                                                                              String targetId,
                                                                              GraphEdge edge) {
            edgeAddAttempts++;
            if (refuseEdgeCreate) {
                return null;
            }
            edge.setId(String.valueOf(nextEdgeId++));
            edge.setStartId(sourceId);
            edge.setEndId(targetId);
            lastEdgeSourceId = sourceId;
            lastEdgeTargetId = targetId;
            Triple<GraphVertex, GraphEdge, GraphVertex> triple = Triple.of(
                    vertices.get(sourceId), edge, vertices.get(targetId));
            edges.put(edge.getId(), triple);
            return returnNullAfterEdgeCreate ? null : triple;
        }

        @Override
        public Triple<GraphVertex, GraphEdge, GraphVertex> updateEdge(GraphEdgeEditInfo edgeEditInfo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Triple<GraphVertex, GraphEdge, GraphVertex> updateEdgeById(String edgeId,
                                                                          GraphEdge newData) {
            Triple<GraphVertex, GraphEdge, GraphVertex> triple = edges.get(edgeId);
            GraphEdge edge = triple.getMiddle();
            edge.setTextSegmentId(newData.getTextSegmentId());
            edge.setDescription(newData.getDescription());
            edge.setWeight(newData.getWeight());
            edge.setRelationType(newData.getRelationType());
            edge.setPolarity(newData.getPolarity());
            edge.setStatus(newData.getStatus());
            edge.setProperties(newData.getProperties());
            edge.setEvidenceCount(newData.getEvidenceCount());
            edge.setMetadata(new HashMap<>(newData.getMetadata()));
            return triple;
        }

        @Override public void deleteVertices(GraphSearchCondition filter, boolean includeEdges) { }
        @Override public void deleteEdges(GraphSearchCondition filter) { }
        @Override public void deleteVerticesByIds(List<String> ids) { }
        @Override public void deleteEdgesByIds(List<String> ids) { }
    }
}
