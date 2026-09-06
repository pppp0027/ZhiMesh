package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.CREATE_TIME;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.IMPORTANCE;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.OCCURRED_AT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.TIME_PRECISION;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisodicRecencyRankerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);

    @Test
    void recentCuePromotesNewerCandidateWhenRelevanceIsComparable() {
        RetrievedCandidate older = candidate("三个月前使用旧网站", "2026-05-21 12:00:00", 0.80D);
        RetrievedCandidate recent = candidate("昨天使用新的牛客网链接", "2026-08-20 12:00:00", 0.72D);

        assertEquals(List.of(recent, older), EpisodicRecencyRanker.rank(
                "上次使用的是哪个网站？", List.of(older, recent), 0.25D, 30D, NOW));
    }

    @Test
    void recencyCannotOvertakeMuchStrongerSemanticEvidence() {
        RetrievedCandidate stronglyRelated = candidate(
                "较早但直接回答当前问题的事件", "2026-05-21 12:00:00", 0.90D);
        RetrievedCandidate marginallyRelated = candidate(
                "刚发生但仅勉强相关的事件", "2026-08-21 11:00:00", 0.31D);

        assertEquals(List.of(stronglyRelated, marginallyRelated), EpisodicRecencyRanker.rank(
                "上次发生了什么？", List.of(stronglyRelated, marginallyRelated), 0.25D, 30D, NOW));
    }

    @Test
    void nonTemporalQuestionKeepsSemanticOrder() {
        RetrievedCandidate older = candidate("旧事件", "2026-01-01 12:00:00", 0.80D);
        RetrievedCandidate recent = candidate("新事件", "2026-08-21 11:00:00", 0.75D);

        assertEquals(List.of(older, recent), EpisodicRecencyRanker.rank(
                "我曾经使用过哪些网站？", List.of(older, recent), 0.25D, 30D, NOW));
    }

    @Test
    void actualOccurrenceTimeWinsOverMisleadingWriteTime() {
        RetrievedCandidate toldTodayButOldEvent = candidate(
                "今天才提起的去年事件", "2026-08-21 11:50:00",
                "2025-08-21 12:00:00", "YEAR", 3, 0.76D);
        RetrievedCandidate actuallyRecent = candidate(
                "昨天真正发生的事件", "2026-08-21 11:00:00",
                "2026-08-20 12:00:00", "DAY", 3, 0.74D);

        assertEquals(List.of(actuallyRecent, toldTodayButOldEvent), EpisodicRecencyRanker.rank(
                "最近发生了什么？", List.of(toldTodayButOldEvent, actuallyRecent),
                0.20D, 30D, 0.05D, NOW));
    }

    @Test
    void unknownOccurrenceTimeIsNeutralInsteadOfPretendingToBeNewest() {
        RetrievedCandidate unknown = candidate(
                "没有时间的事件", "2026-08-21 11:59:00",
                null, "UNKNOWN", 3, 0.76D);
        RetrievedCandidate yesterday = candidate(
                "昨天发生的事件", "2026-08-21 11:00:00",
                "2026-08-20 12:00:00", "DAY", 3, 0.74D);

        assertEquals(List.of(yesterday, unknown), EpisodicRecencyRanker.rank(
                "最近发生了什么？", List.of(unknown, yesterday),
                0.20D, 30D, 0.05D, NOW));
    }

    private static RetrievedCandidate candidate(String text, String createTime, double rerankScore) {
        RetrievedCandidate candidate = RetrievedCandidate.from(Content.from(TextSegment.from(
                text, new Metadata(Map.of(CREATE_TIME, createTime,
                        RetrievedCandidate.VECTOR_SCORE, 0.80D)))), "vector", 1);
        candidate.setRerankScore(rerankScore);
        return candidate;
    }

    private static RetrievedCandidate candidate(String text, String createTime,
                                                 String occurredAt, String precision,
                                                 int importance, double rerankScore) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put(CREATE_TIME, createTime);
        metadata.put(TIME_PRECISION, precision);
        metadata.put(IMPORTANCE, importance);
        metadata.put(RetrievedCandidate.VECTOR_SCORE, 0.80D);
        if (occurredAt != null) metadata.put(OCCURRED_AT, occurredAt);
        RetrievedCandidate candidate = RetrievedCandidate.from(Content.from(TextSegment.from(
                text, new Metadata(metadata))), "vector", 1);
        candidate.setRerankScore(rerankScore);
        return candidate;
    }
}
