package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.ZhiMeshStringUtil;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphElementSourceService;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.DocumentTransformer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.segment.TextSegmentTransformer;
import dev.langchain4j.spi.data.document.splitter.DocumentSplitterFactory;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Triple;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static dev.langchain4j.spi.ServiceHelper.loadFactories;
import static java.util.Collections.singletonList;

@Builder
@AllArgsConstructor
@Slf4j
public class GraphStoreIngestor {

    private final DocumentTransformer documentTransformer;
    private final TextSegmentTransformer textSegmentTransformer;
    private final GraphStore graphStore;
    private final DocumentSplitter documentSplitter;
    private final Function<List<TextSegment>, List<Triple<TextSegment, String, String>>> segmentsFunction;

    /**
     * 查询时 where 语句的条件字段名
     */
    private final List<String> identifyColumns;

    /**
     * Retained for constructor/configuration compatibility. Appendable source
     * values are now rebuilt from graph-element provenance instead of mutating
     * a shared metadata map.
     */
    @SuppressWarnings("unused")
    private final List<String> appendColumns;

    /** Immutable build identity copied into every per-segment contribution. */
    private final String chunkSetUuid;
    private final Long graphModelId;
    private final String graphIndexVersionUuid;
    /** Optional test/embedded override; production resolves the Spring bean lazily. */
    private final KnowledgeBaseGraphElementSourceService elementSourceService;

    public GraphStoreIngestor(DocumentTransformer documentTransformer,
                              DocumentSplitter documentSplitter,
                              GraphStore graphStore,
                              TextSegmentTransformer textSegmentTransformer,
                              Function<List<TextSegment>, List<Triple<TextSegment, String, String>>> segmentsFunction,
                              String identifyColumns,
                              String appendColumns) {
        this.graphStore = ensureNotNull(graphStore, "graphStore");
        this.documentTransformer = documentTransformer;
        this.documentSplitter = getOrDefault(documentSplitter, GraphStoreIngestor::loadDocumentSplitter);
        this.textSegmentTransformer = textSegmentTransformer;
        this.segmentsFunction = segmentsFunction;
        this.identifyColumns = Arrays.asList(identifyColumns.split(","));
        this.appendColumns = Arrays.asList(appendColumns.split(","));
        this.chunkSetUuid = "";
        this.graphModelId = 0L;
        this.graphIndexVersionUuid = "";
        this.elementSourceService = null;
    }

    private static DocumentSplitter loadDocumentSplitter() {
        Collection<DocumentSplitterFactory> factories = loadFactories(DocumentSplitterFactory.class);
        if (factories.size() > 1) {
            throw new RuntimeException("Conflict: multiple document splitters have been found in the classpath. " +
                                       "Please explicitly specify the one you wish to use.");
        }

        for (DocumentSplitterFactory factory : factories) {
            DocumentSplitter documentSplitter = factory.create();
            log.debug("Loaded the following document splitter through SPI: {}", documentSplitter);
            return documentSplitter;
        }

        return null;
    }

    public void ingest(Document document) {
        ingest(singletonList(document));
    }

