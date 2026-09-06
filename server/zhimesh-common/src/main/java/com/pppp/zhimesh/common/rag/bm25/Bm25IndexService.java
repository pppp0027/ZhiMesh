package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.service.CanonicalChunkSnapshot;
import com.pppp.zhimesh.common.util.UuidUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Builds and atomically publishes one item's centralized FULLTEXT index. */
@Service
public class Bm25IndexService {

    private final Bm25Repository repository;
    private final Bm25Tokenizer tokenizer;
    private final Bm25ReadinessService readinessService;
    private final ZhiMeshProperties properties;

    public Bm25IndexService(Bm25Repository repository,
                            Bm25Tokenizer tokenizer,
                            Bm25ReadinessService readinessService,
                            ZhiMeshProperties properties) {
        this.repository = repository;
        this.tokenizer = tokenizer;
        this.readinessService = readinessService;
        this.properties = properties;
    }

    public boolean isReady(Collection<String> kbUuids) {
        if (kbUuids == null || kbUuids.isEmpty()) {
            return false;
        }
        return readinessService.readyKnowledgeBases(kbUuids).containsAll(kbUuids);
    }

    /** Convenience API used by the canonical indexing orchestration. */
    @Transactional
    public void rebuild(CanonicalChunkSnapshot snapshot) {
        if (snapshot == null || snapshot.chunkSet() == null) {
            throw new IllegalArgumentException("Canonical chunk snapshot is required");
        }
        String kbItemUuid = snapshot.chunkSet().getKbItemUuid();
        if (StringUtils.isBlank(kbItemUuid)) {
            throw new IllegalArgumentException("Canonical chunk-set item identity is required");
        }
        KnowledgeBaseItem item = repository.lockItemForUpdate(kbItemUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Knowledge-base item not found: " + kbItemUuid));
        rebuildLocked(item, snapshot);
    }

    /**
     * Replaces postings/documents and flips readiness in one PostgreSQL transaction.
     * Any exception restores both the old physical index and old ACTIVE build.
     */
    @Transactional
    public Bm25IndexBuildResult rebuild(KnowledgeBaseItem item, CanonicalChunkSnapshot snapshot) {
        if (item == null || StringUtils.isBlank(item.getUuid())) {
            throw new IllegalArgumentException("Knowledge-base item identity is required");
        }
        KnowledgeBaseItem lockedItem = repository.lockItemForUpdate(item.getUuid())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Knowledge-base item not found: " + item.getUuid()));
        return rebuildLocked(lockedItem, snapshot);
    }

    private Bm25IndexBuildResult rebuildLocked(KnowledgeBaseItem item,
                                                CanonicalChunkSnapshot snapshot) {
        if (!properties.getRetrieval().getBm25().isEnabled()) {
            throw new Bm25UnavailableException("BM25 indexing is disabled");
        }
        validateConfig();
        List<KnowledgeBaseChunk> chunks = validateSnapshot(item, snapshot);
        KnowledgeBaseChunkSet chunkSet = snapshot.chunkSet();
        String analyzerVersion = tokenizer.analyzerVersion();
        String buildKeyHash = sha256(chunkSet.getUuid() + "\u0000" + analyzerVersion);

        var existing = repository.findActiveBuild(
                item.getUuid(), chunkSet.getUuid(), analyzerVersion, buildKeyHash);
        if (existing.isPresent()) {
            int documentCount = repository.countDocumentsForBuild(existing.get().uuid());
            if (documentCount == chunks.size()) {
                return new Bm25IndexBuildResult(existing.get().uuid(), chunkSet.getUuid(),
                        documentCount, 0, analyzerVersion);
            }
            // The ACTIVE marker is inconsistent with the physical index. Move it
            // outside the inflight unique-index predicate before inserting a
            // replacement with the same deterministic build key. The item row
            // lock and surrounding transaction keep this recovery atomic.
            repository.supersedeBuild(item.getUuid(), existing.get().uuid());
        }

        LocalDateTime now = LocalDateTime.now();
        String buildUuid = UuidUtil.createShort();
        repository.createBuildingIndex(new Bm25BuildRecord(
                buildUuid, item.getKbId(), item.getKbUuid(), item.getId(), item.getUuid(),
                chunkSet.getUuid(), analyzerVersion, buildKeyHash, now));

        List<Bm25DocumentRecord> documents = new ArrayList<>(chunks.size());
        List<Bm25PostingRecord> postings = new ArrayList<>();
        for (KnowledgeBaseChunk chunk : chunks) {
            Bm25Analysis analysis = tokenizer.analyze(chunk.getContent());
            documents.add(new Bm25DocumentRecord(
                    chunk.getUuid(), buildUuid, analyzerVersion, item.getKbUuid(), item.getUuid(),
                    chunkSet.getUuid(), analysis.documentLength(), now));
            analysis.termFrequencies().forEach((term, frequency) -> postings.add(
                    new Bm25PostingRecord(buildUuid, chunk.getUuid(), term, frequency)));
        }

        repository.deletePhysicalIndexForItem(item.getUuid());
        repository.insertDocuments(documents);
        repository.insertPostings(postings);
        repository.activateBuild(item.getUuid(), buildUuid, chunkSet.getUuid(), LocalDateTime.now());
        return new Bm25IndexBuildResult(
                buildUuid, chunkSet.getUuid(), documents.size(), postings.size(), analyzerVersion);
    }

    @Transactional
    public void deleteByItemUuid(String kbItemUuid) {
        if (StringUtils.isBlank(kbItemUuid)) {
            throw new IllegalArgumentException("kbItemUuid must not be blank");
        }
        String normalized = kbItemUuid.trim();
        // Serialize deletion with a possible asynchronous rebuild. If the item
        // has already been physically removed there is no row to lock, but its
        // orphaned lexical records still need cleanup.
        repository.lockItemForUpdate(normalized);
        repository.deleteItemIndex(normalized);
    }

    private List<KnowledgeBaseChunk> validateSnapshot(KnowledgeBaseItem item,
                                                       CanonicalChunkSnapshot snapshot) {
        if (item == null || StringUtils.isAnyBlank(item.getUuid(), item.getKbUuid())) {
            throw new IllegalArgumentException("Knowledge-base item identity is required");
        }
        if (snapshot == null || snapshot.chunkSet() == null) {
            throw new IllegalArgumentException("Canonical chunk snapshot is required");
        }
        KnowledgeBaseChunkSet chunkSet = snapshot.chunkSet();
        if (StringUtils.isBlank(chunkSet.getUuid())) {
            throw new IllegalArgumentException("Canonical chunk-set identity is required");
        }
        if (!item.getUuid().equals(chunkSet.getKbItemUuid())
                || !item.getKbUuid().equals(chunkSet.getKbUuid())) {
            throw new IllegalArgumentException("Canonical chunk snapshot belongs to another item");
        }
        if (!chunkSet.getUuid().equals(item.getActiveChunkSetUuid())
                || !Boolean.TRUE.equals(chunkSet.getIsActive())
                || !"ACTIVE".equalsIgnoreCase(chunkSet.getStatus())) {
            throw new IllegalArgumentException("BM25 can only index the item's ACTIVE canonical chunk set");
        }
        List<KnowledgeBaseChunk> chunks = snapshot.chunks() == null
                ? List.of()
                : snapshot.chunks().stream()
                        .sorted(Comparator.comparingInt(KnowledgeBaseChunk::getChunkIndex))
                        .toList();
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Canonical chunk snapshot contains no chunks");
        }
        if (chunkSet.getChunkCount() != null && chunkSet.getChunkCount() != chunks.size()) {
            throw new IllegalArgumentException("Canonical chunk count does not match its chunk set");
        }
        if (snapshot.segments() != null && !snapshot.segments().isEmpty()
                && snapshot.segments().size() != chunks.size()) {
            throw new IllegalArgumentException("Canonical chunks and text segments have different sizes");
        }
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeBaseChunk chunk = chunks.get(index);
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() != index
                    || StringUtils.isAnyBlank(chunk.getUuid(), chunk.getContent())
                    || !chunkSet.getUuid().equals(chunk.getChunkSetUuid())
                    || !item.getUuid().equals(chunk.getKbItemUuid())
                    || !item.getKbUuid().equals(chunk.getKbUuid())) {
                throw new IllegalArgumentException("Invalid canonical chunk at position " + index);
            }
        }
        return chunks;
    }

    private void validateConfig() {
        double k1 = properties.getRetrieval().getBm25().getK1();
        double b = properties.getRetrieval().getBm25().getB();
        if (!Double.isFinite(k1) || k1 <= 0D) {
            throw new IllegalArgumentException("BM25 k1 must be finite and greater than zero");
        }
        if (!Double.isFinite(b) || b < 0D || b > 1D) {
            throw new IllegalArgumentException("BM25 b must be between zero and one");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
