package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.KnowledgeBaseGraphSegment;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.QuotaHelper;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphSegmentService;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphElementSourceService;
import com.pppp.zhimesh.common.service.UserDayCostService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.vo.GraphIngestParam;
import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * 知识图谱RAG，基于图谱存储进行问答增强
 */
@Slf4j
public class GraphRag {

    /**
     * RAG名称，用于区分不同实例
     */
    @Getter
    private final String name;

    private final GraphStore graphStore;

    private KnowledgeBaseGraphSegmentService knowledgeBaseGraphSegmentService;
    private KnowledgeBaseGraphElementSourceService knowledgeBaseGraphElementSourceService;

    public GraphRag(String name, GraphStore kbGraphStore) {
        this.name = name;
        this.graphStore = kbGraphStore;
    }

    /** Test seam: injects collaborators so cleanup paths run without a Spring context. */
    GraphRag(String name, GraphStore kbGraphStore,
             KnowledgeBaseGraphElementSourceService elementSourceService,
             KnowledgeBaseGraphSegmentService segmentService) {
        this(name, kbGraphStore);
        this.knowledgeBaseGraphElementSourceService = elementSourceService;
        this.knowledgeBaseGraphSegmentService = segmentService;
    }

    public KnowledgeBaseGraphSegmentService getKnowledgeBaseGraphSegmentService() {
        if (null == knowledgeBaseGraphSegmentService) {
            knowledgeBaseGraphSegmentService = SpringUtil.getBean(KnowledgeBaseGraphSegmentService.class);
        }
        return knowledgeBaseGraphSegmentService;
    }

    KnowledgeBaseGraphElementSourceService getKnowledgeBaseGraphElementSourceService() {
        if (null == knowledgeBaseGraphElementSourceService) {
            knowledgeBaseGraphElementSourceService =
                    SpringUtil.getBean(KnowledgeBaseGraphElementSourceService.class);
        }
        return knowledgeBaseGraphElementSourceService;
    }