    public void ingest(List<Document> documents) {

        log.info("Starting to ingest {} documents", documents.size());

        if (documentTransformer != null) {
            documents = documentTransformer.transformAll(documents);
            log.info("Documents were transformed into {} documents", documents.size());
        }
        List<TextSegment> segments;
        if (documentSplitter != null) {
            segments = documentSplitter.splitAll(documents);
            log.info("Documents were split into {} text segments", segments.size());
        } else {
            segments = documents.stream()
                    .map(Document::toTextSegment)
                    .toList();
        }
        if (textSegmentTransformer != null) {
            segments = textSegmentTransformer.transformAll(segments);
            log.info("Text segments were transformed into {} text segments", documents.size());
        }

        // TODO handle failures, parallelize
        log.info("Starting to extract {} text segments", segments.size());
        List<Triple<TextSegment, String, String>> segmentIdToAiResponse = segmentsFunction.apply(segments);
        // Entity and edge persistence is a shared read-create-update workflow.
        // Keep this short critical section serialized while allowing the LLM
        // extraction above to run concurrently across documents.
        synchronized (graphStore) {
        for (Triple<TextSegment, String, String> triple : segmentIdToAiResponse) {
            TextSegment segment = triple.getLeft();
            String textSegmentId = triple.getMiddle();
            String response = triple.getRight();
            // Never pass the mutable segment map through multiple graph
            // elements. Every vertex/edge receives its own copy below.
            Map<String, Object> segmentMetadata = Map.copyOf(segment.metadata().toMap());
            String kbUuid = String.valueOf(segmentMetadata.get(ZhiMeshConstant.MetadataKey.KB_UUID));
            String kbItemUuid = String.valueOf(segmentMetadata.get(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID));
            log.info("Finished extract {} text segments", segments.size());
            log.info("Graph extraction completed, segmentId:{},responseChars:{}",
                    textSegmentId, StringUtils.length(response));
            // TODO handle failures, parallelize
            log.info("Starting to store {} text segments into the graph store", segments.size());
            if (StringUtils.isBlank(response)) {
                log.info("No graphable entities or relationships extracted; skipping segment:{}", textSegmentId);
                continue;
            }

            Filter filter = null;
            for (Map.Entry<String, Object> entry : segmentMetadata.entrySet()) {
                boolean contain = identifyColumns.contains(entry.getKey());
                if (contain) {
                    if (null == filter) {
                        filter = new IsEqualTo(entry.getKey(), entry.getValue());
                    } else {
                        filter = filter.and(new IsEqualTo(entry.getKey(), entry.getValue()));
                    }
                }
            }
            if (null == filter) {
                throw new BaseException(ErrorEnum.B_GRAPH_FILTER_NOT_FOUND);
            }

            ParsedGraphRecords parsed = parseGraphRecords(response);
            Map<String, GraphVertex> verticesByName = new LinkedHashMap<>();
            List<GraphVertex> knowledgeBaseVertices = new ArrayList<>(graphStore.searchVertices(
                    GraphVertexSearch.builder()
                            .limit(10000)
                            .metadataFilter(filter)
                            .build()));
            for (EntityRecord entity : mergeEntityRecords(parsed.entities())) {
                GraphVertex storedVertex = storeEntity(entity, textSegmentId, filter,
                        segmentMetadata, kbUuid, kbItemUuid, knowledgeBaseVertices);
                for (String identityName : entity.identityNames()) {
                    verticesByName.put(GraphEntityTypeResolver.normalizeName(identityName), storedVertex);
                }
            }

            for (RelationshipRecord relationship : parsed.relationships()) {
                GraphVertex sourceVertex = resolveRelationshipEndpoint(relationship.source(),
                        verticesByName, textSegmentId, kbUuid, kbItemUuid);
                GraphVertex targetVertex = resolveRelationshipEndpoint(relationship.target(),
                        verticesByName, textSegmentId, kbUuid, kbItemUuid);
                storeRelationship(relationship, sourceVertex, targetVertex, textSegmentId,
                        segmentMetadata, kbUuid, kbItemUuid);
            }
            boolean storedGraphRecord = !parsed.entities().isEmpty() || !parsed.relationships().isEmpty();
            if (!storedGraphRecord) {
                throw new IllegalStateException("Graph extraction response contains no storable records for segment " + textSegmentId);
            }
        }

        log.info("Finished storing {} text segments into the graph store", segments.size());
        }
    }

