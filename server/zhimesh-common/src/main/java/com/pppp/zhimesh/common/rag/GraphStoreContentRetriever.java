package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.entity.KnowledgeBaseGraphSegment;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphElementSourceService;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphSegmentService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.GraphEdge;
import com.pppp.zhimesh.common.vo.GraphEdgeSearch;
import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import com.pppp.zhimesh.common.vo.GraphVertex;
import com.pppp.zhimesh.common.vo.GraphVertexSearch;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.enums.ErrorEnum.B_BREAK_SEARCH;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureGreaterThanZero;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

@Slf4j
public class GraphStoreContentRetriever implements ContentRetriever {
    public static final Function<Query, Integer> DEFAULT_MAX_RESULTS = query -> 3;
    public static final Function<Query, Filter> DEFAULT_FILTER = query -> null;
    public static final String DEFAULT_DISPLAY_NAME = "Default";
    private static final int MAX_ENTITY_CANDIDATES = 200;
    private static final int MAX_PRECISE_RELATION_DOCUMENTS = 4;
    static final String GRAPH_SOURCE_DOCUMENT_COUNT = "graph_source_document_count";
    static final String GRAPH_PROVENANCE_AMBIGUOUS = "graph_provenance_ambiguous";

    private final GraphStore graphStore;
    private final ChatModel chatModel;
    private final Function<Query, Integer> maxResultsProvider;
    private final Function<Query, Filter> filterProvider;
    private final String displayName;
    private final boolean breakIfSearchMissed;
    private final int hopDepth;
    private final RefGraphDto kbQaRecordRefGraphDto = RefGraphDto.builder().vertices(Collections.emptyList()).edges(Collections.emptyList()).entitiesFromQuestion(Collections.emptyList()).build();
    private List<String> directMatchedEntities = List.of();
    private List<String> llmExtractedEntities = List.of();
    private List<Map<String, Object>> matchedAnchors = List.of();
    private List<Map<String, Object>> traversedEdges = List.of();
    private List<String> resolvedSourceSegmentUuids = List.of();
    private List<String> sourceCandidateSegmentUuids = List.of();
    private String traceStatus = "not_run";
    private String entityExtractionMode = "not_run";
    private String entityExtractionFormat = "not_run";
    private boolean entityExtractionResponseNonBlank;
    private int entityExtractionParsedCount;
    private String entityExtractionErrorType;
    private String sourceResolutionErrorType;

    @Builder
    private GraphStoreContentRetriever(String displayName, GraphStore graphStore, ChatModel chatModel,
                                       Function<Query, Integer> dynamicMaxResults, Function<Query, Filter> dynamicFilter,
                                       Boolean breakIfSearchMissed, Integer hopDepth) {
        this.displayName = getOrDefault(displayName, DEFAULT_DISPLAY_NAME);
        this.graphStore = ensureNotNull(graphStore, "graphStore");
        this.chatModel = ensureNotNull(chatModel, "ChatModel");
        this.maxResultsProvider = getOrDefault(dynamicMaxResults, DEFAULT_MAX_RESULTS);
        this.filterProvider = getOrDefault(dynamicFilter, DEFAULT_FILTER);
        this.breakIfSearchMissed = breakIfSearchMissed;
        this.hopDepth = hopDepth == null ? 1 : Math.max(1, Math.min(2, hopDepth));
    }