    /**
     * Removes the complete graph contribution of one knowledge base. Vertices carry
     * the KB uuid in their metadata, so one scoped DETACH DELETE removes every
     * vertex and edge; provenance rows and stored segments are dropped afterwards.
     */
    public void cleanupKnowledgeBase(String kbUuid) {
        synchronized (graphStore) {
            graphStore.deleteVertices(GraphSearchCondition.builder()
                    .metadataFilter(new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid))
                    .build(), true);
            getKnowledgeBaseGraphElementSourceService().removeKnowledgeBaseSources(kbUuid);
            getKnowledgeBaseGraphSegmentService().removeKnowledgeBaseSegments(kbUuid);
            log.info("Cleaned knowledge-base graph, kbUuid:{}", kbUuid);
        }
    }

    /**
     * Removes the previous graph contribution of one document before a retry.
     * Shared graph elements remain when another document still references them.
     * The same method is also used after a failed ingestion to remove partial data.
     */
    public void cleanupDocument(String kbUuid, String kbItemUuid) {
        KnowledgeBaseGraphElementSourceService sourceService =
                getKnowledgeBaseGraphElementSourceService();
        synchronized (graphStore) {
            List<String> edgeIds = sourceService.elementIds(kbItemUuid, KnowledgeBaseGraphElementSourceService.EDGE);
            List<String> vertexIds = sourceService.elementIds(kbItemUuid, KnowledgeBaseGraphElementSourceService.VERTEX);

            Set<String> sharedEdgeIds = sourceService.elementIdsReferencedByOtherDocuments(
                    kbUuid, kbItemUuid, KnowledgeBaseGraphElementSourceService.EDGE, edgeIds);
            Set<String> sharedVertexIds = sourceService.elementIdsReferencedByOtherDocuments(
                    kbUuid, kbItemUuid, KnowledgeBaseGraphElementSourceService.VERTEX, vertexIds);

            List<String> exclusiveEdgeIds = edgeIds.stream().filter(id -> !sharedEdgeIds.contains(id)).toList();
            List<String> exclusiveVertexIds = vertexIds.stream().filter(id -> !sharedVertexIds.contains(id)).toList();

            // Edges must be removed before vertices. Provenance is retained until
            // the physical graph operations succeed, so a failed cleanup is retryable.
            graphStore.deleteEdgesByIds(exclusiveEdgeIds);
            graphStore.deleteVerticesByIds(exclusiveVertexIds);

            // Shared elements cannot be deleted, but the contribution of the
            // document being rebuilt must still be removed from their materialized
            // graph properties. Compute the replacement while provenance is
            // retained so a failed graph update remains retryable.
            for (String edgeId : sharedEdgeIds) {
                KnowledgeBaseGraphElementSourceService.ContributionAggregate aggregate =
                        sourceService.aggregateExcludingDocument(
                                KnowledgeBaseGraphElementSourceService.EDGE, edgeId, kbItemUuid);
                if (!aggregate.isEmpty()) {
                    com.pppp.zhimesh.common.vo.GraphEdge existing = graphStore.getEdges(List.of(edgeId))
                            .stream().findFirst().map(Triple::getMiddle).orElse(null);
                    graphStore.updateEdgeById(edgeId, com.pppp.zhimesh.common.vo.GraphEdge.builder()
                            .textSegmentId(aggregate.textSegmentId())
                            .description(aggregate.description())
                            .weight(aggregate.weight())
                            .relationType(StringUtils.defaultIfBlank(aggregate.relationType(),
                                    existing == null ? "" : existing.getRelationType()))
                            .polarity(aggregate.polarity() == null && existing != null
                                    ? existing.getPolarity() : aggregate.polarity())
                            .status(StringUtils.defaultIfBlank(aggregate.status(),
                                    existing == null ? "" : existing.getStatus()))
                            .properties(aggregate.properties().isEmpty() && existing != null
                                    ? existing.getProperties() : aggregate.properties())
                            .evidenceCount(aggregate.sourceCount())
                            .metadata(new HashMap<>(aggregate.metadata()))
                            .build());
                }
            }
            for (String vertexId : sharedVertexIds) {
                KnowledgeBaseGraphElementSourceService.ContributionAggregate aggregate =
                        sourceService.aggregateExcludingDocument(
                                KnowledgeBaseGraphElementSourceService.VERTEX, vertexId, kbItemUuid);
                if (!aggregate.isEmpty()) {
                    com.pppp.zhimesh.common.vo.GraphVertex existing = graphStore.getVertexById(vertexId);
                    String canonicalName = StringUtils.defaultIfBlank(aggregate.canonicalName(),
                            existing == null ? "" : existing.getName());
                    graphStore.updateVertexById(vertexId,
                            com.pppp.zhimesh.common.vo.GraphVertex.builder()
                                    .name(canonicalName)
                                    .canonicalName(canonicalName)
                                    .aliases(aggregate.aliases().isEmpty() && existing != null
                                            ? existing.getAliases() : aggregate.aliases())
                                    .properties(aggregate.properties().isEmpty() && existing != null
                                            ? existing.getProperties() : aggregate.properties())
                                    .salience(aggregate.salience() == null && existing != null
                                            ? existing.getSalience() : aggregate.salience())
                                    .textSegmentId(aggregate.textSegmentId())
                                    .description(aggregate.description())
                                    .metadata(new HashMap<>(aggregate.metadata()))
                                    .build());
                }
            }
            sourceService.replaceDocumentSources(kbItemUuid);
            getKnowledgeBaseGraphSegmentService().removeDocumentSegments(kbUuid, kbItemUuid);
            log.info("Cleaned graph contribution, kbUuid:{}, kbItemUuid:{}, deletedEdges:{}, deletedVertices:{}, sharedEdges:{}, sharedVertices:{}",
                    kbUuid, kbItemUuid, exclusiveEdgeIds.size(), exclusiveVertexIds.size(),
                    sharedEdgeIds.size(), sharedVertexIds.size());
        }
    }

    public void ingest(GraphIngestParam graphIngestParam) {
        log.info("GraphRag ingest");
        buildIngestor(graphIngestParam).ingest(graphIngestParam.getDocument());
    }

    /**
     * Ingests the canonical chunks shared with the vector and BM25 branches so
     * graph segment text and chunk provenance stay identical to those routes.
     * A null or empty segment list falls back to the legacy re-splitting path.
     */
    public void ingest(GraphIngestParam graphIngestParam, List<TextSegment> canonicalSegments) {
        if (canonicalSegments == null || canonicalSegments.isEmpty()) {
            ingest(graphIngestParam);
            return;
        }
        log.info("GraphRag ingest with {} canonical segments", canonicalSegments.size());
        buildIngestor(graphIngestParam).ingestSegments(canonicalSegments);
    }

    private GraphStoreIngestor buildIngestor(GraphIngestParam graphIngestParam) {
        User user = graphIngestParam.getUser();
        DocumentSplitter documentSplitter = DocumentSplitterFactory.create(
                graphIngestParam.getStrategy(),
                graphIngestParam.getMaxSegmentSize(),
                graphIngestParam.getOverlap(),
                graphIngestParam.getCustomSeparator(),
                TokenEstimatorFactory.create(graphIngestParam.getTokenEstimator()));
        GraphStoreIngestor ingestor = GraphStoreIngestor.builder()
                .documentSplitter(documentSplitter)
                .segmentsFunction(segments -> {
                    List<SegmentExtractionTask> extractionTasks = new ArrayList<>(segments.size());
                    for (TextSegment segment : segments) {
                        String segmentId = UuidUtil.createShort();
                        log.info("Save segment to graph_segment,segmentId:{}", segmentId);
                        KnowledgeBaseGraphSegment graphSegment = new KnowledgeBaseGraphSegment();
                        graphSegment.setUuid(segmentId);
                        graphSegment.setRemark(segment.text());
                        graphSegment.setKbUuid(segment.metadata().getString(ZhiMeshConstant.MetadataKey.KB_UUID));
                        graphSegment.setKbItemUuid(segment.metadata().getString(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID));
                        graphSegment.setChunkSetUuid(StringUtils.defaultString(graphIngestParam.getChunkSetUuid()));
                        Object segmentChunkUuid = segment.metadata().toMap().get(
                                ZhiMeshConstant.MetadataKey.CHUNK_UUID);
                        graphSegment.setChunkUuid(segmentChunkUuid == null
                                ? "" : String.valueOf(segmentChunkUuid));
                        graphSegment.setGraphModelId(graphIngestParam.getGraphModelId() == null
                                ? 0L : graphIngestParam.getGraphModelId());
                        graphSegment.setGraphIndexVersionUuid(StringUtils.defaultString(
                                graphIngestParam.getGraphIndexVersionUuid()));
                        graphSegment.setUserId(user.getId());
                        getKnowledgeBaseGraphSegmentService().save(graphSegment);
                        extractionTasks.add(new SegmentExtractionTask(segment, segmentId));
                    }
                    int segmentConcurrency = Math.max(1, SpringUtil.getBean(ZhiMeshProperties.class)
                            .getIndexing().getGraphSegmentConcurrency());
                    log.info("Starting bounded graph extraction, segments:{}, concurrency:{}",
                            extractionTasks.size(), segmentConcurrency);
                    return GraphExtractionTaskRunner.mapOrdered(extractionTasks,
                            segmentConcurrency,
                            task -> extractSegment(graphIngestParam, user, task));
                })
                .identifyColumns(graphIngestParam.getIdentifyColumns())
                .appendColumns(graphIngestParam.getAppendColumns())
                .chunkSetUuid(graphIngestParam.getChunkSetUuid())
                .graphModelId(graphIngestParam.getGraphModelId())
                .graphIndexVersionUuid(graphIngestParam.getGraphIndexVersionUuid())
                .graphStore(graphStore)
                .build();
        return ingestor;
    }

    private Triple<TextSegment, String, String> extractSegment(
            GraphIngestParam graphIngestParam, User user, SegmentExtractionTask task) {
        TextSegment segment = task.segment();
        String segmentId = task.segmentId();
        String response = "";
        if (StringUtils.isBlank(segment.text())) {
            return Triple.of(segment, segmentId, response);
        }

        if (!graphIngestParam.isFreeToken()) {
            ErrorEnum errorMsg = SpringUtil.getBean(QuotaHelper.class).checkTextQuota(user);
            if (null != errorMsg) {
                log.warn("Quota exceeded during knowledge graph extraction, user:{}, errorInfo:{}",
                        user.getName(), SpringUtil.getMessage(errorMsg.getInfo()));
                // A skipped segment must fail the document. Treating it as
                // success produces a misleading "graphed" status with no
                // nodes or edges.
                throw new BaseException(errorMsg);
            }
        }

        log.info("Requesting LLM to extract entities and relations from text, segmentId:{}",
                segmentId);
        GraphExtractionRequestExecutor requestExecutor =
                SpringUtil.getBean(GraphExtractionRequestExecutor.class);
        ChatResponse aiMessageResponse = requestExecutor.execute(segmentId, "extract", () ->
                graphIngestParam.getChatModel().chat(
                        UserMessage.from(GraphExtractPrompt.buildJsonExtractionPrompt(segment.text()))));
        String rawResponse = aiMessageResponse.aiMessage().text();
        SpringUtil.getBean(UserDayCostService.class).appendCostToUser(user,
                aiMessageResponse.tokenUsage().totalTokenCount(), graphIngestParam.isFreeToken());

        List<String> qualityIssues = GraphExtractionResponse.qualityIssues(
                rawResponse, segment.text());
        if (!qualityIssues.isEmpty()) {
            log.warn("Graph extraction requires controlled repair, segmentId:{}, issues:{}",
                    segmentId, qualityIssues);
            String responseToRepair = rawResponse;
            ChatResponse repairResponse = requestExecutor.execute(segmentId, "repair", () ->
                    graphIngestParam.getChatModel().chat(UserMessage.from(
                            GraphExtractPrompt.buildJsonRepairPrompt(segment.text(), responseToRepair,
                                    String.join("; ", qualityIssues)))));
            rawResponse = repairResponse.aiMessage().text();
            SpringUtil.getBean(UserDayCostService.class).appendCostToUser(user,
                    repairResponse.tokenUsage().totalTokenCount(), graphIngestParam.isFreeToken());
        }
        // A repair remains inside the same task, so it never creates a third
        // concurrent request when the per-document limit is two.
        GraphExtractionResponse.assertQuality(rawResponse, segment.text());
        response = GraphExtractionResponse.parseToLegacyFormat(rawResponse);
        return Triple.of(segment, segmentId, response);
    }

    private record SegmentExtractionTask(TextSegment segment, String segmentId) {
    }

    public GraphStoreContentRetriever createRetriever(RetrieverCreateParam param) {
        return GraphStoreContentRetriever.builder()
                .graphStore(graphStore)
                .chatModel(param.getChatModel())
                .maxResults(param.getMaxResults())
                .filter(param.getFilter())
                .breakIfSearchMissed(param.isBreakIfSearchMissed())
                .hopDepth(param.getGraphHopDepth())
                .build();
    }
}