    private GraphVertex storeEntity(EntityRecord entity, String segmentUuid, Filter metadataFilter,
                                    Map<String, Object> segmentMetadata, String kbUuid,
                                    String kbItemUuid, List<GraphVertex> knowledgeBaseVertices) {
        String storedName = entity.canonicalName();
        if (GraphEntityTypeResolver.isUnknown(entity.type())) {
            // The extraction quality gate normally repairs or rejects such a
            // record first. Reaching this point means the type survived every
            // safeguard, so fail closed instead of persisting an UNKNOWN vertex.
            throw new IllegalStateException("Refusing to store graph entity with unresolved type, "
                    + "name:" + storedName + ", segmentId:" + segmentUuid
                    + ", kbUuid:" + kbUuid + ", kbItemUuid:" + kbItemUuid);
        }
        List<GraphVertex> identityMatches = knowledgeBaseVertices.stream()
                .filter(vertex -> identitiesOverlap(entity.identityNames(), vertexIdentityNames(vertex)))
                .sorted(Comparator.comparingInt(
                        (GraphVertex vertex) -> identityMatchScore(entity, vertex)).reversed())
                .toList();
        GraphVertex storedVertex;
        if (CollectionUtils.isNotEmpty(identityMatches)) {
            storedVertex = identityMatches.get(0);
            if (!entity.type().equalsIgnoreCase(storedVertex.getLabel())) {
                log.warn("Graph entity type conflict resolved to existing KB identity, kbUuid:{}, "
                                + "canonicalName:{}, requestedType:{}, existingType:{}, vertexId:{}",
                        kbUuid, storedName, entity.type(), storedVertex.getLabel(), storedVertex.getId());
            }
        } else {
            // The KB identity snapshot is bounded, so a same-name/same-type
            // vertex can exist outside it. Look it up before creating anything;
            // blindly adding a vertex here is what produced duplicate
            // same-name entities on large graphs.
            storedVertex = graphStore.getVertex(GraphVertexSearch.builder()
                    .label(entity.type())
                    .names(List.of(storedName))
                    .metadataFilter(metadataFilter)
                    .limit(1)
                    .build());
            if (storedVertex == null) {
                Map<String, Object> entityMetadata = new HashMap<>(segmentMetadata);
                graphStore.addVertex(GraphVertex.builder()
                        .label(entity.type())
                        .name(storedName)
                        .canonicalName(storedName)
                        .aliases(entity.aliases())
                        .properties(entity.properties())
                        .salience(entity.salience())
                        .textSegmentId(segmentUuid)
                        .description(entity.description())
                        .metadata(entityMetadata)
                        .build());
                storedVertex = graphStore.getVertex(GraphVertexSearch.builder()
                        .label(entity.type())
                        .names(List.of(storedName))
                        .metadataFilter(metadataFilter)
                        .limit(1)
                        .build());
            }
        }
        if (storedVertex == null) {
            throw new IllegalStateException("Unable to resolve stored graph entity, name:"
                    + storedName + ", type:" + entity.type() + ", segmentId:" + segmentUuid
                    + ", kbUuid:" + kbUuid + ", kbItemUuid:" + kbItemUuid);
        }

        // Search/create returning a Java object is not sufficient proof that its
        // graph-native id is still valid. Re-resolve before provenance is bound
        // to that id, and only accept a unique same-name/same-type replacement.
        storedVertex = requirePersistedEntity(entity, storedVertex, storedName,
                metadataFilter, segmentUuid, kbUuid, kbItemUuid);

        KnowledgeBaseGraphElementSourceService sourceService = sourceService();
        sourceService.record(kbUuid, kbItemUuid, segmentUuid,
                KnowledgeBaseGraphElementSourceService.VERTEX, storedVertex.getId(),
                entity.description(), null, entity.canonicalName(), entity.aliases(),
                entity.properties(), entity.salience(), "", null, "",
                chunkSetUuid, chunkUuid(segmentMetadata), graphModelId, graphIndexVersionUuid);
        KnowledgeBaseGraphElementSourceService.ContributionAggregate aggregate =
                sourceService.aggregate(KnowledgeBaseGraphElementSourceService.VERTEX,
                        storedVertex.getId());
        GraphVertex updated = graphStore.updateVertexById(storedVertex.getId(), GraphVertex.builder()
                .name(StringUtils.defaultIfBlank(aggregate.canonicalName(), storedVertex.getName()))
                .canonicalName(StringUtils.defaultIfBlank(aggregate.canonicalName(), storedVertex.getName()))
                .aliases(aggregate.aliases())
                .properties(aggregate.properties())
                .salience(aggregate.salience())
                .textSegmentId(aggregate.textSegmentId())
                .description(aggregate.description())
                .metadata(new HashMap<>(aggregate.metadata()))
                .build());
        if (updated == null) {
            throw new IllegalStateException("Graph vertex disappeared while materializing contributions, name:"
                    + storedName + ", type:" + entity.type() + ", id:" + storedVertex.getId()
                    + ", segmentId:" + segmentUuid + ", kbUuid:" + kbUuid
                    + ", kbItemUuid:" + kbItemUuid);
        }
        if (!storedVertex.getId().equals(updated.getId())) {
            throw new IllegalStateException("Graph vertex identity changed during update, name:"
                    + storedName + ", expectedId:" + storedVertex.getId()
                    + ", actualId:" + updated.getId() + ", segmentId:" + segmentUuid);
        }
        knowledgeBaseVertices.removeIf(vertex -> updated.getId().equals(vertex.getId()));
        knowledgeBaseVertices.add(updated);
        return updated;
    }

