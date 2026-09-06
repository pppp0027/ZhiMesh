package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.enums.EventType;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.memory.vo.ExtractedEpisodicEvent;
import com.pppp.zhimesh.common.util.LocalDateTimeUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.*;

/**
 * Append-only writer for episodic memory. Each event becomes a row in the dedicated
 * {@code episodicEmbeddingStore} (physically isolated from semantic memory so the
 * two never compete for top-K). All structured fields (event_type, importance,
 * create_time, conv_msg_id) live in the vector store's metadata — no relational table.
 * <p>
 * 情景记忆 append-only 写入服务。事件写入独立的 {@code episodicEmbeddingStore}
 * （与语义记忆物理隔离，互不抢占 top-K 召回）。所有结构化字段（事件类型、重要性、创建时间、
 * 来源消息）都存在向量库 metadata 中，不再需要关系表。
 */
@Slf4j
@Service
public class EpisodicMemoryService {

    private static final java.util.Set<String> TIME_PRECISIONS = java.util.Set.of(
            "DATETIME", "DAY", "MONTH", "YEAR", "APPROXIMATE", "UNKNOWN");

    @Resource
    @Qualifier("episodicEmbeddingStore")
    private EmbeddingStore<TextSegment> episodicEmbeddingStore;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private ZhiMeshProperties properties;

    /**
     * Batch add episodic events to the dedicated episodic vector store. Events
     * are checked against both persisted memories and earlier events in this
     * batch, so retries and near-identical extraction results do not pollute
     * later recall.
     *
     * @param characterId    character id
     * @param userId         user id, stored in metadata for ownership filtering and traceability
     * @param characterMsgId source character message id, stored in metadata and used for idempotency
     * @param events         events to add
     * @param isFreeToken    whether the model is free-tier (passed through for cost accounting)
     */
    public void batchAdd(Long characterId, Long userId, Long characterMsgId,
                         List<ExtractedEpisodicEvent> events, boolean isFreeToken) {
        batchAdd(characterId, userId, characterMsgId, LocalDateTime.now(), null,
                events, isFreeToken);
    }

