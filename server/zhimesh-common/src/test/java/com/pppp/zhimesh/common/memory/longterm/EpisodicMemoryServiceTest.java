package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.memory.vo.ExtractedEpisodicEvent;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.CREATE_TIME;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.OCCURRED_AT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.RAW_TIME_EXPRESSION;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.TIME_PRECISION;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodicMemoryServiceTest {

    @Mock
    private EmbeddingStore<TextSegment> store;
    @Mock
    private EmbeddingModel embeddingModel;
    @Captor
    private ArgumentCaptor<List<TextSegment>> segmentsCaptor;

    private EpisodicMemoryService service;

    @BeforeEach
    void setUp() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getMemory().setEpisodicDedupMinScore(0.92D);
        service = new EpisodicMemoryService();
        ReflectionTestUtils.setField(service, "episodicEmbeddingStore", store);
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "properties", properties);
    }

    @Test
    void persistedNearDuplicatePreventsAnotherWrite() {
        Embedding embedding = Embedding.from(new float[]{1F, 0F});
        when(embeddingModel.embed("抓取牛客网页面内容")).thenReturn(Response.from(embedding));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                new EmbeddingMatch<>(0.999D, "existing", embedding,
                        TextSegment.from("抓取牛客网页面内容")))));

        service.batchAdd(10L, 7L, 99L,
                List.of(event("抓取牛客网页面内容")), false);

        verify(store, never()).addAll(anyList(), anyList(), anyList());
    }

    @Test
    void nearDuplicatesInSameBatchProduceOneWriteAndKeepUserOwnership() {
        when(embeddingModel.embed("请求抓取牛客网页面内容"))
                .thenReturn(Response.from(Embedding.from(new float[]{1F, 0F})));
        when(embeddingModel.embed("用户请求抓取牛客网页面内容"))
                .thenReturn(Response.from(Embedding.from(new float[]{0.999F, 0.01F})));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));

        service.batchAdd(10L, 7L, 99L, List.of(
                event("请求抓取牛客网页面内容"),
                event("用户请求抓取牛客网页面内容")), false);

        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        List<TextSegment> persisted = segmentsCaptor.getValue();
        assertEquals(1, persisted.size());
        assertEquals(7L, persisted.get(0).metadata().toMap().get(USER_ID));
    }

    @Test
    void distinctEventsAreBothPersisted() {
        when(embeddingModel.embed("用户计划学习向量数据库"))
                .thenReturn(Response.from(Embedding.from(new float[]{1F, 0F})));
        when(embeddingModel.embed("用户周末准备去爬山"))
                .thenReturn(Response.from(Embedding.from(new float[]{0F, 1F})));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));

        service.batchAdd(10L, 7L, 99L, List.of(
                event("用户计划学习向量数据库"),
                event("用户周末准备去爬山")), false);

        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        assertEquals(2, segmentsCaptor.getValue().size());
    }

    @Test
    void persistsTrustedOccurrenceTimeSeparatelyFromRecordedTime() {
        when(embeddingModel.embed("昨天参加了项目评审"))
                .thenReturn(Response.from(Embedding.from(new float[]{1F, 0F})));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        ExtractedEpisodicEvent event = event("昨天参加了项目评审");
        event.setOccurredAt("2026-08-20 15:00:00");
        event.setTimePrecision("DAY");
        event.setRawTimeExpression("昨天");

        service.batchAdd(10L, 7L, 99L,
                LocalDateTime.of(2026, 8, 21, 12, 0), "我昨天参加了项目评审",
                List.of(event), false);

        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        Map<String, Object> metadata = segmentsCaptor.getValue().get(0).metadata().toMap();
        assertEquals("2026-08-20 15:00:00", metadata.get(OCCURRED_AT));
        assertEquals("2026-08-21 12:00:00", metadata.get(CREATE_TIME));
        assertEquals("DAY", metadata.get(TIME_PRECISION));
        assertEquals("昨天", metadata.get(RAW_TIME_EXPRESSION));
    }

    @Test
    void rejectsModelInventedTemporalExpression() {
        when(embeddingModel.embed("参加了项目评审"))
                .thenReturn(Response.from(Embedding.from(new float[]{1F, 0F})));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        ExtractedEpisodicEvent event = event("参加了项目评审");
        event.setOccurredAt("2026-08-20 15:00:00");
        event.setTimePrecision("DAY");
        event.setRawTimeExpression("昨天");

        service.batchAdd(10L, 7L, 99L,
                LocalDateTime.of(2026, 8, 21, 12, 0), "我参加了项目评审",
                List.of(event), false);

        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        Map<String, Object> metadata = segmentsCaptor.getValue().get(0).metadata().toMap();
        assertEquals(null, metadata.get(OCCURRED_AT));
        assertEquals("UNKNOWN", metadata.get(TIME_PRECISION));
    }

    @Test
    void recurringSimilarEventsOnDifferentDaysAreBothPersisted() {
        when(embeddingModel.embed("完成了五公里跑步"))
                .thenReturn(Response.from(Embedding.from(new float[]{1F, 0F})))
                .thenReturn(Response.from(Embedding.from(new float[]{1F, 0F})));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        ExtractedEpisodicEvent yesterday = event("完成了五公里跑步");
        yesterday.setOccurredAt("2026-08-20 00:00:00");
        yesterday.setTimePrecision("DAY");
        yesterday.setRawTimeExpression("昨天");
        ExtractedEpisodicEvent today = event("完成了五公里跑步");
        today.setOccurredAt("2026-08-21 00:00:00");
        today.setTimePrecision("DAY");
        today.setRawTimeExpression("今天");

        service.batchAdd(10L, 7L, 99L,
                LocalDateTime.of(2026, 8, 21, 12, 0), "昨天和今天都完成了五公里跑步",
                List.of(yesterday, today), false);

        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        assertEquals(2, segmentsCaptor.getValue().size());
    }

    private static ExtractedEpisodicEvent event(String summary) {
        ExtractedEpisodicEvent event = new ExtractedEpisodicEvent();
        event.setSummary(summary);
        event.setEventType("general");
        event.setImportance(3);
        return event;
    }
}