    private void storeRelationship(RelationshipRecord relationship, GraphVertex sourceVertex,
                                   GraphVertex targetVertex, String segmentUuid,
                                   Map<String, Object> segmentMetadata, String kbUuid,
                                   String kbItemUuid) {
        GraphVertex persistedSource = requirePersistedRelationshipEndpoint(
                "source", sourceVertex, relationship, segmentUuid, kbUuid, kbItemUuid);
        GraphVertex persistedTarget = requirePersistedRelationshipEndpoint(
                "target", targetVertex, relationship, segmentUuid, kbUuid, kbItemUuid);
        Triple<GraphVertex, GraphEdge, GraphVertex> stored = graphStore.getEdgeByVertexIds(
                persistedSource.getId(), persistedTarget.getId(),
                relationship.relationType(), relationship.polarity());
        if (stored == null) {
            Map<String, Object> edgeMetadata = new HashMap<>(segmentMetadata);
            stored = graphStore.addEdgeByVertexIds(persistedSource.getId(), persistedTarget.getId(),
                    GraphEdge.builder()
                            .sourceName(persistedSource.getName())
                            .targetName(persistedTarget.getName())
                            .weight(relationship.weight())
                            .relationType(relationship.relationType())
                            .polarity(relationship.polarity())
                            .status(relationship.status())
                            .properties(relationship.properties())
                            .evidenceCount(1)
                            .metadata(edgeMetadata)
                            .textSegmentId(segmentUuid)
                            .description(relationship.description())
                            .build());
        }
        if (stored == null || stored.getMiddle() == null) {
            // Some graph drivers can complete CREATE while failing to expose the
            // returned row. Read once before declaring the document failed; this
            // avoids a duplicate-creating write retry.
            log.warn("Graph relationship create returned no edge; verifying persisted edge, "
                            + "segmentId:{}, sourceName:{}, sourceId:{}, targetName:{}, targetId:{}",
                    segmentUuid, relationship.source(), persistedSource.getId(),
                    relationship.target(), persistedTarget.getId());
            stored = graphStore.getEdgeByVertexIds(
                    persistedSource.getId(), persistedTarget.getId(),
                    relationship.relationType(), relationship.polarity());
        }
        if (stored == null || stored.getMiddle() == null) {
            boolean sourceExists = graphStore.getVertexById(persistedSource.getId()) != null;
            boolean targetExists = graphStore.getVertexById(persistedTarget.getId()) != null;
            throw new IllegalStateException("Unable to store graph relationship, segmentId:"
                    + segmentUuid + ", sourceName:" + relationship.source()
                    + ", sourceType:" + persistedSource.getLabel()
                    + ", sourceId:" + persistedSource.getId()
                    + ", sourceExists:" + sourceExists
                    + ", targetName:" + relationship.target()
                    + ", targetType:" + persistedTarget.getLabel()
                    + ", targetId:" + persistedTarget.getId()
                    + ", targetExists:" + targetExists
                    + ", kbUuid:" + kbUuid + ", kbItemUuid:" + kbItemUuid);
        }

        GraphEdge edge = stored.getMiddle();
        KnowledgeBaseGraphElementSourceService sourceService = sourceService();
        sourceService.record(kbUuid, kbItemUuid, segmentUuid,
                KnowledgeBaseGraphElementSourceService.EDGE, edge.getId(),
                relationship.description(), relationship.weight(), "", List.of(),
                relationship.properties(), null, relationship.relationType(),
                relationship.polarity(), relationship.status(), chunkSetUuid,
                chunkUuid(segmentMetadata), graphModelId, graphIndexVersionUuid);
        KnowledgeBaseGraphElementSourceService.ContributionAggregate aggregate =
                sourceService.aggregate(KnowledgeBaseGraphElementSourceService.EDGE, edge.getId());
        Triple<GraphVertex, GraphEdge, GraphVertex> updatedEdge = graphStore.updateEdgeById(
                edge.getId(), GraphEdge.builder()
                        .textSegmentId(aggregate.textSegmentId())
                        .description(aggregate.description())
                        .weight(aggregate.weight())
                        .relationType(aggregate.relationType())
                        .polarity(aggregate.polarity())
                        .status(aggregate.status())
                        .properties(aggregate.properties())
                        .evidenceCount(aggregate.sourceCount())
                        .metadata(new HashMap<>(aggregate.metadata()))
                        .build());
        if (updatedEdge == null || updatedEdge.getMiddle() == null) {
            throw new IllegalStateException("Graph relationship disappeared while materializing contributions, "
                    + "edgeId:" + edge.getId() + ", segmentId:" + segmentUuid
                    + ", sourceName:" + relationship.source()
                    + ", targetName:" + relationship.target()
                    + ", kbUuid:" + kbUuid + ", kbItemUuid:" + kbItemUuid);
        }
    }