    @Override
    public List<Content> retrieve(Query query) {
        resetTrace();
        Filter filter = filterProvider.apply(query);
        int maxResults = maxResultsProvider.apply(query);
        List<GraphVertex> candidates = graphStore.searchVertices(GraphVertexSearch.builder()
                .metadataFilter(filter).limit(MAX_ENTITY_CANDIDATES).build());
        List<GraphVertex> directAnchors = GraphEntityNameMatcher.matchDirectMentions(
                query.text(), candidates, maxResults);
        directMatchedEntities = directAnchors.stream().map(GraphVertex::getName).toList();

        Set<String> entities = Collections.emptySet();
        Exception extractionFailure = null;
        if (directAnchors.isEmpty()) {
            entityExtractionMode = "llm_fallback";
            try {
                GraphQueryEntityParser.ParseResult parseResult = extractEntities(query.text());
                entities = parseResult.entities();
                llmExtractedEntities = entities.stream().sorted().toList();
                entityExtractionFormat = parseResult.format();
                entityExtractionResponseNonBlank = parseResult.responseNonBlank();
                entityExtractionParsedCount = entities.size();
            } catch (Exception exception) {
                extractionFailure = exception;
                entityExtractionErrorType = exception.getClass().getSimpleName();
                entityExtractionFormat = "error";
                log.warn("Graph LLM entity extraction failed; no model response content was logged",
                        exception);
            }
        } else {
            entityExtractionMode = "direct_only";
            entityExtractionFormat = "skipped";
        }

        List<GraphVertex> llmAnchors = GraphEntityNameMatcher.match(entities, candidates, maxResults);
        Map<String, GraphVertex> anchorMap = new java.util.LinkedHashMap<>();
        directAnchors.forEach(vertex -> anchorMap.put(vertex.getId(), vertex));
        llmAnchors.forEach(vertex -> anchorMap.putIfAbsent(vertex.getId(), vertex));
        List<GraphVertex> anchors = anchorMap.values().stream().limit(maxResults).toList();
        Set<String> directAnchorIds = directAnchors.stream().map(GraphVertex::getId).collect(Collectors.toSet());
        Set<String> llmAnchorIds = llmAnchors.stream().map(GraphVertex::getId).collect(Collectors.toSet());
        matchedAnchors = anchors.stream().map(vertex -> anchorTrace(
                vertex, directAnchorIds.contains(vertex.getId()), llmAnchorIds.contains(vertex.getId()))).toList();
        if (anchors.isEmpty()) {
            traceStatus = "empty_no_anchor";
            if (extractionFailure != null) {
                throw new IllegalStateException("Graph entity extraction failed and no direct entity mention matched",
                        extractionFailure);
            }
            if (breakIfSearchMissed) {
                throw new BaseException(B_BREAK_SEARCH);
            }
            return Collections.emptyList();
        }

        List<Triple<GraphVertex, GraphEdge, GraphVertex>> traversedEdges =
                traverse(anchors, filter, maxResults, query.text());
        Map<String, GraphVertex> vertices = new LinkedHashMap<>();
        anchors.forEach(vertex -> vertices.put(vertex.getId(), vertex));
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (Triple<GraphVertex, GraphEdge, GraphVertex> edge : traversedEdges) {
            vertices.put(edge.getLeft().getId(), edge.getLeft());
            vertices.put(edge.getRight().getId(), edge.getRight());
            edges.put(edge.getMiddle().getId(), edge.getMiddle());
        }
        kbQaRecordRefGraphDto.setEntitiesFromQuestion(anchors.stream().map(GraphVertex::getName).toList());
        kbQaRecordRefGraphDto.setVertices(new ArrayList<>(vertices.values()));
        kbQaRecordRefGraphDto.setEdges(new ArrayList<>(edges.values()));

        int descriptionLimit = Math.max(0, SpringUtil.getBean(com.pppp.zhimesh.common.config.ZhiMeshProperties.class)
                .getRetrieval().getGraphDescriptionLimit());
        List<Content> sourceContents = resolveSourceEvidence(
                vertices.values(), edges.values(), query.text(), maxResults);
        if (sourceContents.isEmpty()) {
            // Legacy graphs without provenance still return their descriptions.
            anchors.stream().filter(vertex -> StringUtils.isNotBlank(vertex.getDescription()))
                    .map(this::toVertexContent).forEach(sourceContents::add);
        }
        List<Content> relationContents = edges.values().stream()
                .filter(edge -> StringUtils.isNotBlank(edge.getDescription()))
                .limit(descriptionLimit)
                .map(this::toRelationContent)
                .toList();
        List<Content> contents = orderEvidence(query.text(), sourceContents, relationContents,
                maxResults + descriptionLimit, maxResults, 2);
        traceStatus = contents.isEmpty() ? "empty_no_evidence" : "success";
        return contents;
    }

