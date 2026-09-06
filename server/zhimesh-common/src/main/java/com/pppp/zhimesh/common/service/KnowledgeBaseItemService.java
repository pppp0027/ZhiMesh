package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.KbItemDto;
import com.pppp.zhimesh.common.dto.KbItemEditReq;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.EmbeddingStatusEnum;
import com.pppp.zhimesh.common.enums.FulltextStatusEnum;
import com.pppp.zhimesh.common.enums.GraphicalStatusEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.rag.EmbeddingRagContext;
import com.pppp.zhimesh.common.rag.GraphRag;
import com.pppp.zhimesh.common.rag.GraphRagContext;
import com.pppp.zhimesh.common.rag.bm25.Bm25IndexService;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCoordinator;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.EmbeddingIngestParam;
import com.pppp.zhimesh.common.vo.GraphIngestParam;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.DOC_INDEX_TYPE_EMBEDDING;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.DOC_INDEX_TYPE_FULLTEXT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.DOC_INDEX_TYPE_GRAPHICAL;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE;
import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_STATISTIC_RECALCULATE_SIGNAL;
import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.USER_INDEXING;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;

@Slf4j
@Service
public class KnowledgeBaseItemService extends ServiceImpl<KnowledgeBaseItemMapper, KnowledgeBaseItem> {

    /**
     * Apache AGE graph writes perform read-create-update sequences. Serializing
     * them prevents concurrent document ingestion from racing on shared nodes
     * and edges in the same knowledge-base graph.
     */
    private Semaphore graphIngestSemaphore;
    private Semaphore embeddingIngestSemaphore;
    private int graphIngestPermits;

    @Resource
    private ZhiMeshProperties adiProperties;

    @PostConstruct
    void initializeIndexingConcurrency() {
        int graphConcurrency = Math.max(1, adiProperties.getIndexing().getGraphConcurrency());
        int embeddingConcurrency = Math.max(1, adiProperties.getIndexing().getEmbeddingConcurrency());
        graphIngestSemaphore = new Semaphore(graphConcurrency, true);
        embeddingIngestSemaphore = new Semaphore(embeddingConcurrency, true);
        graphIngestPermits = graphConcurrency;
        log.info("Knowledge-base indexing concurrency initialized, graph:{}, embedding:{}", graphConcurrency, embeddingConcurrency);
    }

    @Resource
    @Lazy
    private KnowledgeBaseItemService self;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IKnowledgeEmbeddingService iKnowledgeEmbeddingService;

    @Resource
    private FileService fileService;

    @Resource
    private CanonicalChunkIndexService canonicalChunkIndexService;

    @Resource
    private Bm25IndexService bm25IndexService;

    @Resource
    private KnowledgeRouteProfileCoordinator routeProfileCoordinator;

    @Transactional
    public KnowledgeBaseItem saveOrUpdate(KbItemEditReq itemEditReq) {
        KnowledgeBaseItem previous = null;
        // Authorize before mutating: by knowledge-base uuid when creating (no item
        // uuid exists yet), by item id when updating (the client-controlled uuid
        // cannot be trusted; the real target is resolved by id).
        if (null == itemEditReq.getId() || itemEditReq.getId() < 1) {
            checkWritePrivilegeByKb(itemEditReq.getKbUuid());
        } else {
            checkWritePrivilegeById(itemEditReq.getId());
            previous = baseMapper.selectById(itemEditReq.getId());
            if (previous == null) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
        }
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setTitle(itemEditReq.getTitle());
        if (StringUtils.isNotBlank(itemEditReq.getBrief())) {
            item.setBrief(itemEditReq.getBrief());
        } else {
            item.setBrief(StringUtils.substring(itemEditReq.getRemark(), 0, 200));
        }
        item.setRemark(itemEditReq.getRemark());
        if (null == itemEditReq.getId() || itemEditReq.getId() < 1) {
            item.setUuid(UuidUtil.createShort());
            item.setKbId(itemEditReq.getKbId());
            item.setKbUuid(itemEditReq.getKbUuid());
            baseMapper.insert(item);
        } else {
            item.setId(itemEditReq.getId());
            boolean contentChanged = !Objects.equals(previous.getRemark(), item.getRemark());
            if (contentChanged) {
                // Fence every in-flight derived-index build in the same row update.
                // A worker holding an older snapshot can no longer publish it as current.
                item.setActiveChunkSetUuid("");
                item.setEmbeddingChunkSetUuid("");
                item.setGraphicalChunkSetUuid("");
                item.setFulltextChunkSetUuid("");
                item.setFulltextStatus(FulltextStatusEnum.NONE);
                item.setFulltextStatusChangeTime(LocalDateTime.now());
                item.setFulltextStartedAt(null);
                item.setFulltextCompletedAt(null);
            }
            baseMapper.updateById(item);
            if (contentChanged) {
                // The old lexical index must stop serving immediately after source content changes.
                // The next indexing job will publish a new canonical snapshot and FULLTEXT build.
                bm25IndexService.deleteByItemUuid(previous.getUuid());
            }
        }

        String changedKbUuid = previous == null ? item.getKbUuid() : previous.getKbUuid();
        stringRedisTemplate.opsForSet().add(KB_STATISTIC_RECALCULATE_SIGNAL, changedKbUuid);
        return baseMapper.selectById(item.getId());
    }