    private GraphVertex requirePersistedEntity(EntityRecord entity, GraphVertex candidate,
                                                String storedName, Filter metadataFilter,
                                                String segmentUuid, String kbUuid,
                                                String kbItemUuid) {
        GraphVertex persisted = graphStore.getVertexById(candidate.getId());
        if (persisted != null) {
            return persisted;
        }

        List<GraphVertex> replacements = graphStore.searchVertices(
                GraphVertexSearch.builder()
                        .names(vertexIdentityNames(candidate))
                        .metadataFilter(metadataFilter)
                        .limit(10)
                        .build());
        List<GraphVertex> matchingReplacements = replacements.stream()
                .filter(vertex -> identitiesOverlap(entity.identityNames(), vertexIdentityNames(vertex)))
                .sorted(Comparator.comparingInt((GraphVertex vertex) ->
                        candidate.getLabel().equalsIgnoreCase(vertex.getLabel()) ? 1 : 0).reversed())
                .toList();
        if (!matchingReplacements.isEmpty()) {
            GraphVertex replacement = matchingReplacements.get(0);
            log.warn("Re-resolved graph vertex before recording provenance, name:{}, type:{}, "
                            + "staleId:{}, replacementId:{}, segmentId:{}",
                    storedName, entity.type(), candidate.getId(), replacement.getId(), segmentUuid);
            return replacement;
        }

        throw new IllegalStateException("Graph vertex id is not persisted and cannot be uniquely re-resolved, "
                + "name:" + storedName + ", type:" + entity.type() + ", staleId:"
                + candidate.getId() + ", replacementCount:" + matchingReplacements.size()
                + ", segmentId:" + segmentUuid + ", kbUuid:" + kbUuid
                + ", kbItemUuid:" + kbItemUuid);
    }