    private Content toRelationContent(GraphEdge edge) {
        Map<String, Object> metadata = new HashMap<>();
        if (edge.getMetadata() != null) metadata.putAll(edge.getMetadata());
        int sourceDocumentCount = Math.max(
                metadataValueCount(metadata.get(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID)),
                metadataValueCount(metadata.get(RetrievedCandidate.SOURCE_DOCUMENT_IDS)));
        metadata.put(GRAPH_SOURCE_DOCUMENT_COUNT, sourceDocumentCount);
        if (sourceDocumentCount > MAX_PRECISE_RELATION_DOCUMENTS) {
            // A relation shared by many documents is useful as a graph hint,
            // but it is not precise document-level evidence. Do not let the
            // evaluation layer count every linked document as a retrieval hit.
            metadata.remove(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID);
            metadata.remove(RetrievedCandidate.SOURCE_DOCUMENT_IDS);
            // LangChain4j Metadata does not support Boolean values.
            metadata.put(GRAPH_PROVENANCE_AMBIGUOUS, "true");
        }
        metadata.put(RetrievedCandidate.ROUTE, "graph");
        metadata.put(RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.GRAPH_RELATION);
        metadata.put(RetrievedCandidate.GRAPH_ELEMENT_ID, edge.getId());
        return Content.from(TextSegment.from(edge.getDescription(), new Metadata(metadata)));
    }

    private Content toVertexContent(GraphVertex vertex) {
        Map<String, Object> metadata = new HashMap<>();
        if (vertex.getMetadata() != null) metadata.putAll(vertex.getMetadata());
        metadata.put(RetrievedCandidate.ROUTE, "graph");
        metadata.put(RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.ORIGINAL_SEGMENT);
        metadata.put(RetrievedCandidate.GRAPH_ELEMENT_ID, vertex.getId());
        return Content.from(TextSegment.from(vertex.getDescription(), new Metadata(metadata)));
    }

    private List<Content> resolveSourceEvidence(Collection<GraphVertex> vertices,
                                                Collection<GraphEdge> edges,
                                                String question,
                                                int maxResults) {
        try {
            List<String> edgeIds = edges.stream().map(GraphEdge::getId)
                    .filter(StringUtils::isNotBlank).toList();
            List<String> vertexIds = vertices.stream().map(GraphVertex::getId)
                    .filter(StringUtils::isNotBlank).toList();
            KnowledgeBaseGraphElementSourceService sourceService =
                    SpringUtil.getBean(KnowledgeBaseGraphElementSourceService.class);
            int sourcePoolLimit = Math.max(maxResults, maxResults * 12);
            List<String> segmentUuids = sourceService.sourceSegmentUuidsForKnowledgeBases(
                    findKbUuids(vertices, edges), edgeIds, vertexIds,
                    Math.max(1, sourcePoolLimit), 12);
            sourceCandidateSegmentUuids = List.copyOf(segmentUuids);
            List<KnowledgeBaseGraphSegment> segments = SpringUtil
                    .getBean(KnowledgeBaseGraphSegmentService.class)
                    .listByUuidsPreservingOrder(segmentUuids);
            Map<String, Set<String>> elementIdsBySegment = sourceService.elementIdsBySegmentUuids(
                    findKbUuids(vertices, edges), segmentUuids);
            List<Content> evidence = new ArrayList<>();
            for (KnowledgeBaseGraphSegment segment : segments) {
                if (StringUtils.isBlank(segment.getRemark())) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(ZhiMeshConstant.MetadataKey.KB_UUID, segment.getKbUuid());
                metadata.put(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, segment.getKbItemUuid());
                metadata.put(RetrievedCandidate.GRAPH_SEGMENT_UUID, segment.getUuid());
                metadata.put(RetrievedCandidate.SEGMENT_UUID, segment.getUuid());
                Set<String> graphElementIds = elementIdsBySegment.getOrDefault(segment.getUuid(), Set.of());
                if (!graphElementIds.isEmpty()) {
                    metadata.put(RetrievedCandidate.GRAPH_ELEMENT_IDS,
                            String.join(",", graphElementIds));
                }
                metadata.put(RetrievedCandidate.ROUTE, "graph");
                metadata.put(RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.ORIGINAL_SEGMENT);
                evidence.add(Content.from(TextSegment.from(segment.getRemark(), new Metadata(metadata))));
            }
            List<Content> ranked = rankWithDocumentDiversity(
                    question, evidence, Math.max(1, maxResults), 2);
            resolvedSourceSegmentUuids = ranked.stream()
                    .map(Content::textSegment)
                    .map(TextSegment::metadata)
                    .map(metadata -> metadata.getString(RetrievedCandidate.SEGMENT_UUID))
                    .filter(StringUtils::isNotBlank)
                    .toList();
            return new ArrayList<>(ranked);
        } catch (Exception exception) {
            // Provenance enrichment is useful but must never turn a successful
            // graph traversal into a failed user request.
            log.warn("Unable to resolve graph elements to source segments; using graph descriptions", exception);
            sourceResolutionErrorType = exception.getClass().getSimpleName();
            return new ArrayList<>();
        }
    }

