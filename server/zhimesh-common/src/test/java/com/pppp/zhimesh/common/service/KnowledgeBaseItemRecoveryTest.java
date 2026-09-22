package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.enums.EmbeddingStatusEnum;
import com.pppp.zhimesh.common.enums.FulltextStatusEnum;
import com.pppp.zhimesh.common.enums.GraphicalStatusEnum;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseItemRecoveryTest {

    @BeforeAll
    static void initializeLambdaColumnCache() {
        // set() resolves lambda columns eagerly, which needs the entity's TableInfo.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(KnowledgeBaseItem.class.getName());
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseItem.class);
    }

    @Test
    void failsGraphicalStatusesDoingBeyondTheConfiguredTimeout() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "adiProperties", new ZhiMeshProperties());
        when(mapper.update(isNull(), any())).thenReturn(2);

        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        int recovered = service.failTimedOutGraphIndexing(now);

        assertEquals(2, recovered);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        // Condition parameters register lazily, when the SQL segment renders.
        wrapper.getTargetSql();

        // Stale window and target status: anything still DOING whose last status
        // change precedes now-60min is flipped to FAIL with a fresh change time.
        LocalDateTime staleBefore = now.minusMinutes(60);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(GraphicalStatusEnum.DOING));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(staleBefore));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(GraphicalStatusEnum.FAIL));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now));
        assertTrue(wrapper.getSqlSet().toLowerCase().contains("graphical_status"));
    }

    @Test
    void timeoutFloorProtectsLiveIngestionFromZeroConfiguration() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIndexing().setGraphDoingTimeoutMinutes(0);
        ReflectionTestUtils.setField(service, "adiProperties", properties);
        when(mapper.update(isNull(), any())).thenReturn(0);

        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        service.failTimedOutGraphIndexing(now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        wrapper.getTargetSql();
        // A zero/negative configuration still fails only statuses older than one
        // minute, never the whole DOING set of a freshly started ingestion.
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now.minusMinutes(1)));
    }

    @Test
    void startupBoundaryFailsOnlyGraphDoingRowsChangedBeforeIt() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "adiProperties", new ZhiMeshProperties());
        when(mapper.update(isNull(), any())).thenReturn(1);

        LocalDateTime now = LocalDateTime.of(2026, 9, 21, 8, 0);
        LocalDateTime staleBefore = LocalDateTime.of(2026, 9, 21, 7, 59, 30);
        int recovered = service.failGraphIndexingStartedBefore(now, staleBefore);

        assertEquals(1, recovered);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        // Condition parameters register lazily, when the SQL segment renders.
        wrapper.getTargetSql();

        // Startup semantics: the boundary is the given moment (not now minus a
        // timeout), so rows still DOING whose last change precedes it — the
        // ones left behind by the previous process — flip to FAIL, while rows
        // whose change time is at or after the boundary (this process's own
        // live ingestions) are left untouched by the strict less-than.
        assertTrue(wrapper.getParamNameValuePairs().containsValue(GraphicalStatusEnum.DOING));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(staleBefore));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(GraphicalStatusEnum.FAIL));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now));
        assertTrue(wrapper.getSqlSet().toLowerCase().contains("graphical_status"));
        assertTrue(wrapper.getTargetSql().toLowerCase().contains("graphical_status_change_time"));
        // Pin the strict comparison: a row changed exactly at the boundary
        // belongs to this process and must survive (<= would wrongly fail it).
        assertTrue(wrapper.getTargetSql().contains("graphical_status_change_time <"));
        assertFalse(wrapper.getTargetSql().contains("<="));
    }

    @Test
    void failsEmbeddingStatusesDoingBeyondTheConfiguredTimeout() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "adiProperties", new ZhiMeshProperties());
        when(mapper.update(isNull(), any())).thenReturn(1);

        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        int recovered = service.failTimedOutEmbeddingIndexing(now);

        assertEquals(1, recovered);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        // Condition parameters register lazily, when the SQL segment renders.
        wrapper.getTargetSql();

        // Stale window and target status: only rows still DOING whose last
        // status change precedes now-30min are flipped to FAIL with a fresh
        // change time; fresh DOING rows and DONE/FAIL rows stay untouched.
        LocalDateTime staleBefore = now.minusMinutes(30);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(EmbeddingStatusEnum.DOING));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(staleBefore));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(EmbeddingStatusEnum.FAIL));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now));
        assertTrue(wrapper.getSqlSet().toLowerCase().contains("embedding_status"));
    }

    @Test
    void embeddingTimeoutFloorProtectsLiveIngestionFromZeroConfiguration() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIndexing().setEmbeddingDoingTimeoutMinutes(0);
        ReflectionTestUtils.setField(service, "adiProperties", properties);
        when(mapper.update(isNull(), any())).thenReturn(0);

        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        service.failTimedOutEmbeddingIndexing(now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        wrapper.getTargetSql();
        // A zero/negative configuration still fails only statuses older than one
        // minute, never the whole DOING set of a freshly started ingestion.
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now.minusMinutes(1)));
    }

    @Test
    void failsFulltextStatusesDoingBeyondTheConfiguredTimeout() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "adiProperties", new ZhiMeshProperties());
        when(mapper.update(isNull(), any())).thenReturn(3);

        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        int recovered = service.failTimedOutFulltextIndexing(now);

        assertEquals(3, recovered);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        // Condition parameters register lazily, when the SQL segment renders.
        wrapper.getTargetSql();

        // Stale window and target status: only rows still DOING whose last
        // status change precedes now-30min are flipped to FAIL with a fresh
        // change time; fresh DOING rows and DONE/FAIL rows stay untouched.
        LocalDateTime staleBefore = now.minusMinutes(30);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(FulltextStatusEnum.DOING));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(staleBefore));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(FulltextStatusEnum.FAIL));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now));
        assertTrue(wrapper.getSqlSet().toLowerCase().contains("fulltext_status"));
    }

    @Test
    void fulltextTimeoutFloorProtectsLiveIngestionFromZeroConfiguration() {
        KnowledgeBaseItemService service = new KnowledgeBaseItemService();
        KnowledgeBaseItemMapper mapper = mock(KnowledgeBaseItemMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIndexing().setFulltextDoingTimeoutMinutes(0);
        ReflectionTestUtils.setField(service, "adiProperties", properties);
        when(mapper.update(isNull(), any())).thenReturn(0);

        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        service.failTimedOutFulltextIndexing(now);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeBaseItem>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<KnowledgeBaseItem> wrapper = wrapperCaptor.getValue();
        wrapper.getTargetSql();
        // A zero/negative configuration still fails only statuses older than one
        // minute, never the whole DOING set of a freshly started ingestion.
        assertTrue(wrapper.getParamNameValuePairs().containsValue(now.minusMinutes(1)));
    }
}