    private GraphVertex requirePersistedRelationshipEndpoint(
            String role, GraphVertex candidate, RelationshipRecord relationship,
            String segmentUuid, String kbUuid, String kbItemUuid) {
        GraphVertex persisted = graphStore.getVertexById(candidate.getId());
        if (persisted != null) {
            return persisted;
        }
        throw new IllegalStateException("Relationship endpoint is missing from graph store, role:"
                + role + ", segmentId:" + segmentUuid + ", relationship:"
                + relationship.source() + " -> " + relationship.target()
                + ", endpointName:" + candidate.getName()
                + ", endpointType:" + candidate.getLabel()
                + ", endpointId:" + candidate.getId()
                + ", kbUuid:" + kbUuid + ", kbItemUuid:" + kbItemUuid);
    }

    private GraphVertex resolveRelationshipEndpoint(String name,
                                                     Map<String, GraphVertex> verticesByName,
                                                     String segmentUuid, String kbUuid,
                                                     String kbItemUuid) {
        GraphVertex vertex = verticesByName.get(GraphEntityTypeResolver.normalizeName(name));
        if (vertex != null) {
            return vertex;
        }
        // GraphRag's quality gate normally prevents this path. Failing closed
        // is safer than silently creating UNKNOWN nodes or choosing one of two
        // same-name, different-type vertices and wiring the edge incorrectly.
        throw new IllegalStateException("Relationship endpoint was not emitted as an entity: " + name
                + ", segment:" + segmentUuid + ", kbUuid:" + kbUuid + ", kbItemUuid:" + kbItemUuid);
    }

    private List<EntityRecord> mergeEntityRecords(List<EntityRecord> records) {
        List<List<EntityRecord>> identityGroups = new ArrayList<>();
        for (EntityRecord record : records) {
            List<List<EntityRecord>> overlapping = new ArrayList<>();
            for (List<EntityRecord> group : identityGroups) {
                if (group.stream().anyMatch(existing ->
                        identitiesOverlap(record.identityNames(), existing.identityNames()))) {
                    overlapping.add(group);
                }
            }
            if (overlapping.isEmpty()) {
                List<EntityRecord> group = new ArrayList<>();
                group.add(record);
                identityGroups.add(group);
                continue;
            }
            // Identity must group transitively. When one record bridges two
            // groups through shared aliases, keeping them apart stores the same
            // entity twice in one segment and the second write overwrites that
            // segment's contribution row for the vertex.
            List<EntityRecord> target = overlapping.get(0);
            for (int index = 1; index < overlapping.size(); index++) {
                List<EntityRecord> absorbed = overlapping.get(index);
                target.addAll(absorbed);
                identityGroups.remove(absorbed);
            }
            target.add(record);
        }
        List<EntityRecord> merged = new ArrayList<>();
        for (List<EntityRecord> sameName : identityGroups) {
            EntityRecord first = sameName.get(0);
            String description = "";
            List<String> candidateTypes = new ArrayList<>();
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            Map<String, Object> properties = new LinkedHashMap<>();
            String canonicalName = first.canonicalName();
            double salience = first.salience();
            for (EntityRecord record : sameName) {
                description = appendUniqueLine(description, record.description());
                candidateTypes.add(record.type());
                aliases.addAll(record.aliases());
                aliases.add(record.name());
                properties.putAll(record.properties());
                if (record.canonicalName().length() > canonicalName.length()) {
                    aliases.add(canonicalName);
                    canonicalName = record.canonicalName();
                } else if (!record.canonicalName().equals(canonicalName)) {
                    aliases.add(record.canonicalName());
                }
                salience = Math.max(salience, record.salience());
            }
            String resolvedCanonicalName = canonicalName;
            aliases.removeIf(alias -> GraphEntityTypeResolver.normalizeName(alias)
                    .equals(GraphEntityTypeResolver.normalizeName(resolvedCanonicalName)));
            String resolvedType = GraphEntityTypeResolver.resolve(first.name(), candidateTypes,
                    description);
            if (candidateTypes.stream().distinct().count() > 1) {
                log.warn("Resolved conflicting entity types inside one extraction, name:{}, candidates:{}, resolved:{}",
                        first.name(), candidateTypes, resolvedType);
            }
            merged.add(new EntityRecord(first.name(), resolvedType, description, resolvedCanonicalName,
                    List.copyOf(aliases), Map.copyOf(properties), salience));
        }
        return merged;
    }

