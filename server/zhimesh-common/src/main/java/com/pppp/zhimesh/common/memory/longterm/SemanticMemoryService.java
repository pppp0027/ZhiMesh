package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.memory.vo.BatchActionMemories;
import com.pppp.zhimesh.common.util.LocalDateTimeUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.CHARACTER_ID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.LAST_SOURCE_MESSAGE_ID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.MEMORY_TYPE;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.MEMORY_UPDATED_AT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.USER_ID;

/**
 * Semantic memory service: deduplicating merge candidates retrieval and
 * ADD / UPDATE / DELETE execution against the semantic vector store. Does not
 * call LLMs and does not account for cost — the caller (dispatcher) drives those.
 * <p>
 * 语义记忆服务：负责合并候选检索和 ADD / UPDATE / DELETE 执行。不调 LLM、不计 token 成本，
 * 由调度器统筹这些事项。物理隔离后只在 semantic store 上工作，不再过滤 memory_type。
 */
@Slf4j
@Service
public class SemanticMemoryService {

    @Resource
    @Qualifier("semanticEmbeddingStore")
    private EmbeddingStore<TextSegment> semanticEmbeddingStore;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private ZhiMeshProperties adiProperties;

    /**
     * For each fact, find its top-K nearest neighbours in the semantic store. Each
     * fact keeps its own tmpId space so the LLM never sees ambiguous IDs across facts.
     * <p>
     * 对每条 fact 检索 top-K 候选。每条 fact 独立的 tmpId 空间，避免 LLM 跨 fact 时 id 歧义。
     */
    public MergeCandidates searchMergeCandidates(List<String> facts, Long characterId) {
        Map<String, List<Map<String, String>>> factToOldMemoriesForPrompt = new LinkedHashMap<>();
        Map<String, Map<String, String>> factToTmpIdToEmbeddingId = new HashMap<>();
        Map<String, Map<String, EmbeddingMatch<TextSegment>>> factToEmbeddingIdToMatch = new HashMap<>();
        double minScore = adiProperties.getMemory() != null
                ? adiProperties.getMemory().getMinScore()
                : ZhiMeshConstant.LONG_TERM_MEMORY_MIN_SCORE_DEFAULT;

        for (String fact : facts) {
            if (StringUtils.isBlank(fact)) {
                continue;
            }
            Embedding embedding = embeddingModel.embed(fact).content();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embedding)
                    .maxResults(5)
                    .minScore(minScore)
                    .filter(new IsEqualTo(CHARACTER_ID, characterId))
                    .build();
            EmbeddingSearchResult<TextSegment> searchResult = semanticEmbeddingStore.search(searchRequest);

            Map<String, EmbeddingMatch<TextSegment>> embeddingIdToMatch = new LinkedHashMap<>();
            searchResult.matches().forEach(item -> embeddingIdToMatch.put(item.embeddingId(), item));

            List<Map<String, String>> oldMemoryForPrompt = new ArrayList<>();
            Map<String, String> tmpIdToEmbeddingId = new HashMap<>();
            int i = 0;
            for (Map.Entry<String, EmbeddingMatch<TextSegment>> entry : embeddingIdToMatch.entrySet()) {
                String tmpId = String.valueOf(i);
                oldMemoryForPrompt.add(Map.of("id", tmpId, "text", entry.getValue().embedded().text()));
                tmpIdToEmbeddingId.put(tmpId, entry.getKey());
                i++;
            }

            factToOldMemoriesForPrompt.put(fact, oldMemoryForPrompt);
            factToTmpIdToEmbeddingId.put(fact, tmpIdToEmbeddingId);
            factToEmbeddingIdToMatch.put(fact, embeddingIdToMatch);
        }
        return new MergeCandidates(factToOldMemoriesForPrompt, factToTmpIdToEmbeddingId, factToEmbeddingIdToMatch);
    }

    /**
     * Apply ADD / UPDATE / DELETE actions to the semantic vector store. Single-action
     * failures are caught here so other actions still proceed.
     * <p>
     * 应用 ADD / UPDATE / DELETE 操作。单条失败不影响其他操作。
     */
    public void applyActions(BatchActionMemories batchActions, Long characterId, MergeCandidates candidates) {
        applyActions(batchActions, characterId, null, null, candidates);
    }

    public void applyActions(BatchActionMemories batchActions, Long characterId,
                             Long userId, Long sourceMessageId, MergeCandidates candidates) {
        if (batchActions == null || batchActions.getActions() == null) {
            return;
        }
        for (BatchActionMemories.BatchActionMemory action : batchActions.getActions()) {
            try {
                applyAction(action, characterId, userId, sourceMessageId, candidates);
            } catch (Exception ex) {
                log.warn("Apply memory action failed, action={}, error={}", action, ex.getMessage());
            }
        }
    }

    private void applyAction(BatchActionMemories.BatchActionMemory action,
                             Long characterId,
                             Long userId,
                             Long sourceMessageId,
                             MergeCandidates candidates) {
        if (action == null || StringUtils.isBlank(action.getEvent())) {
            return;
        }
        String event = action.getEvent().trim().toUpperCase();
        if (ZhiMeshConstant.MemoryEvent.NONE.equalsIgnoreCase(event)) {
            log.info("No changes required for memory id: {}", action.getId());
            return;
        }

        // ADD: no need to resolve old memories, ingest the new content directly.
        // ADD：无需定位旧记忆，直接 ingest 新内容。
        if (ZhiMeshConstant.MemoryEvent.ADD.equalsIgnoreCase(event)) {
            if (StringUtils.isBlank(action.getText())) {
                log.warn("ADD action missing text, skip");
                return;
            }
            Metadata metadata = new Metadata(semanticMetadata(
                    Map.of(), characterId, userId, sourceMessageId));
            Embedding embedding = embeddingModel.embed(action.getText()).content();
            semanticEmbeddingStore.addAll(
                    List.of(UUID.randomUUID().toString()),
                    List.of(embedding),
                    List.of(TextSegment.from(action.getText(), metadata)));
            return;
        }

        // UPDATE / DELETE: locate the old memory via the fact-scoped tmpId space.
        // UPDATE / DELETE：通过 fact-scoped tmpId 空间定位旧记忆。
        Map<String, String> tmpIdToEmbeddingId = candidates.factToTmpIdToEmbeddingId.get(action.getFact());
        Map<String, EmbeddingMatch<TextSegment>> embeddingIdToMatch = candidates.factToEmbeddingIdToMatch.get(action.getFact());
        if (tmpIdToEmbeddingId == null || embeddingIdToMatch == null) {
            log.warn("LLM hallucinated fact field, no matching context, fact={}, skip", action.getFact());
            return;
        }
        String tmpId = action.getId();
        if (StringUtils.isBlank(tmpId)) {
            log.warn("UPDATE/DELETE action missing id, action={}, skip", action);
            return;
        }
        String embeddingId = tmpIdToEmbeddingId.get(tmpId);
        if (embeddingId == null) {
            log.warn("LLM hallucinated id, tmpId={} not in mapping, fact={}, skip", tmpId, action.getFact());
            return;
        }

        EmbeddingMatch<TextSegment> match = embeddingIdToMatch.get(embeddingId);
        if (match == null || match.embedded() == null) {
            log.warn("Memory action embeddingId not found in match map, skip");
            return;
        }
        if (isSuperseded(match.embedded().metadata().toMap(), sourceMessageId)) {
            log.info("Skipping superseded semantic-memory action, embeddingId:{}, sourceMessageId:{}",
                    embeddingId, sourceMessageId);
            return;
        }

        if (ZhiMeshConstant.MemoryEvent.DELETE.equalsIgnoreCase(event)) {
            semanticEmbeddingStore.remove(embeddingId);
            return;
        }

        if (ZhiMeshConstant.MemoryEvent.UPDATE.equalsIgnoreCase(event)) {
            if (StringUtils.isBlank(action.getText())) {
                log.warn("UPDATE action missing text, action={}, skip", action);
                return;
            }
            Embedding replacementEmbedding = embeddingModel.embed(action.getText()).content();
            Metadata metadata = new Metadata(semanticMetadata(
                    match.embedded().metadata().toMap(), characterId, userId, sourceMessageId));
            TextSegment newSegment = TextSegment.from(action.getText(), metadata);
            // PgVector uses INSERT .. ON CONFLICT DO UPDATE and Neo4j uses MERGE;
            // both replace text, metadata and vector atomically for the same ID.
            semanticEmbeddingStore.addAll(
                    List.of(embeddingId), List.of(replacementEmbedding), List.of(newSegment));
            return;
        }

        log.warn("Unknown memory event: {}, action={}", event, action);
    }

    private static Map<String, Object> semanticMetadata(Map<String, Object> existing,
                                                        Long characterId,
                                                        Long userId,
                                                        Long sourceMessageId) {
        Map<String, Object> metadata = new HashMap<>(existing);
        metadata.put(CHARACTER_ID, characterId);
        metadata.put(MEMORY_TYPE, MemoryType.SEMANTIC.getDesc());
        metadata.put(MEMORY_UPDATED_AT, LocalDateTimeUtil.format(java.time.LocalDateTime.now()));
        if (userId != null) metadata.put(USER_ID, userId);
        if (sourceMessageId != null) metadata.put(LAST_SOURCE_MESSAGE_ID, sourceMessageId);
        return metadata;
    }

    private static boolean isSuperseded(Map<String, Object> metadata, Long sourceMessageId) {
        if (sourceMessageId == null || metadata == null) return false;
        Object raw = metadata.get(LAST_SOURCE_MESSAGE_ID);
        if (raw == null) return false;
        try {
            return Long.parseLong(String.valueOf(raw)) > sourceMessageId;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * Merge candidates collected per-fact. Exposes the per-fact prompt input
     * for the dispatcher to feed the LLM, and keeps the tmpId↔embeddingId
     * mappings + raw EmbeddingMatches needed for {@link #applyActions}.
     * <p>
     * 按 fact 聚合的合并候选。三个 map 分别给调度器构造 prompt、给 applyActions 反查使用。
     */
    public static final class MergeCandidates {
        public final Map<String, List<Map<String, String>>> factToOldMemoriesForPrompt;
        public final Map<String, Map<String, String>> factToTmpIdToEmbeddingId;
        public final Map<String, Map<String, EmbeddingMatch<TextSegment>>> factToEmbeddingIdToMatch;

        public MergeCandidates(Map<String, List<Map<String, String>>> factToOldMemoriesForPrompt,
                               Map<String, Map<String, String>> factToTmpIdToEmbeddingId,
                               Map<String, Map<String, EmbeddingMatch<TextSegment>>> factToEmbeddingIdToMatch) {
            this.factToOldMemoriesForPrompt = factToOldMemoriesForPrompt;
            this.factToTmpIdToEmbeddingId = factToTmpIdToEmbeddingId;
            this.factToEmbeddingIdToMatch = factToEmbeddingIdToMatch;
        }

        public boolean isEmpty() {
            return factToOldMemoriesForPrompt.isEmpty();
        }
    }
}