    private List<Triple<GraphVertex, GraphEdge, GraphVertex>> traverse(List<GraphVertex> anchors, Filter filter,
                                                                      int maxResults, String question) {
        Set<String> frontier = anchors.stream().map(GraphVertex::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> visited = new HashSet<>(frontier);
        Map<String, Triple<GraphVertex, GraphEdge, GraphVertex>> result = new LinkedHashMap<>();
        for (int currentHop = 0; currentHop < hopDepth && !frontier.isEmpty(); currentHop++) {
            GraphSearchCondition vertexCondition = GraphSearchCondition.builder()
                    .names(new ArrayList<>(frontier)).metadataFilter(filter).build();
            GraphSearchCondition edgeCondition = GraphSearchCondition.builder().metadataFilter(filter).build();
            List<Triple<GraphVertex, GraphEdge, GraphVertex>> matches = new ArrayList<>();
            matches.addAll(graphStore.searchEdges(GraphEdgeSearch.builder()
                    .source(vertexCondition).edge(edgeCondition).limit(maxResults).build()));
            matches.addAll(graphStore.searchEdges(GraphEdgeSearch.builder()
                    .target(vertexCondition).edge(edgeCondition).limit(maxResults).build()));
            matches.sort(java.util.Comparator
                    .comparingInt((Triple<GraphVertex, GraphEdge, GraphVertex> match) ->
                            edgeKeywordScore(question, match)).reversed()
                    .thenComparing(match -> StringUtils.defaultString(match.getMiddle().getId())));
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (Triple<GraphVertex, GraphEdge, GraphVertex> match : matches) {
                if (result.putIfAbsent(match.getMiddle().getId(), match) == null) {
                    List<Map<String, Object>> trace = new ArrayList<>(traversedEdges);
                    trace.add(edgeTrace(match, currentHop + 1, edgeKeywordScore(question, match)));
                    traversedEdges = List.copyOf(trace);
                }
                nextFrontier.add(match.getLeft().getName());
                nextFrontier.add(match.getRight().getName());
            }
            nextFrontier.removeAll(visited);
            visited.addAll(nextFrontier);
            frontier = nextFrontier;
        }
        return new ArrayList<>(result.values());
    }

    private int edgeKeywordScore(String question, Triple<GraphVertex, GraphEdge, GraphVertex> match) {
        String query = GraphEntityNameMatcher.normalize(question);
        String searchable = GraphEntityNameMatcher.normalize(
                StringUtils.defaultString(match.getLeft().getName())
                        + StringUtils.defaultString(match.getMiddle().getDescription())
                        + StringUtils.defaultString(match.getRight().getName()));
        if (query.length() < 2 || searchable.isEmpty()) return 0;
        Set<String> matched = new HashSet<>();
        for (int index = 0; index < query.length() - 1; index++) {
            String bigram = query.substring(index, index + 2);
            if (searchable.contains(bigram)) matched.add(bigram);
        }
        return matched.size();
    }

    private GraphQueryEntityParser.ParseResult extractEntities(String question) {
        String response = chatModel.chat(GraphExtractPrompt.buildQueryEntityPrompt(question));
        return GraphQueryEntityParser.parse(response);
    }

    public RefGraphDto getGraphRef() { return kbQaRecordRefGraphDto; }

    /** Clears graph provenance when no graph evidence survived final selection. */
    public void clearRetrievedReference() {
        kbQaRecordRefGraphDto.setVertices(Collections.emptyList());
        kbQaRecordRefGraphDto.setEdges(Collections.emptyList());
        kbQaRecordRefGraphDto.setEntitiesFromQuestion(Collections.emptyList());
    }

    /** Keeps graph details aligned with the graph evidence that entered the prompt. */
    public void retainRetrievedReference(Set<String> acceptedElementIds) {
        if (acceptedElementIds == null || acceptedElementIds.isEmpty()) {
            clearRetrievedReference();
            return;
        }
        List<GraphEdge> retainedEdges = kbQaRecordRefGraphDto.getEdges().stream()
                .filter(edge -> acceptedElementIds.contains(edge.getId()))
                .toList();
        Set<String> retainedVertexIds = new LinkedHashSet<>(acceptedElementIds);
        retainedEdges.forEach(edge -> {
            retainedVertexIds.add(edge.getStartId());
            retainedVertexIds.add(edge.getEndId());
        });
        List<GraphVertex> retainedVertices = kbQaRecordRefGraphDto.getVertices().stream()
                .filter(vertex -> retainedVertexIds.contains(vertex.getId()))
                .toList();
        Set<String> retainedNames = retainedVertices.stream()
                .map(GraphVertex::getName).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        kbQaRecordRefGraphDto.setEdges(retainedEdges);
        kbQaRecordRefGraphDto.setVertices(retainedVertices);
        kbQaRecordRefGraphDto.setEntitiesFromQuestion(
                kbQaRecordRefGraphDto.getEntitiesFromQuestion().stream()
                        .filter(retainedNames::contains).toList());
    }

    public Map<String, Object> getTrace() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("status", traceStatus);
        trace.put("hopDepth", hopDepth);
        trace.put("directMatchedEntities", directMatchedEntities);
        trace.put("llmExtractedEntities", llmExtractedEntities);
        trace.put("matchedAnchors", matchedAnchors);
        trace.put("traversedEdges", traversedEdges);
        trace.put("resolvedSourceSegmentUuids", resolvedSourceSegmentUuids);
        trace.put("sourceCandidateSegmentUuids", sourceCandidateSegmentUuids);
        trace.put("entityExtractionMode", entityExtractionMode);
        trace.put("entityExtractionFormat", entityExtractionFormat);
        trace.put("entityExtractionResponseNonBlank", entityExtractionResponseNonBlank);
        trace.put("entityExtractionParsedCount", entityExtractionParsedCount);
        trace.put("entityExtractionErrorType", entityExtractionErrorType);
        trace.put("sourceResolutionErrorType", sourceResolutionErrorType);
        return trace;
    }