    private ParsedGraphRecords parseGraphRecords(String response) {
        List<EntityRecord> entities = new ArrayList<>();
        List<RelationshipRecord> relationships = new ArrayList<>();
        String[] rows = StringUtils.split(response, ZhiMeshConstant.GRAPH_RECORD_DELIMITER);
        if (rows == null) {
            return new ParsedGraphRecords(List.of(), List.of());
        }
        for (String row : rows) {
            String graphRow = row.replaceAll("^\\(|\\)$", "");
            String[] attributes = StringUtils.split(graphRow, ZhiMeshConstant.GRAPH_TUPLE_DELIMITER);
            if (attributes == null || attributes.length < 4) {
                continue;
            }
            if (attributes[0].contains("\"entity\"") || attributes[0].contains("\"实体\"")) {
                String name = ZhiMeshStringUtil.clearStr(attributes[1]).trim();
                String type = ZhiMeshStringUtil.clearStr(attributes[2].toUpperCase())
                        .replaceAll("[^a-zA-Z0-9\\s\\u4E00-\\u9FA5]+", "")
                        .replace(" ", "");
                String description = ZhiMeshStringUtil.clearStr(attributes[3]);
                String canonicalName = attributes.length > 4
                        ? ZhiMeshStringUtil.clearStr(attributes[4]).trim() : name;
                if (StringUtils.isBlank(canonicalName)) canonicalName = name;
                List<String> aliases = attributes.length > 5
                        ? parseStringList(attributes[5]) : List.of();
                Map<String, Object> properties = attributes.length > 6
                        ? parseProperties(attributes[6]) : Map.of();
                double salience = attributes.length > 7
                        ? Math.max(1D, Math.min(10D, NumberUtils.toDouble(attributes[7], 5D)))
                        : 5D;
                entities.add(new EntityRecord(name,
                        GraphEntityTypeResolver.resolve(name, type, description), description,
                        canonicalName, aliases, properties, salience));
            } else if (attributes[0].contains("\"relationship\"")
                    || attributes[0].contains("\"关系\"")) {
                String source = ZhiMeshStringUtil.clearStr(attributes[1]).trim();
                String target = ZhiMeshStringUtil.clearStr(attributes[2]).trim();
                String description = ZhiMeshStringUtil.clearStr(attributes[3]);
                double weight = attributes.length > 4
                        ? Math.max(0D, Math.min(10D, NumberUtils.toDouble(attributes[4], 1D)))
                        : 1D;
                String relationType = attributes.length > 5
                        ? GraphRelationshipSemantics.normalizeType(attributes[5])
                        : GraphRelationshipSemantics.DEFAULT_TYPE;
                boolean polarity = attributes.length <= 6
                        || Boolean.parseBoolean(attributes[6].trim());
                String status = attributes.length > 7
                        ? GraphRelationshipSemantics.normalizeStatus(attributes[7])
                        : GraphRelationshipSemantics.DEFAULT_STATUS;
                Map<String, Object> properties = attributes.length > 8
                        ? parseProperties(attributes[8]) : Map.of();
                relationships.add(new RelationshipRecord(source, target, description, weight,
                        relationType, polarity, status, properties));
            }
        }
        return new ParsedGraphRecords(List.copyOf(entities), List.copyOf(relationships));
    }