    public void batchAdd(Long characterId, Long userId, Long characterMsgId,
                         LocalDateTime recordedAt, String sourceUserMessage,
                         List<ExtractedEpisodicEvent> events, boolean isFreeToken) {
        if (CollectionUtils.isEmpty(events)) {
            return;
        }
        // isFreeToken is reserved for future cost accounting; it remains in the
        // signature so callers do not need to change when that feature arrives.

        // Format once per batch with project-wide pattern (yyyy-MM-dd HH:mm:ss).
        // Sub-second precision adds no recall value for "past events"; the canonical
        // format is also the lingua franca used elsewhere in the codebase / UI.
        // 每批格式化一次，使用项目统一的 yyyy-MM-dd HH:mm:ss——亚秒精度对"过往事件"
        // 召回无价值，此格式与代码库/前端其他时间字段一致。
        LocalDateTime effectiveRecordedAt = recordedAt != null ? recordedAt : LocalDateTime.now();
        String createTimeStr = LocalDateTimeUtil.format(
                effectiveRecordedAt, LocalDateTimeUtil.PATTERN_DEFAULT);
        List<String> embeddingIds = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();
        List<AcceptedEvent> acceptedEvents = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();

        for (ExtractedEpisodicEvent event : events) {
            if (event == null || StringUtils.isBlank(event.getSummary())) {
                continue;
            }

            // PgVector requires a canonical UUID string (parsed via UUID.fromString);
            // the project-wide UuidUtil.createShort() strips dashes and would fail here.
            String embeddingId = UUID.randomUUID().toString();
            Embedding embedding = embeddingModel.embed(event.getSummary()).content();
            NormalizedEventTime eventTime = normalizeEventTime(
                    event, sourceUserMessage, effectiveRecordedAt);
            double dedupThreshold = dedupThreshold();
            if (isDuplicate(characterId, characterMsgId, embedding, eventTime)
                    || acceptedEvents.stream().anyMatch(existing ->
                    cosine(existing.embedding(), embedding) >= dedupThreshold
                            && sameOccurrence(existing.eventTime(), eventTime))) {
                log.info("Skipping duplicate episodic memory, characterId:{}, sourceMessageId:{}",
                        characterId, characterMsgId);
                continue;
            }

            String eventType = EventType.fromString(event.getEventType()).getCode();
            int importance = event.getImportance() != null
                    ? Math.max(1, Math.min(5, event.getImportance()))
                    : ZhiMeshConstant.EPISODIC_IMPORTANCE_DEFAULT;

            // Build metadata with a mutable HashMap so characterMsgId can be conditionally
            // added (Map.of rejects null values). CHARACTER_ID stays as Long to match
            // the type used by IsEqualTo filters.
            Map<String, Object> meta = new HashMap<>();
            meta.put(CHARACTER_ID, characterId);
            meta.put(MEMORY_TYPE, MemoryType.EPISODIC.getDesc());
            meta.put(CREATE_TIME, createTimeStr);
            meta.put(EVENT_TYPE, eventType);
            meta.put(IMPORTANCE, importance);
            meta.put(TIME_PRECISION, eventTime.precision());
            meta.put(TIME_SOURCE, eventTime.occurredAt() == null ? "unknown" : "user_expression");
            if (eventTime.occurredAt() != null) {
                meta.put(OCCURRED_AT, LocalDateTimeUtil.format(eventTime.occurredAt()));
                meta.put(RAW_TIME_EXPRESSION, eventTime.rawExpression());
            }
            if (userId != null) {
                meta.put(USER_ID, userId);
            }
            if (characterMsgId != null) {
                meta.put(CHARACTER_MSG_ID, characterMsgId);
            }
            Metadata metadata = new Metadata(meta);
            TextSegment segment = TextSegment.from(event.getSummary(), metadata);

            embeddingIds.add(embeddingId);
            embeddings.add(embedding);
            acceptedEvents.add(new AcceptedEvent(embedding, eventTime));
            segments.add(segment);
        }

        if (segments.isEmpty()) {
            log.warn("No valid episodic events to persist");
            return;
        }

        episodicEmbeddingStore.addAll(embeddingIds, embeddings, segments);
        log.info("Episodic memory batch added: characterId={}, count={}", characterId, segments.size());
    }

    private NormalizedEventTime normalizeEventTime(ExtractedEpisodicEvent event,
                                                    String sourceUserMessage,
                                                    LocalDateTime recordedAt) {
        String rawExpression = StringUtils.trimToNull(event.getRawTimeExpression());
        String occurredAt = StringUtils.trimToNull(event.getOccurredAt());
        String precision = StringUtils.upperCase(StringUtils.trimToEmpty(event.getTimePrecision()));
        if (!TIME_PRECISIONS.contains(precision)) precision = "UNKNOWN";
        // The model must quote a temporal expression that actually occurred in
        // the user input. Otherwise the normalized timestamp is not trusted.
        if (rawExpression == null || StringUtils.isBlank(sourceUserMessage)
                || !StringUtils.containsIgnoreCase(sourceUserMessage, rawExpression)
                || occurredAt == null) {
            return new NormalizedEventTime(null, "UNKNOWN", null);
        }
        try {
            LocalDateTime parsed;
            try {
                parsed = LocalDateTimeUtil.parse(occurredAt);
            } catch (DateTimeParseException ignored) {
                parsed = LocalDateTime.parse(occurredAt);
            }
            int maxYear = Math.max(2200, recordedAt.getYear() + 100);
            if (parsed.getYear() < 1900 || parsed.getYear() > maxYear) {
                log.warn("Rejecting out-of-range episodic occurredAt: {}", occurredAt);
                return new NormalizedEventTime(null, "UNKNOWN", null);
            }
            String safeRaw = StringUtils.substring(rawExpression, 0, 200);
            return new NormalizedEventTime(parsed,
                    "UNKNOWN".equals(precision) ? "APPROXIMATE" : precision, safeRaw);
        } catch (DateTimeParseException exception) {
            log.warn("Rejecting invalid episodic occurredAt: {}", occurredAt);
            return new NormalizedEventTime(null, "UNKNOWN", null);
        }
    }