    private void resetTrace() {
        clearRetrievedReference();
        directMatchedEntities = List.of();
        llmExtractedEntities = List.of();
        matchedAnchors = List.of();
        traversedEdges = List.of();
        resolvedSourceSegmentUuids = List.of();
        sourceCandidateSegmentUuids = List.of();
        traceStatus = "running";
        entityExtractionMode = "not_run";
        entityExtractionFormat = "not_run";
        entityExtractionResponseNonBlank = false;
        entityExtractionParsedCount = 0;
        entityExtractionErrorType = null;
        sourceResolutionErrorType = null;
    }

    /**
     * Relations and original chunks share one relevance score before Top-N.
     * Graph-only retrieval keeps at least topN-1 original chunks when available;
     * a precise, highly relevant relation can still occupy the remaining slot.
     */
    static List<Content> orderEvidence(String question, List<Content> sourceContents,
                                       List<Content> relationContents, int limit, int topN,
                                       int perDocumentLimit) {
        List<Content> all = new ArrayList<>(sourceContents.size() + relationContents.size());
        all.addAll(sourceContents);
        all.addAll(relationContents);
        all.sort(Comparator.comparingInt(
                (Content content) -> evidenceScore(question, content)).reversed());
        List<Content> diverse = selectWithDocumentDiversity(all, limit, perDocumentLimit);
        return preserveOriginalEvidence(diverse, Math.min(topN, limit),
                Math.min(Math.max(0, topN - 1), sourceContents.size()));
    }