    private KnowledgeBaseGraphElementSourceService sourceService() {
        return elementSourceService != null
                ? elementSourceService
                : SpringUtil.getBean(KnowledgeBaseGraphElementSourceService.class);
    }

    private String chunkUuid(Map<String, Object> metadata) {
        Object value = metadata.get(ZhiMeshConstant.MetadataKey.CHUNK_UUID);
        return value == null ? "" : String.valueOf(value);
    }

    private String appendUniqueLine(String existing, String value) {
        Set<String> lines = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(existing)) {
            Arrays.stream(existing.split("\\R")).map(String::trim)
                    .filter(StringUtils::isNotBlank).forEach(lines::add);
        }
        if (StringUtils.isNotBlank(value)) {
            lines.add(value.trim());
        }
        return String.join("\n", lines);
    }

    private List<String> parseStringList(String value) {
        List<String> parsed = JsonUtil.toList(value.trim(), String.class);
        return parsed == null ? List.of() : parsed.stream()
                .filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
    }

    private Map<String, Object> parseProperties(String value) {
        try {
            Map<String, Object> parsed = new LinkedHashMap<>(JsonUtil.toMap(value.trim()));
            parsed.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
            return Map.copyOf(parsed);
        } catch (RuntimeException exception) {
            log.warn("Ignoring malformed graph properties JSON");
            return Map.of();
        }
    }

    private boolean identitiesOverlap(Collection<String> left, Collection<String> right) {
        Set<String> normalizedLeft = left.stream()
                .filter(StringUtils::isNotBlank)
                .map(GraphEntityTypeResolver::normalizeName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> normalizedRight = right.stream().filter(StringUtils::isNotBlank)
                .map(GraphEntityTypeResolver::normalizeName)
                .collect(java.util.stream.Collectors.toSet());
        for (String leftName : normalizedLeft) {
            for (String rightName : normalizedRight) {
                if (leftName.equals(rightName)) return true;
                String shorter = leftName.length() <= rightName.length() ? leftName : rightName;
                String longer = leftName.length() > rightName.length() ? leftName : rightName;
                // Common Chinese abbreviations normally retain at least four
                // characters of the full name. This catches 公司/集团/model
                // suffix variants without merging generic two-character nouns.
                if (shorter.length() >= 4 && longer.contains(shorter)) return true;
            }
        }
        return false;
    }

    private List<String> vertexIdentityNames(GraphVertex vertex) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(vertex.getName());
        values.add(vertex.getCanonicalName());
        if (vertex.getAliases() != null) values.addAll(vertex.getAliases());
        values.removeIf(StringUtils::isBlank);
        return List.copyOf(values);
    }

    private int identityMatchScore(EntityRecord entity, GraphVertex vertex) {
        int score = 0;
        if (GraphEntityTypeResolver.normalizeName(entity.canonicalName())
                .equals(GraphEntityTypeResolver.normalizeName(vertex.getCanonicalName()))) {
            score += 100;
        }
        if (entity.identityNames().stream().map(GraphEntityTypeResolver::normalizeName)
                .anyMatch(name -> name.equals(GraphEntityTypeResolver.normalizeName(vertex.getName())))) {
            score += 50;
        }
        if (entity.type().equalsIgnoreCase(vertex.getLabel())) score += 10;
        return score;
    }

    private record EntityRecord(String name, String type, String description,
                                String canonicalName, List<String> aliases,
                                Map<String, Object> properties, double salience) {
        List<String> identityNames() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            values.add(name);
            values.add(canonicalName);
            values.addAll(aliases);
            values.removeIf(StringUtils::isBlank);
            return List.copyOf(values);
        }
    }

    private record RelationshipRecord(String source, String target, String description,
                                      double weight, String relationType, boolean polarity,
                                      String status, Map<String, Object> properties) {
    }

    private record ParsedGraphRecords(List<EntityRecord> entities,
                                      List<RelationshipRecord> relationships) {
    }
}
