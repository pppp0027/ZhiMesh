package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.enums.FulltextStatusEnum;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkSetMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.rag.DocumentSplitterFactory;
import com.pppp.zhimesh.common.rag.TokenEstimatorFactory;
import com.pppp.zhimesh.common.util.UuidUtil;
import dev.langchain4j.data.document.DefaultDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds and activates the single canonical chunk set used by all downstream indexes.
 * The item row is locked before lookup/build so separate application instances serialize
 * competing builds for the same item in PostgreSQL rather than relying on a JVM lock.
 */
@Service
public class CanonicalChunkIndexService {

    static final String SPLITTER_VERSION = "zhimesh-document-splitter-v1";
    static final String PREPROCESSOR_VERSION = "identity-v1";

    private static final String STATUS_BUILDING = "BUILDING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final Set<String> REUSABLE_STATUSES = Set.of(
            STATUS_READY, STATUS_ACTIVE, STATUS_SUPERSEDED);

    private final KnowledgeBaseChunkSetMapper chunkSetMapper;
    private final KnowledgeBaseChunkMapper chunkMapper;
    private final KnowledgeBaseItemMapper itemMapper;

    public CanonicalChunkIndexService(KnowledgeBaseChunkSetMapper chunkSetMapper,
                                      KnowledgeBaseChunkMapper chunkMapper,
                                      KnowledgeBaseItemMapper itemMapper) {
        this.chunkSetMapper = chunkSetMapper;
        this.chunkMapper = chunkMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * Returns an active snapshot, reusing an equivalent complete chunk set or building it once.
     */
    @Transactional(rollbackFor = Exception.class)
    public CanonicalChunkSnapshot index(KnowledgeBase knowledgeBase, KnowledgeBaseItem item) {
        validateIdentity(knowledgeBase, item);
        KnowledgeBaseItem lockedItem = lockItem(item.getUuid());
        validateIdentity(knowledgeBase, lockedItem);

        SplitConfig config = splitConfig(knowledgeBase);
        String sourceContentHash = sha256(lockedItem.getRemark());
        String splitConfigHash = splitConfigHash(config);
        KnowledgeBaseChunkSet chunkSet = findEquivalent(
                lockedItem.getUuid(), sourceContentHash, splitConfigHash);

        if (chunkSet == null) {
            chunkSet = createBuildingChunkSet(
                    knowledgeBase, lockedItem, config, sourceContentHash, splitConfigHash);
        }

        List<KnowledgeBaseChunk> chunks = listChunks(chunkSet.getUuid());
        if (!canReuse(chunkSet, chunks)) {
            chunks = rebuildChunks(knowledgeBase, lockedItem, chunkSet, config);
        }

        activate(chunkSet, lockedItem, chunks);
        item.setActiveChunkSetUuid(chunkSet.getUuid());
        return snapshot(chunkSet, chunks);
    }

    /** Removes canonical chunks and clears every item-level derived chunk-set pointer. */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByItemUuid(String kbItemUuid) {
        if (StringUtils.isBlank(kbItemUuid)) {
            throw new IllegalArgumentException("kbItemUuid is required");
        }
        itemMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getUuid, kbItemUuid)
                .last("FOR UPDATE"));
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeBaseChunk>()
                .eq(KnowledgeBaseChunk::getKbItemUuid, kbItemUuid));
        chunkSetMapper.delete(new LambdaQueryWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getKbItemUuid, kbItemUuid));
        itemMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getUuid, kbItemUuid)
                .set(KnowledgeBaseItem::getActiveChunkSetUuid, "")
                .set(KnowledgeBaseItem::getEmbeddingChunkSetUuid, "")
                .set(KnowledgeBaseItem::getGraphicalChunkSetUuid, "")
                .set(KnowledgeBaseItem::getFulltextStatus, FulltextStatusEnum.NONE)
                .set(KnowledgeBaseItem::getFulltextStatusChangeTime, LocalDateTime.now())
                .set(KnowledgeBaseItem::getFulltextStartedAt, null)
                .set(KnowledgeBaseItem::getFulltextCompletedAt, null)
                .set(KnowledgeBaseItem::getFulltextChunkSetUuid, ""));
    }

    /**
     * 删除整个知识库的 canonical chunk 与 chunk set
     * Removes every canonical chunk and chunk set owned by the knowledge base.
     *
     * <p>复用作用域结论（安全约束核销）：等价 chunk set 的唯一索引是
     * {@code uk_kb_chunk_set_source_config (kb_item_uuid, source_content_hash,
     * split_config_hash)}（见 016_add_canonical_chunk_schema.sql），且
     * {@link #findEquivalent} 的查找同样以 kb_item_uuid 起头——复用从不跨条目，
     * 而一个条目只归属一个知识库。因此按 kb_uuid 过滤只会删除本库自己的快照，
     * 不可能命中他库在用快照，无需按 chunk 行归属做二次限定，也不会留下孤儿 set。</p>
     *
     * <p>Reuse-scope conclusion (safety constraint): equivalent chunk sets are
     * keyed by {@code (kb_item_uuid, source_content_hash, split_config_hash)}
     * and {@link #findEquivalent} scopes its lookup to one item, so a chunk set
     * is never shared across knowledge bases. Filtering by kb_uuid therefore
     * drops only this knowledge base's own snapshots; no surviving reference
     * from another knowledge base can be orphaned or destroyed.</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKbUuid(String kbUuid) {
        if (StringUtils.isBlank(kbUuid)) {
            throw new IllegalArgumentException("kbUuid is required");
        }
        // The owning items are removed separately by the knowledge-base
        // deletion flow, so there is no item-level pointer left to reset.
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeBaseChunk>()
                .eq(KnowledgeBaseChunk::getKbUuid, kbUuid));
        chunkSetMapper.delete(new LambdaQueryWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getKbUuid, kbUuid));
    }

    private KnowledgeBaseItem lockItem(String itemUuid) {
        KnowledgeBaseItem locked = itemMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getUuid, itemUuid)
                .last("FOR UPDATE"));
        if (locked == null) {
            throw new IllegalStateException("Knowledge-base item does not exist: " + itemUuid);
        }
        return locked;
    }

    private KnowledgeBaseChunkSet findEquivalent(String itemUuid, String sourceHash, String configHash) {
        return chunkSetMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getKbItemUuid, itemUuid)
                .eq(KnowledgeBaseChunkSet::getSourceContentHash, sourceHash)
                .eq(KnowledgeBaseChunkSet::getSplitConfigHash, configHash));
    }

    private KnowledgeBaseChunkSet createBuildingChunkSet(KnowledgeBase knowledgeBase,
                                                          KnowledgeBaseItem item,
                                                          SplitConfig config,
                                                          String sourceHash,
                                                          String configHash) {
        KnowledgeBaseChunkSet chunkSet = new KnowledgeBaseChunkSet();
        chunkSet.setUuid(UuidUtil.createShort());
        chunkSet.setKbId(knowledgeBase.getId());
        chunkSet.setKbUuid(knowledgeBase.getUuid());
        chunkSet.setKbItemId(item.getId());
        chunkSet.setKbItemUuid(item.getUuid());
        chunkSet.setSourceContentHash(sourceHash);
        chunkSet.setSplitStrategy(config.strategy());
        chunkSet.setMaxSegmentSize(config.maxSegmentSize());
        chunkSet.setOverlap(config.overlap());
        chunkSet.setCustomSeparator(config.customSeparator());
        chunkSet.setTokenEstimator(config.tokenEstimator());
        chunkSet.setSplitterVersion(SPLITTER_VERSION);
        chunkSet.setPreprocessorVersion(PREPROCESSOR_VERSION);
        chunkSet.setSplitConfigHash(configHash);
        chunkSet.setChunkCount(0);
        chunkSet.setTotalTokens(0);
        chunkSet.setStatus(STATUS_BUILDING);
        chunkSet.setIsActive(false);
        chunkSet.setStartedAt(LocalDateTime.now());
        chunkSetMapper.insert(chunkSet);
        if (chunkSet.getId() == null) {
            throw new IllegalStateException("Canonical chunk-set insert did not return an id");
        }
        return chunkSet;
    }

    private boolean canReuse(KnowledgeBaseChunkSet chunkSet, List<KnowledgeBaseChunk> chunks) {
        return REUSABLE_STATUSES.contains(chunkSet.getStatus())
                && chunkSet.getChunkCount() != null
                && chunkSet.getChunkCount() == chunks.size();
    }

    private List<KnowledgeBaseChunk> rebuildChunks(KnowledgeBase knowledgeBase,
                                                    KnowledgeBaseItem item,
                                                    KnowledgeBaseChunkSet chunkSet,
                                                    SplitConfig config) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeBaseChunk>()
                .eq(KnowledgeBaseChunk::getChunkSetUuid, chunkSet.getUuid()));
        LocalDateTime startedAt = LocalDateTime.now();
        chunkSetMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getId, chunkSet.getId())
                .set(KnowledgeBaseChunkSet::getStatus, STATUS_BUILDING)
                .set(KnowledgeBaseChunkSet::getIsActive, false)
                .set(KnowledgeBaseChunkSet::getChunkCount, 0)
                .set(KnowledgeBaseChunkSet::getTotalTokens, 0)
                .set(KnowledgeBaseChunkSet::getStartedAt, startedAt)
                .set(KnowledgeBaseChunkSet::getCompletedAt, null)
                .set(KnowledgeBaseChunkSet::getErrorType, null)
                .set(KnowledgeBaseChunkSet::getErrorMessage, null));
        chunkSet.setStatus(STATUS_BUILDING);
        chunkSet.setIsActive(false);
        chunkSet.setStartedAt(startedAt);
        chunkSet.setCompletedAt(null);

        TokenCountEstimator estimator = TokenEstimatorFactory.create(config.tokenEstimator());
        DocumentSplitter splitter = DocumentSplitterFactory.create(
                config.strategy(), config.maxSegmentSize(), config.overlap(),
                config.customSeparator(), estimator);
        Metadata documentMetadata = new Metadata(Map.of(
                ZhiMeshConstant.MetadataKey.KB_UUID, knowledgeBase.getUuid(),
                ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, item.getUuid()));
        Document document = new DefaultDocument(item.getRemark(), documentMetadata);
        List<TextSegment> rawSegments = splitter.split(document);
        List<KnowledgeBaseChunk> chunks = new ArrayList<>(rawSegments.size());
        for (int index = 0; index < rawSegments.size(); index++) {
            TextSegment raw = rawSegments.get(index);
            KnowledgeBaseChunk chunk = new KnowledgeBaseChunk();
            chunk.setUuid(UuidUtil.createShort());
            chunk.setChunkSetId(chunkSet.getId());
            chunk.setChunkSetUuid(chunkSet.getUuid());
            chunk.setKbId(knowledgeBase.getId());
            chunk.setKbUuid(knowledgeBase.getUuid());
            chunk.setKbItemId(item.getId());
            chunk.setKbItemUuid(item.getUuid());
            chunk.setChunkIndex(index);
            chunk.setContent(raw.text());
            chunk.setContentHash(sha256(raw.text()));
            chunk.setTokenCount(Math.max(0, estimator.estimateTokenCountInText(raw.text())));
            // Recursive and overlap-based splitting cannot always provide unambiguous offsets.
            chunk.setCharStart(null);
            chunk.setCharEnd(null);
            chunkMapper.insert(chunk);
            chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    private void activate(KnowledgeBaseChunkSet chunkSet,
                          KnowledgeBaseItem item,
                          List<KnowledgeBaseChunk> chunks) {
        int totalTokens = chunks.stream()
                .map(KnowledgeBaseChunk::getTokenCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        LocalDateTime completedAt = LocalDateTime.now();

        chunkSetMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getKbItemUuid, item.getUuid())
                .eq(KnowledgeBaseChunkSet::getIsActive, true)
                .ne(KnowledgeBaseChunkSet::getUuid, chunkSet.getUuid())
                .set(KnowledgeBaseChunkSet::getStatus, STATUS_SUPERSEDED)
                .set(KnowledgeBaseChunkSet::getIsActive, false));
        chunkSetMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getId, chunkSet.getId())
                .set(KnowledgeBaseChunkSet::getStatus, STATUS_ACTIVE)
                .set(KnowledgeBaseChunkSet::getIsActive, true)
                .set(KnowledgeBaseChunkSet::getChunkCount, chunks.size())
                .set(KnowledgeBaseChunkSet::getTotalTokens, totalTokens)
                .set(KnowledgeBaseChunkSet::getCompletedAt, completedAt)
                .set(KnowledgeBaseChunkSet::getErrorType, null)
                .set(KnowledgeBaseChunkSet::getErrorMessage, null));
        LambdaUpdateWrapper<KnowledgeBaseItem> itemUpdate = new LambdaUpdateWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getId, item.getId())
                .set(KnowledgeBaseItem::getActiveChunkSetUuid, chunkSet.getUuid());
        if (!Objects.equals(item.getFulltextChunkSetUuid(), chunkSet.getUuid())) {
            itemUpdate.set(KnowledgeBaseItem::getFulltextStatus, FulltextStatusEnum.NONE)
                    .set(KnowledgeBaseItem::getFulltextStatusChangeTime, completedAt)
                    .set(KnowledgeBaseItem::getFulltextStartedAt, null)
                    .set(KnowledgeBaseItem::getFulltextCompletedAt, null)
                    .set(KnowledgeBaseItem::getFulltextChunkSetUuid, "");
        }
        itemMapper.update(null, itemUpdate);

        chunkSet.setStatus(STATUS_ACTIVE);
        chunkSet.setIsActive(true);
        chunkSet.setChunkCount(chunks.size());
        chunkSet.setTotalTokens(totalTokens);
        chunkSet.setCompletedAt(completedAt);
        chunkSet.setErrorType(null);
        chunkSet.setErrorMessage(null);
        item.setActiveChunkSetUuid(chunkSet.getUuid());
    }

    private List<KnowledgeBaseChunk> listChunks(String chunkSetUuid) {
        return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeBaseChunk>()
                .eq(KnowledgeBaseChunk::getChunkSetUuid, chunkSetUuid)
                .orderByAsc(KnowledgeBaseChunk::getChunkIndex));
    }

    private CanonicalChunkSnapshot snapshot(KnowledgeBaseChunkSet chunkSet,
                                            List<KnowledgeBaseChunk> chunks) {
        List<TextSegment> segments = chunks.stream().map(chunk -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(ZhiMeshConstant.MetadataKey.KB_UUID, chunk.getKbUuid());
            metadata.put(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID, chunk.getKbItemUuid());
            metadata.put(ZhiMeshConstant.MetadataKey.CHUNK_SET_UUID, chunk.getChunkSetUuid());
            metadata.put(ZhiMeshConstant.MetadataKey.CHUNK_UUID, chunk.getUuid());
            return TextSegment.from(chunk.getContent(), new Metadata(metadata));
        }).toList();
        return new CanonicalChunkSnapshot(chunkSet, chunks, segments);
    }

    private static void validateIdentity(KnowledgeBase knowledgeBase, KnowledgeBaseItem item) {
        Objects.requireNonNull(knowledgeBase, "knowledgeBase");
        Objects.requireNonNull(item, "item");
        if (knowledgeBase.getId() == null || StringUtils.isBlank(knowledgeBase.getUuid())) {
            throw new IllegalArgumentException("Persisted knowledgeBase id and uuid are required");
        }
        if (item.getId() == null || StringUtils.isBlank(item.getUuid()) || item.getRemark() == null) {
            throw new IllegalArgumentException("Persisted item id, uuid and content are required");
        }
        if (!Objects.equals(knowledgeBase.getId(), item.getKbId())
                || !Objects.equals(knowledgeBase.getUuid(), item.getKbUuid())) {
            throw new IllegalArgumentException("Knowledge base and item lineage do not match");
        }
    }

    private static SplitConfig splitConfig(KnowledgeBase knowledgeBase) {
        String strategy = switch (StringUtils.defaultString(knowledgeBase.getIngestSplitStrategy()).trim()) {
            case ZhiMeshConstant.SplitStrategy.PARAGRAPH -> ZhiMeshConstant.SplitStrategy.PARAGRAPH;
            case ZhiMeshConstant.SplitStrategy.LINE -> ZhiMeshConstant.SplitStrategy.LINE;
            case ZhiMeshConstant.SplitStrategy.SENTENCE -> ZhiMeshConstant.SplitStrategy.SENTENCE;
            case ZhiMeshConstant.SplitStrategy.CUSTOM -> ZhiMeshConstant.SplitStrategy.CUSTOM;
            default -> ZhiMeshConstant.SplitStrategy.RECURSIVE;
        };
        int maxSegmentSize = knowledgeBase.getIngestMaxSegmentSize() == null
                ? ZhiMeshConstant.RAG_MAX_SEGMENT_SIZE_IN_TOKENS
                : knowledgeBase.getIngestMaxSegmentSize();
        int overlap = knowledgeBase.getIngestMaxOverlap() == null ? 0 : knowledgeBase.getIngestMaxOverlap();
        if (maxSegmentSize <= 0 || overlap < 0 || overlap >= maxSegmentSize) {
            throw new IllegalArgumentException("Invalid canonical chunk maxSegmentSize/overlap");
        }
        String separator = StringUtils.defaultString(knowledgeBase.getIngestCustomSeparator());
        if (ZhiMeshConstant.SplitStrategy.CUSTOM.equals(strategy) && separator.isEmpty()) {
            throw new IllegalArgumentException("custom separator is required for custom splitting");
        }
        String estimator = StringUtils.defaultIfBlank(
                knowledgeBase.getIngestTokenEstimator(), ZhiMeshConstant.TokenEstimator.OPENAI);
        return new SplitConfig(strategy, maxSegmentSize, overlap, separator, estimator);
    }

    static String splitConfigHash(SplitConfig config) {
        MessageDigest digest = sha256Digest();
        updateField(digest, config.strategy());
        updateField(digest, Integer.toString(config.maxSegmentSize()));
        updateField(digest, Integer.toString(config.overlap()));
        updateField(digest, config.customSeparator());
        updateField(digest, config.tokenEstimator());
        updateField(digest, SPLITTER_VERSION);
        updateField(digest, PREPROCESSOR_VERSION);
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(String text) {
        return HexFormat.of().formatHex(
                sha256Digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    record SplitConfig(String strategy, int maxSegmentSize, int overlap,
                       String customSeparator, String tokenEstimator) {
    }
}