    static List<Content> rankWithDocumentDiversity(String question, List<Content> contents,
                                                    int limit, int perDocumentLimit) {
        if (contents.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Content> ordered = new ArrayList<>(contents);
        ordered.sort(Comparator.comparingInt(
                (Content content) -> lexicalRelevance(question, content.textSegment().text()))
                .reversed());
        return selectWithDocumentDiversity(ordered, limit, perDocumentLimit);
    }

    private static List<Content> selectWithDocumentDiversity(List<Content> ordered,
                                                             int limit,
                                                             int perDocumentLimit) {
        List<Content> selected = new ArrayList<>();
        List<Content> deferred = new ArrayList<>();
        Map<String, Integer> documentCounts = new HashMap<>();
        int safePerDocumentLimit = Math.max(1, perDocumentLimit);
        for (Content content : ordered) {
            String documentId = documentId(content);
            if (StringUtils.isNotBlank(documentId)
                    && documentCounts.getOrDefault(documentId, 0) >= safePerDocumentLimit) {
                deferred.add(content);
                continue;
            }
            selected.add(content);
            if (StringUtils.isNotBlank(documentId)) {
                documentCounts.merge(documentId, 1, Integer::sum);
            }
            if (selected.size() >= limit) {
                return selected;
            }
        }
        for (Content content : deferred) {
            selected.add(content);
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected;
    }

    private static int lexicalRelevance(String question, String evidence) {
        String normalizedQuestion = GraphEntityNameMatcher.normalize(question);
        String normalizedEvidence = GraphEntityNameMatcher.normalize(evidence);
        if (normalizedQuestion.length() < 2 || normalizedEvidence.isEmpty()) {
            return 0;
        }
        Set<String> matched = new HashSet<>();
        for (int index = 0; index < normalizedQuestion.length() - 1; index++) {
            String bigram = normalizedQuestion.substring(index, index + 2);
            if (normalizedEvidence.contains(bigram)) {
                matched.add(bigram);
            }
        }
        return matched.size();
    }

    private static int evidenceScore(String question, Content content) {
        int score = lexicalRelevance(question, content.textSegment().text()) * 100;
        if (!isRelation(content)) {
            return score + 20;
        }
        int documentCount = sourceDocumentCount(content);
        return score - Math.min(200, Math.max(0, documentCount - 1) * 10);
    }

    private static List<Content> preserveOriginalEvidence(List<Content> ordered, int topN,
                                                          int minimumOriginals) {
        if (ordered.isEmpty() || topN <= 0 || minimumOriginals <= 0) {
            return ordered;
        }
        List<Content> promoted = new ArrayList<>(ordered.subList(0, Math.min(topN, ordered.size())));
        int originalCount = (int) promoted.stream().filter(content -> !isRelation(content)).count();
        if (originalCount >= minimumOriginals) {
            return ordered;
        }
        for (int index = topN; index < ordered.size() && originalCount < minimumOriginals; index++) {
            Content original = ordered.get(index);
            if (isRelation(original)) {
                continue;
            }
            for (int replace = promoted.size() - 1; replace >= 0; replace--) {
                if (isRelation(promoted.get(replace))) {
                    promoted.set(replace, original);
                    originalCount++;
                    break;
                }
            }
        }
        Set<Content> promotedIdentity = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        promotedIdentity.addAll(promoted);
        List<Content> result = new ArrayList<>(ordered.size());
        result.addAll(promoted);
        ordered.stream().filter(content -> !promotedIdentity.contains(content)).forEach(result::add);
        return result;
    }

    private static boolean isRelation(Content content) {
        return RetrievedCandidate.GRAPH_RELATION.equals(
                content.textSegment().metadata().getString(RetrievedCandidate.CONTENT_TYPE));
    }

    private static int sourceDocumentCount(Content content) {
        Metadata metadata = content.textSegment().metadata();
        Object explicitCount = metadata.toMap().get(GRAPH_SOURCE_DOCUMENT_COUNT);
        if (explicitCount instanceof Number number) {
            return number.intValue();
        }
        if (explicitCount != null) {
            try {
                return Integer.parseInt(String.valueOf(explicitCount));
            } catch (NumberFormatException ignored) {
                // Fall back to counting the source identifiers below.
            }
        }
        return Math.max(
                metadataValueCount(metadata.toMap().get(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID)),
                metadataValueCount(metadata.toMap().get(RetrievedCandidate.SOURCE_DOCUMENT_IDS)));
    }

    private static int metadataValueCount(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Collection<?> values) {
            return (int) values.stream().filter(java.util.Objects::nonNull).count();
        }
        return (int) java.util.Arrays.stream(String.valueOf(value).split(","))
                .filter(StringUtils::isNotBlank)
                .count();
    }

    private static String documentId(Content content) {
        Metadata metadata = content.textSegment().metadata();
        String documentId = metadata.getString(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID);
        if (StringUtils.isNotBlank(documentId)) {
            return documentId;
        }
        return metadata.getString(RetrievedCandidate.SOURCE_DOCUMENT_IDS);
    }

    static Set<String> findKbUuids(Collection<GraphVertex> vertices, Collection<GraphEdge> edges) {
        LinkedHashSet<String> kbUuids = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            addCsvValues(kbUuids, metadataValue(edge.getMetadata(), ZhiMeshConstant.MetadataKey.KB_UUID));
        }
        for (GraphVertex vertex : vertices) {
            addCsvValues(kbUuids, metadataValue(vertex.getMetadata(), ZhiMeshConstant.MetadataKey.KB_UUID));
        }
        return Set.copyOf(kbUuids);
    }