    private boolean isDuplicate(Long characterId, Long characterMsgId, Embedding embedding,
                                NormalizedEventTime eventTime) {
        Filter characterScope = new IsEqualTo(CHARACTER_ID, characterId);
        if (characterMsgId != null) {
            Filter sameSource = new And(characterScope, new IsEqualTo(CHARACTER_MSG_ID, characterMsgId));
            // A source message is persisted as one atomic addAll batch. Any row
            // from that source therefore proves that this is a replay, even if a
            // non-deterministic extraction model paraphrased the summary.
            if (hasMatch(embedding, sameSource, 0D)) return true;
        }
        EmbeddingSearchResult<TextSegment> matches = episodicEmbeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(embedding)
                        .maxResults(5)
                        .minScore(dedupThreshold())
                        .filter(characterScope)
                        .build());
        if (matches.matches().isEmpty()) return false;
        return matches.matches().stream()
                .anyMatch(match -> sameOccurrence(eventTime, eventTime(match)));
    }

    private boolean hasMatch(Embedding embedding, Filter filter, double minScore) {
        return !episodicEmbeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(1)
                .minScore(minScore)
                .filter(filter)
                .build()).matches().isEmpty();
    }

    private double dedupThreshold() {
        return Math.max(0D, Math.min(1D, properties.getMemory().getEpisodicDedupMinScore()));
    }

    private static NormalizedEventTime eventTime(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null) {
            return new NormalizedEventTime(null, "UNKNOWN", null);
        }
        Map<String, Object> metadata = match.embedded().metadata().toMap();
        String rawTime = StringUtils.trimToNull(String.valueOf(metadata.get(OCCURRED_AT)));
        if (rawTime == null || "null".equalsIgnoreCase(rawTime)) {
            return new NormalizedEventTime(null, "UNKNOWN", null);
        }
        try {
            return new NormalizedEventTime(LocalDateTimeUtil.parse(rawTime),
                    StringUtils.upperCase(String.valueOf(metadata.getOrDefault(TIME_PRECISION, "UNKNOWN"))),
                    null);
        } catch (DateTimeParseException exception) {
            return new NormalizedEventTime(null, "UNKNOWN", null);
        }
    }

    private static boolean sameOccurrence(NormalizedEventTime left, NormalizedEventTime right) {
        if (left == null || right == null
                || left.occurredAt() == null || right.occurredAt() == null) {
            // Unknown-time near duplicates remain suppressed to protect recall quality.
            return true;
        }
        String precision = coarserPrecision(left.precision(), right.precision());
        return switch (precision) {
            case "YEAR" -> left.occurredAt().getYear() == right.occurredAt().getYear();
            case "MONTH" -> YearMonth.from(left.occurredAt()).equals(YearMonth.from(right.occurredAt()));
            case "DAY", "APPROXIMATE", "UNKNOWN" -> left.occurredAt().toLocalDate()
                    .equals(right.occurredAt().toLocalDate());
            default -> Math.abs(Duration.between(left.occurredAt(), right.occurredAt()).toMinutes()) <= 60L;
        };
    }

    private static String coarserPrecision(String left, String right) {
        List<String> order = List.of("DATETIME", "DAY", "APPROXIMATE", "MONTH", "YEAR", "UNKNOWN");
        int leftIndex = Math.max(0, order.indexOf(StringUtils.defaultString(left, "UNKNOWN")));
        int rightIndex = Math.max(0, order.indexOf(StringUtils.defaultString(right, "UNKNOWN")));
        return order.get(Math.max(leftIndex, rightIndex));
    }

    private static double cosine(Embedding left, Embedding right) {
        float[] a = left.vector();
        float[] b = right.vector();
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1D;
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int index = 0; index < a.length; index++) {
            dot += a[index] * b[index];
            normA += a[index] * a[index];
            normB += b[index] * b[index];
        }
        return normA == 0D || normB == 0D ? -1D : dot / Math.sqrt(normA * normB);
    }

    private record NormalizedEventTime(LocalDateTime occurredAt, String precision,
                                       String rawExpression) {
    }

    private record AcceptedEvent(Embedding embedding, NormalizedEventTime eventTime) {
    }
}