    public KnowledgeBaseItem getEnable(String uuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBaseItem::getUuid, uuid)
                
                .one();
    }

    /**
     * Search items in a knowledge base by keyword.
     */
    public Page<KbItemDto> search(String kbUuid, String keyword, Integer currentPage, Integer pageSize) {
        Page<KbItemDto> page = baseMapper.searchByKb(new Page<>(currentPage, pageSize), kbUuid, keyword);
        page.getRecords().forEach(item -> item.setSourceFileUrl(fileService.getUrl(item.getSourceFileUuid())));
        return page;
    }

    /**
     * 批量索引知识点
     *
     * @param knowledgeBase 知识库
     * @param kbItemUuids   知识点uuid列表
     * @param indexTypes    索引类型，如embedding,graphical,fulltext
     * @return 成功或失败
     */
    public boolean checkAndIndexing(KnowledgeBase knowledgeBase, List<String> kbItemUuids, List<String> indexTypes) {
        List<KnowledgeBaseItem> items = new ArrayList<>();
        for (String kbItemUuid : kbItemUuids) {
            if (hasWritePrivilege(kbItemUuid)) {
                KnowledgeBaseItem item = getEnable(kbItemUuid);
                if (item != null) items.add(item);
            }
        }
        if (items.isEmpty()) return true;
        IndexBatchCompletion batch = beginIndexBatch(knowledgeBase, indexTypes);
        try {
            for (KnowledgeBaseItem item : items) {
                submitIndexTask(ThreadContext.getCurrentUser(), knowledgeBase, item, indexTypes, batch);
            }
        } finally {
            batch.submissionFinished();
        }
        return true;
    }

    IndexBatchCompletion beginIndexBatch(KnowledgeBase knowledgeBase, List<String> indexTypes) {
        long generation = requestsRouteProfileRebuild(indexTypes)
                ? routeProfileCoordinator.indexingRequested(knowledgeBase.getUuid()) : -1L;
        Runnable completion = generation > 0
                ? () -> routeProfileCoordinator.indexingCompleted(knowledgeBase.getUuid(), generation)
                : () -> { };
        return new IndexBatchCompletion(completion);
    }

    /**
     * Wipes the whole knowledge-base graph so a full rebuild starts clean. Every
     * graph-ingest permit is drained first, letting in-flight document builds
     * finish before their elements are deleted; builds queued behind the drain
     * then reingest on the emptied graph. Legacy elements that lost their
     * provenance rows are removed too, because the wipe filters by kb_uuid
     * metadata instead of provenance ids.
     */
    void cleanupKnowledgeBaseGraph(String kbUuid) {
        graphIngestSemaphore.acquireUninterruptibly(graphIngestPermits);
        try {
            knowledgeBaseGraphRag().cleanupKnowledgeBase(kbUuid);
        } finally {
            graphIngestSemaphore.release(graphIngestPermits);
        }
    }

    /** Test seam: resolves the knowledge-base GraphRag without a static context. */
    GraphRag knowledgeBaseGraphRag() {
        return GraphRagContext.get(KNOWLEDGE_BASE);
    }

    void submitIndexTask(User user, KnowledgeBase knowledgeBase, KnowledgeBaseItem item,
                         List<String> indexTypes, IndexBatchCompletion batch) {
        String userIndexKey = MessageFormat.format(USER_INDEXING, knowledgeBase.getOwnerId());
        batch.taskScheduled();
        try {
            stringRedisTemplate.opsForValue().increment(userIndexKey);
            stringRedisTemplate.expire(userIndexKey, 10, TimeUnit.MINUTES);
            self.asyncIndex(user, knowledgeBase, item, indexTypes, batch);
        } catch (RuntimeException exception) {
            decrementUserIndexing(userIndexKey);
            batch.taskCompleted();
            throw exception;
        }
    }

    /**
     * 对文档进行索引（向量、图谱、BM25 全文索引）
     *
     * @param user          用户
     * @param knowledgeBase 知识库
     * @param kbItem        知识点
     * @param indexTypes    索引类型，如embedding,graphical,fulltext
     */
    @Async("indexingExecutor")
    public void asyncIndex(User user, KnowledgeBase knowledgeBase, KnowledgeBaseItem kbItem,
                           List<String> indexTypes, IndexBatchCompletion batch) {
        String userIndexKey = MessageFormat.format(USER_INDEXING, knowledgeBase.getOwnerId());
        List<String> requestedIndexTypes = indexTypes == null ? List.of() : List.copyOf(indexTypes);
        try {
            // Async jobs receive a queued snapshot. Always reload before deriving any
            // index so an edit made while the job was waiting cannot be re-published.
            KnowledgeBaseItem currentItem = getEnable(kbItem.getUuid());
            if (currentItem == null) {
                log.info("Knowledge-base item disappeared before indexing, kbItemUuid:{}", kbItem.getUuid());
                return;
            }
            kbItem = currentItem;
            ZhiMeshProperties.Retrieval.Bm25 bm25Config = adiProperties.getRetrieval().getBm25();
            IndexRequestPlan plan = planIndexRequests(requestedIndexTypes,
                    EmbeddingStatusEnum.DOING == kbItem.getEmbeddingStatus(),
                    GraphicalStatusEnum.DOING == kbItem.getGraphicalStatus(),
                    FulltextStatusEnum.DOING == kbItem.getFulltextStatus(),
                    bm25Config.isEnabled(), bm25Config.isAutoIndex(),
                    adiProperties.getIndexing().isCanonicalChunkEnabled());
            boolean embeddingRequested = plan.embeddingRequested();
            boolean bm25Requested = plan.bm25Requested();
            boolean graphRequested = plan.graphRequested();

            Metadata metadata = new Metadata();
            metadata.put(ZhiMeshConstant.MetadataKey.KB_UUID, kbItem.getKbUuid());
            metadata.put(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, kbItem.getUuid());
            Document document = new DefaultDocument(kbItem.getRemark(), metadata);

            CanonicalChunkSnapshot snapshot = null;
            if (plan.canonicalChunksRequired()) {
                try {
                    snapshot = canonicalChunkIndexService.index(knowledgeBase, kbItem);
                } catch (Exception exception) {
                    // Vector and graph retain their legacy split path so a canonical-index
                    // problem does not take both established retrieval branches offline.
                    log.error("Canonical chunk indexing failed, kbUuid:{}, kbItemUuid:{}",
                            kbItem.getKbUuid(), kbItem.getUuid(), exception);
                }
            }

            // Canonical indexing locks and reloads the source. Reload once more so
            // legacy graph/document ingestion consumes the same source generation.
            KnowledgeBaseItem indexedItem = getEnable(kbItem.getUuid());
            if (indexedItem == null) {
                return;
            }
            kbItem = indexedItem;
            document = new DefaultDocument(kbItem.getRemark(), metadata);

            if (embeddingRequested) {
                indexingEmbedding(knowledgeBase, kbItem, document, snapshot);
            }
            if (bm25Requested) {
                if (snapshot == null) {
                    log.warn("BM25 indexing skipped because canonical chunks are unavailable, kbUuid:{}, kbItemUuid:{}",
                            kbItem.getKbUuid(), kbItem.getUuid());
                    markBm25Failed(kbItem, null, "Canonical chunks are unavailable");
                } else {
                    indexingBm25(kbItem, snapshot);
                }
            }
            if (graphRequested) {
                indexingGraph(user, knowledgeBase, kbItem, document, snapshot);
            }
        } finally {
            try {
                stringRedisTemplate.opsForSet().add(KB_STATISTIC_RECALCULATE_SIGNAL, kbItem.getKbUuid());
            } catch (RuntimeException exception) {
                log.warn("Unable to queue knowledge-base statistics refresh, kbUuid:{}",
                        kbItem.getKbUuid(), exception);
            } finally {
                decrementUserIndexing(userIndexKey);
                batch.taskCompleted();
            }
        }

    }

    private void decrementUserIndexing(String userIndexKey) {
        try {
            Long remaining = stringRedisTemplate.opsForValue().decrement(userIndexKey);
            if (remaining != null && remaining <= 0) stringRedisTemplate.delete(userIndexKey);
        } catch (RuntimeException exception) {
            log.warn("Unable to update user indexing progress, key:{}", userIndexKey, exception);
        }
    }

    static final class IndexBatchCompletion {
        private final AtomicLong remaining = new AtomicLong(1L);
        private final AtomicBoolean fired = new AtomicBoolean();
        private final Runnable completion;

        IndexBatchCompletion(Runnable completion) {
            this.completion = completion;
        }

        void taskScheduled() {
            remaining.incrementAndGet();
        }

        void taskCompleted() {
            completeOne();
        }

        void submissionFinished() {
            completeOne();
        }

        private void completeOne() {
            long pending = remaining.decrementAndGet();
            if (pending < 0) throw new IllegalStateException("Index batch completed more than once");
            if (pending == 0 && fired.compareAndSet(false, true)) completion.run();
        }
    }

    boolean requestsRouteProfileRebuild(List<String> indexTypes) {
        ZhiMeshProperties.Retrieval.Bm25 bm25 = adiProperties.getRetrieval().getBm25();
        return isRouteProfileIndexOperation(indexTypes, bm25.isEnabled(), bm25.isAutoIndex());
    }

    static boolean isRouteProfileIndexOperation(List<String> indexTypes,
                                                boolean bm25Enabled, boolean bm25AutoIndex) {
        List<String> requested = indexTypes == null ? List.of() : indexTypes;
        return requested.contains(DOC_INDEX_TYPE_EMBEDDING)
                || requested.contains(DOC_INDEX_TYPE_GRAPHICAL)
                || requested.contains(DOC_INDEX_TYPE_FULLTEXT)
                || bm25Enabled && bm25AutoIndex;
    }

    /** Per-branch execution plan resolved from the requested index types and current statuses. */
    record IndexRequestPlan(boolean embeddingRequested, boolean bm25Requested,
                            boolean graphRequested, boolean canonicalChunksRequired) {
    }

    /**
     * Resolves which index branches one indexing job must run. Every combination of
     * embedding, graphical and fulltext is valid: branches already marked DOING are
     * skipped, fulltext stays gated by the BM25 toggle, and canonical chunks are
     * required whenever the canonical toggle is on or BM25 needs them — so all
     * requested branches share one aligned chunk snapshot per job.
     */
    static IndexRequestPlan planIndexRequests(List<String> requestedIndexTypes,
                                              boolean embeddingInProgress,
                                              boolean graphInProgress,
                                              boolean fulltextInProgress,
                                              boolean bm25Enabled,
                                              boolean bm25AutoIndex,
                                              boolean canonicalChunkEnabled) {
        List<String> requested = requestedIndexTypes == null ? List.of() : requestedIndexTypes;
        boolean embeddingRequested = requested.contains(DOC_INDEX_TYPE_EMBEDDING) && !embeddingInProgress;
        boolean graphRequested = requested.contains(DOC_INDEX_TYPE_GRAPHICAL) && !graphInProgress;
        boolean bm25Requested = bm25Enabled
                && (bm25AutoIndex || requested.contains(DOC_INDEX_TYPE_FULLTEXT))
                && !fulltextInProgress;
        boolean canonicalChunksRequired = (canonicalChunkEnabled || bm25Requested)
                && (embeddingRequested || graphRequested || bm25Requested);
        return new IndexRequestPlan(embeddingRequested, bm25Requested, graphRequested, canonicalChunksRequired);
    }

    private void indexingEmbedding(KnowledgeBase knowledgeBase, KnowledgeBaseItem kbItem,
                                   Document document, CanonicalChunkSnapshot snapshot) {
        boolean embeddingLockAcquired = false;
        try {
            embeddingIngestSemaphore.acquire();
            embeddingLockAcquired = true;
            boolean started = ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .eq(snapshot != null, KnowledgeBaseItem::getActiveChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getEmbeddingStatusChangeTime, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getEmbeddingStartedAt, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getEmbeddingCompletedAt, null)
                    .set(snapshot != null, KnowledgeBaseItem::getEmbeddingChunkSetUuid, "")
                    .set(KnowledgeBaseItem::getEmbeddingStatus, EmbeddingStatusEnum.DOING)
                    .update();
            if (!started && snapshot != null) {
                log.warn("Skipped stale embedding build, kbItemUuid:{}, chunkSetUuid:{}",
                        kbItem.getUuid(), snapshot.chunkSet().getUuid());
                return;
            }
            iKnowledgeEmbeddingService.deleteByItemUuid(kbItem.getUuid());
            if (snapshot != null) {
                EmbeddingRagContext.get(KNOWLEDGE_BASE).ingestSegments(snapshot.segments());
            } else {
                EmbeddingRagContext.get(KNOWLEDGE_BASE).ingest(document,
                        EmbeddingIngestParam.builder()
                                .overlap(knowledgeBase.getIngestMaxOverlap())
                                .strategy(knowledgeBase.getIngestSplitStrategy())
                                .maxSegmentSize(knowledgeBase.getIngestMaxSegmentSize())
                                .customSeparator(knowledgeBase.getIngestCustomSeparator())
                                .tokenEstimator(knowledgeBase.getIngestTokenEstimator())
                                .build());
            }
            boolean published = ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .eq(snapshot != null, KnowledgeBaseItem::getActiveChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getEmbeddingStatusChangeTime, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getEmbeddingCompletedAt, LocalDateTime.now())
                    .set(snapshot != null, KnowledgeBaseItem::getEmbeddingChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getEmbeddingStatus, EmbeddingStatusEnum.DONE)
                    .update();
            if (!published && snapshot != null) {
                iKnowledgeEmbeddingService.deleteByItemUuid(kbItem.getUuid());
                log.warn("Discarded stale embedding completion, kbItemUuid:{}, chunkSetUuid:{}",
                        kbItem.getUuid(), snapshot.chunkSet().getUuid());
            }
        } catch (Exception e) {
            log.error("ingestForEmbedding error", e);
            ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .eq(snapshot != null, KnowledgeBaseItem::getActiveChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getEmbeddingStatusChangeTime, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getEmbeddingStatus, EmbeddingStatusEnum.FAIL)
                    .update();
        } finally {
            if (embeddingLockAcquired) {
                embeddingIngestSemaphore.release();
            }
        }
    }

    private void indexingBm25(KnowledgeBaseItem kbItem, CanonicalChunkSnapshot snapshot) {
        LocalDateTime startedAt = LocalDateTime.now();
        boolean started = ChainWrappers.lambdaUpdateChain(baseMapper)
                .eq(KnowledgeBaseItem::getId, kbItem.getId())
                .eq(KnowledgeBaseItem::getActiveChunkSetUuid, snapshot.chunkSet().getUuid())
                .set(KnowledgeBaseItem::getFulltextStatus, FulltextStatusEnum.DOING)
                .set(KnowledgeBaseItem::getFulltextStatusChangeTime, startedAt)
                .set(KnowledgeBaseItem::getFulltextStartedAt, startedAt)
                .set(KnowledgeBaseItem::getFulltextCompletedAt, null)
                .set(KnowledgeBaseItem::getFulltextChunkSetUuid, "")
                .update();
        if (!started) {
            log.warn("Skipped stale BM25 build, kbItemUuid:{}, chunkSetUuid:{}",
                    kbItem.getUuid(), snapshot.chunkSet().getUuid());
            return;
        }
        try {
            var result = bm25IndexService.rebuild(kbItem, snapshot);
            LocalDateTime completedAt = LocalDateTime.now();
            boolean published = ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .eq(KnowledgeBaseItem::getActiveChunkSetUuid, snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getFulltextStatus, FulltextStatusEnum.DONE)
                    .set(KnowledgeBaseItem::getFulltextStatusChangeTime, completedAt)
                    .set(KnowledgeBaseItem::getFulltextCompletedAt, completedAt)
                    .set(KnowledgeBaseItem::getFulltextChunkSetUuid, snapshot.chunkSet().getUuid())
                    .update();
            if (!published) {
                log.warn("Discarded stale BM25 completion, kbItemUuid:{}, chunkSetUuid:{}",
                        kbItem.getUuid(), snapshot.chunkSet().getUuid());
                return;
            }
            log.info("BM25 index activated, kbUuid:{}, kbItemUuid:{}, buildUuid:{}, chunks:{}, postings:{}, analyzer:{}",
                    kbItem.getKbUuid(), kbItem.getUuid(), result.indexBuildUuid(), result.documentCount(),
                    result.postingCount(), result.analyzerVersion());
        } catch (Exception exception) {
            markBm25Failed(kbItem, snapshot, exception.getMessage());
            log.error("BM25 indexing failed, kbUuid:{}, kbItemUuid:{}",
                    kbItem.getKbUuid(), kbItem.getUuid(), exception);
        }
    }

    private void markBm25Failed(KnowledgeBaseItem kbItem, CanonicalChunkSnapshot snapshot, String message) {
        var update = ChainWrappers.lambdaUpdateChain(baseMapper)
                .eq(KnowledgeBaseItem::getId, kbItem.getId());
        if (snapshot != null) {
            update.eq(KnowledgeBaseItem::getActiveChunkSetUuid, snapshot.chunkSet().getUuid());
        }
        LocalDateTime now = LocalDateTime.now();
        update.set(KnowledgeBaseItem::getFulltextStatus, FulltextStatusEnum.FAIL)
                .set(KnowledgeBaseItem::getFulltextStatusChangeTime, now)
                .set(KnowledgeBaseItem::getFulltextCompletedAt, now)
                .set(KnowledgeBaseItem::getFulltextChunkSetUuid, "")
                .update();
        log.warn("BM25 index marked failed, kbItemUuid:{}, reason:{}", kbItem.getUuid(), message);
    }

    private void indexingGraph(User user, KnowledgeBase knowledgeBase, KnowledgeBaseItem kbItem,
                               Document document, CanonicalChunkSnapshot snapshot) {
        boolean graphIngestLockAcquired = false;
        GraphRag graphRag = null;
        String graphIndexVersionUuid = UuidUtil.createShort();
        try {
            graphIngestSemaphore.acquire();
            graphIngestLockAcquired = true;
            graphRag = GraphRagContext.get(KNOWLEDGE_BASE);
            boolean started = ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .eq(snapshot != null, KnowledgeBaseItem::getActiveChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getGraphicalStatusChangeTime, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getGraphicalModelId, knowledgeBase.getIngestModelId())
                    .set(KnowledgeBaseItem::getGraphicalStartedAt, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getGraphicalCompletedAt, null)
                    .set(snapshot != null, KnowledgeBaseItem::getGraphicalChunkSetUuid, "")
                    .set(KnowledgeBaseItem::getGraphicalStatus, GraphicalStatusEnum.DOING)
                    .update();
            if (!started && snapshot != null) {
                log.warn("Skipped stale graph build, kbItemUuid:{}, chunkSetUuid:{}",
                        kbItem.getUuid(), snapshot.chunkSet().getUuid());
                return;
            }
            // A retry starts from a clean document contribution. Elements shared
            // with other documents are preserved by cleanupDocument().
            graphRag.cleanupDocument(kbItem.getKbUuid(), kbItem.getUuid());
            AbstractLLMService llmService = LLMContext.getServiceById(knowledgeBase.getIngestModelId(), true);
            ChatModel ChatModel = llmService.buildChatLLM(
                    ChatModelBuilderProperties.builder()
                            .temperature(knowledgeBase.getQueryLlmTemperature())
                            .timeout(Duration.ofSeconds(Math.max(1L,
                                    adiProperties.getIndexing().getGraphRequestTimeoutSeconds())))
                            // GraphExtractionRequestExecutor owns retry admission and
                            // backoff so provider-internal retries cannot multiply it.
                            .maxRetries(0)
                            .build()
            );

            //Ingest document. Canonical chunks keep the graph segments aligned
            //with the vector and BM25 branches (same text, real chunk uuids);
            //a missing snapshot keeps the legacy re-splitting path.
            graphRag.ingest(
                    GraphIngestParam.builder()
                            .user(user)
                            .document(document)
                            .overlap(knowledgeBase.getIngestMaxOverlap())
                            .strategy(knowledgeBase.getIngestSplitStrategy())
                            .maxSegmentSize(knowledgeBase.getIngestMaxSegmentSize())
                            .customSeparator(knowledgeBase.getIngestCustomSeparator())
                            .tokenEstimator(knowledgeBase.getIngestTokenEstimator())
                            .ChatModel(ChatModel)
                            .identifyColumns(List.of(ZhiMeshConstant.MetadataKey.KB_UUID))
                            .appendColumns(List.of(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID))
                            .chunkSetUuid(snapshot == null ? "" : snapshot.chunkSet().getUuid())
                            .graphModelId(knowledgeBase.getIngestModelId())
                            .graphIndexVersionUuid(graphIndexVersionUuid)
                            .isFreeToken(llmService.getAiModel().getIsFree())
                            .build(),
                    snapshot == null ? null : snapshot.segments()
            );
            boolean published = ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .eq(snapshot != null, KnowledgeBaseItem::getActiveChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getGraphicalStatusChangeTime, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getGraphicalCompletedAt, LocalDateTime.now())
                    .set(snapshot != null, KnowledgeBaseItem::getGraphicalChunkSetUuid,
                            snapshot == null ? "" : snapshot.chunkSet().getUuid())
                    .set(KnowledgeBaseItem::getGraphicalStatus, GraphicalStatusEnum.DONE)
                    .update();
            if (!published && snapshot != null) {
                // Source changed while extraction was running. Do not let this graph
                // contribution masquerade as the new canonical generation.
                graphRag.cleanupDocument(kbItem.getKbUuid(), kbItem.getUuid());
                log.warn("Discarded stale graph completion, kbItemUuid:{}, chunkSetUuid:{}",
                        kbItem.getUuid(), snapshot.chunkSet().getUuid());
            }
        } catch (Exception e) {
            log.error("ingestForGraph error, kbUuid:{}, kbItemUuid:{}, title:{}",
                    kbItem.getKbUuid(), kbItem.getUuid(), kbItem.getTitle(), e);
            if (graphIngestLockAcquired && graphRag != null) {
                try {
                    // Remove graph elements and segments written before the failure.
                    graphRag.cleanupDocument(kbItem.getKbUuid(), kbItem.getUuid());
                } catch (Exception cleanupException) {
                    log.error("cleanup failed graph ingestion, kbUuid:{}, kbItemUuid:{}, title:{}",
                            kbItem.getKbUuid(), kbItem.getUuid(), kbItem.getTitle(), cleanupException);
                }
            }
            ChainWrappers.lambdaUpdateChain(baseMapper)
                    .eq(KnowledgeBaseItem::getId, kbItem.getId())
                    .set(KnowledgeBaseItem::getGraphicalStatusChangeTime, LocalDateTime.now())
                    .set(KnowledgeBaseItem::getGraphicalStatus, GraphicalStatusEnum.FAIL)
                    .update();
        } finally {
            if (graphIngestLockAcquired) {
                graphIngestSemaphore.release();
            }
        }
    }

    @Transactional
    public boolean softDelete(String uuid) {
        checkWritePrivilege(uuid);
        KnowledgeBaseItem item = baseMapper.getByUuid(uuid);
        if (item == null) {
            return false;
        }
        // Remove derived FULLTEXT rows before their canonical chunks, then remove
        // the item itself. This keeps all PostgreSQL-backed retrieval state scoped
        // to the same transaction.
        bm25IndexService.deleteByItemUuid(uuid);
        canonicalChunkIndexService.deleteByItemUuid(uuid);
        boolean success = baseMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getUuid, uuid)) > 0;
        if (!success) {
            return false;
        }
        iKnowledgeEmbeddingService.deleteByItemUuid(uuid);
        cleanupDeletedItemGraph(item);

        stringRedisTemplate.opsForSet().add(KB_STATISTIC_RECALCULATE_SIGNAL, item.getKbUuid());
        return true;
    }

    /**
     * Queues the graph cleanup of a deleted item. The item row is gone (or
     * about to be, once the surrounding transaction commits), so provenance is
     * the only remaining record of its graph contribution; dropping it is what
     * makes shared vertices reclaimable. Never blocks the caller: the
     * background executor owns the graph lock wait, and a rejection falls back
     * to a synchronous best-effort attempt.
     */
    private void cleanupDeletedItemGraph(KnowledgeBaseItem item) {
        if (item.getGraphicalStatus() == null || item.getGraphicalStatus() == GraphicalStatusEnum.NONE) {
            return;
        }
        try {
            self.cleanupDeletedItemGraphAsync(item.getKbUuid(), item.getUuid());
        } catch (RuntimeException exception) {
            log.warn("Graph cleanup of deleted item could not be queued, kbItemUuid:{}",
                    item.getUuid(), exception);
            try {
                cleanupDeletedItemGraphQuietly(item.getKbUuid(), item.getUuid());
            } catch (RuntimeException fallbackException) {
                log.error("Graph cleanup of deleted item failed, kbUuid:{}, kbItemUuid:{}",
                        item.getKbUuid(), item.getUuid(), fallbackException);
            }
        }
    }

    @Async("backgroundExecutor")
    public void cleanupDeletedItemGraphAsync(String kbUuid, String kbItemUuid) {
        cleanupDeletedItemGraphQuietly(kbUuid, kbItemUuid);
    }

    private void cleanupDeletedItemGraphQuietly(String kbUuid, String kbItemUuid) {
        GraphRag graphRag = GraphRagContext.get(KNOWLEDGE_BASE);
        if (null == graphRag) {
            return;
        }
        try {
            graphRag.cleanupDocument(kbUuid, kbItemUuid);
        } catch (Exception exception) {
            log.error("Unable to clean graph contribution of deleted item, kbUuid:{}, kbItemUuid:{}",
                    kbUuid, kbItemUuid, exception);
        }
    }

    /**
     * Recovers graphical statuses stuck in DOING after a crash or restart.
     * Only statuses whose last change is older than the configured timeout are
     * failed, so a live long-running ingestion is left alone; its own DONE
     * update then overwrites the recovered FAIL because completion is not
     * guarded on the previous status. Recovering to FAIL (not NONE) keeps the
     * failure visible and lets the user retry indexing, which the DOING guard
     * in {@link #asyncIndex} would otherwise keep blocking forever.
     */
    public int failTimedOutGraphIndexing() {
        return failTimedOutGraphIndexing(LocalDateTime.now());
    }

    int failTimedOutGraphIndexing(LocalDateTime now) {
        long timeoutMinutes = Math.max(1L, adiProperties.getIndexing().getGraphDoingTimeoutMinutes());
        LocalDateTime staleBefore = now.minusMinutes(timeoutMinutes);
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = new LambdaUpdateWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getGraphicalStatus, GraphicalStatusEnum.DOING)
                .lt(KnowledgeBaseItem::getGraphicalStatusChangeTime, staleBefore)
                .set(KnowledgeBaseItem::getGraphicalStatus, GraphicalStatusEnum.FAIL)
                .set(KnowledgeBaseItem::getGraphicalStatusChangeTime, now);
        int recovered = baseMapper.update(null, wrapper);
        if (recovered > 0) {
            log.warn("Recovered {} item(s) from abandoned graph indexing, timeoutMinutes:{}",
                    recovered, timeoutMinutes);
        }
        return recovered;
    }

    public int countByKbUuid(String kbUuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBaseItem::getKbUuid, kbUuid)
                
                .count()
                .intValue();
    }

    public int countTodayCreated() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), 0, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(1);
        return baseMapper.countCreatedByTimePeriod(beginTime, endTime);
    }

    public int countAllCreated() {
        return baseMapper.countAllCreated();
    }

    /**
     * Fetch a knowledge-base item by uuid after a read-privilege check. Both the
     * check and the query operate on the item itself, so they are kept together
     * here rather than split across the controller.
     */
    public KnowledgeBaseItem info(String uuid) {
        checkReadPrivilege(uuid);
        return getEnable(uuid);
    }

    /**
     * Write-privilege check: allows the owner or an admin. Throws
     * {@link com.pppp.zhimesh.common.enums.ErrorEnum#A_USER_NOT_AUTH} on denial.
     * Used by single write operations such as delete.
     */
    public void checkWritePrivilege(String uuid) {
        if (!hasWritePrivilege(uuid)) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
    }

    /**
     * Write-privilege probe: returns whether the owner or an admin may write,
     * without throwing. Used by batch flows (e.g. indexing) that skip items the
     * current user is not allowed to touch instead of aborting the whole batch.
     */
    public boolean hasWritePrivilege(String uuid) {
        if (StringUtils.isBlank(uuid)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        User user = ThreadContext.getCurrentUser();
        if (null == user) {
            throw new BaseException(A_USER_NOT_EXIST);
        }
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            return true;
        }
        return baseMapper.checkWritePrivilege(uuid, user.getId()) > 0;
    }

    /**
     * Write-privilege check keyed by knowledge-base uuid: allows the owner of the
     * knowledge base or an admin. Throws
     * {@link com.pppp.zhimesh.common.enums.ErrorEnum#A_USER_NOT_AUTH} on denial.
     * Used when creating a new item, where no item uuid exists yet.
     */
    public void checkWritePrivilegeByKb(String kbUuid) {
        if (!hasWritePrivilegeByKb(kbUuid)) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
    }

    /**
     * Write-privilege probe keyed by knowledge-base uuid: returns whether the
     * owner of the knowledge base or an admin may write, without throwing.
     */
    public boolean hasWritePrivilegeByKb(String kbUuid) {
        if (StringUtils.isBlank(kbUuid)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        User user = ThreadContext.getCurrentUser();
        if (null == user) {
            throw new BaseException(A_USER_NOT_EXIST);
        }
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            return true;
        }
        return baseMapper.checkWritePrivilegeByKb(kbUuid, user.getId()) > 0;
    }

    /**
     * Write-privilege check keyed by item id: allows the owner of the item's
     * knowledge base or an admin. Throws
     * {@link com.pppp.zhimesh.common.enums.ErrorEnum#A_USER_NOT_AUTH} on denial.
     * Used when updating an item, where the real target is resolved by id rather
     * than the client-controlled uuid.
     */
    public void checkWritePrivilegeById(Long id) {
        if (!hasWritePrivilegeById(id)) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
    }

    /**
     * Write-privilege probe keyed by item id: returns whether the owner of the
     * item's knowledge base or an admin may write, without throwing.
     */
    public boolean hasWritePrivilegeById(Long id) {
        if (null == id || id < 1) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        User user = ThreadContext.getCurrentUser();
        if (null == user) {
            throw new BaseException(A_USER_NOT_EXIST);
        }
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            return true;
        }
        return baseMapper.checkWritePrivilegeById(id, user.getId()) > 0;
    }

    /**
     * Read-privilege check: allows the owner, an admin, or anyone when the
     * owning knowledge base is public. Used by reads of an item and its derived
     * content (embeddings, graph). Denials are reported as
     * {@link com.pppp.zhimesh.common.enums.ErrorEnum#A_DATA_NOT_FOUND} to avoid
     * leaking the existence of other users' private knowledge bases.
     */
    public void checkReadPrivilege(String uuid) {
        if (StringUtils.isBlank(uuid)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        User user = ThreadContext.getCurrentUser();
        if (null == user) {
            throw new BaseException(A_USER_NOT_EXIST);
        }
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            return;
        }
        if (baseMapper.checkReadPrivilege(uuid, user.getId()) == 0) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
    }
}