    private static void addCsvValues(Set<String> target, String value) {
        if (StringUtils.isBlank(value)) return;
        for (String item : value.split(",")) {
            if (StringUtils.isNotBlank(item)) target.add(item.trim());
        }
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return String.valueOf(metadata.get(key));
    }

    private Map<String, Object> anchorTrace(GraphVertex vertex, boolean directMatch, boolean llmMatch) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("id", vertex.getId());
        trace.put("name", vertex.getName());
        trace.put("directMatch", directMatch);
        trace.put("llmMatch", llmMatch);
        return trace;
    }

    private Map<String, Object> edgeTrace(Triple<GraphVertex, GraphEdge, GraphVertex> match,
                                          int hop, int keywordScore) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("edgeId", match.getMiddle().getId());
        trace.put("sourceId", match.getLeft().getId());
        trace.put("sourceName", match.getLeft().getName());
        trace.put("targetId", match.getRight().getId());
        trace.put("targetName", match.getRight().getName());
        trace.put("hop", hop);
        trace.put("keywordScore", keywordScore);
        return trace;
    }

    public static class GraphStoreContentRetrieverBuilder {
        public GraphStoreContentRetrieverBuilder maxResults(Integer maxResults) {
            if (maxResults != null) dynamicMaxResults = query -> ensureGreaterThanZero(maxResults, "maxResults");
            return this;
        }
        public GraphStoreContentRetrieverBuilder filter(Filter filter) {
            if (filter != null) dynamicFilter = query -> filter;
            return this;
        }
        public GraphStoreContentRetrieverBuilder breakIfSearchMissed(boolean breakFlag) {
            breakIfSearchMissed = breakFlag;
            return this;
        }
        public GraphStoreContentRetrieverBuilder hopDepth(Integer hopDepth) {
            this.hopDepth = hopDepth;
            return this;
        }
    }
}
