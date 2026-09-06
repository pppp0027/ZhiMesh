package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkSetMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CanonicalChunkIndexServiceTest {

    private final KnowledgeBaseChunkSetMapper chunkSetMapper = mock(KnowledgeBaseChunkSetMapper.class);
    private final KnowledgeBaseChunkMapper chunkMapper = mock(KnowledgeBaseChunkMapper.class);
    private final KnowledgeBaseItemMapper itemMapper = mock(KnowledgeBaseItemMapper.class);
    private CanonicalChunkIndexService service;

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        initializeTableInfo(KnowledgeBaseChunkSet.class);
        initializeTableInfo(KnowledgeBaseChunk.class);
        initializeTableInfo(KnowledgeBaseItem.class);
    }

    @BeforeEach
    void setUp() {
        reset(chunkSetMapper, chunkMapper, itemMapper);
        service = new CanonicalChunkIndexService(chunkSetMapper, chunkMapper, itemMapper);
        when(chunkSetMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(itemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    @Test
    void buildsActivatesAndAddsStableLineageToEverySegment() {
        KnowledgeBase knowledgeBase = knowledgeBase();
        KnowledgeBaseItem item = item("第一段介绍数据库连接。\n第二段包含错误码 ERR_DB_001。");
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(chunkSetMapper.selectOne(any())).thenReturn(null);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            KnowledgeBaseChunkSet set = invocation.getArgument(0);
            set.setId(31L);
            return 1;
        }).when(chunkSetMapper).insert(any(KnowledgeBaseChunkSet.class));
        when(chunkMapper.insert(any(KnowledgeBaseChunk.class))).thenReturn(1);

        CanonicalChunkSnapshot snapshot = service.index(knowledgeBase, item);

        assertThat(snapshot.chunkSet().getStatus()).isEqualTo("ACTIVE");
        assertThat(snapshot.chunkSet().getIsActive()).isTrue();
        assertThat(snapshot.chunks()).isNotEmpty();
        assertThat(snapshot.segments()).hasSameSizeAs(snapshot.chunks());
        assertThat(item.getActiveChunkSetUuid()).isEqualTo(snapshot.chunkSet().getUuid());
        for (int index = 0; index < snapshot.chunks().size(); index++) {
            KnowledgeBaseChunk chunk = snapshot.chunks().get(index);
            TextSegment segment = snapshot.segments().get(index);
            assertThat(segment.text()).isEqualTo(chunk.getContent());
            assertThat(segment.metadata().getString(ZhiMeshConstant.MetadataKey.KB_UUID))
                    .isEqualTo(knowledgeBase.getUuid());
            assertThat(segment.metadata().getString(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID))
                    .isEqualTo(item.getUuid());
            assertThat(segment.metadata().getString(ZhiMeshConstant.MetadataKey.CHUNK_SET_UUID))
                    .isEqualTo(snapshot.chunkSet().getUuid());
            assertThat(segment.metadata().getString(ZhiMeshConstant.MetadataKey.CHUNK_UUID))
                    .isEqualTo(chunk.getUuid());
            assertThat(chunk.getContentHash()).hasSize(64);
            assertThat(chunk.getTokenCount()).isNotNegative();
        }
        verify(chunkSetMapper).insert(any(KnowledgeBaseChunkSet.class));
        verify(chunkMapper, times(snapshot.chunks().size())).insert(any(KnowledgeBaseChunk.class));
    }

    @Test
    void reusesEquivalentCompleteChunkSetWithoutSplittingOrInsertingAgain() {
        KnowledgeBase knowledgeBase = knowledgeBase();
        KnowledgeBaseItem item = item("already indexed content");
        KnowledgeBaseChunkSet existing = chunkSet(knowledgeBase, item, "existingchunkset0000000000000001");
        KnowledgeBaseChunk chunk = chunk(knowledgeBase, item, existing);
        existing.setChunkCount(1);
        existing.setTotalTokens(3);
        existing.setStatus("ACTIVE");
        existing.setIsActive(true);
        when(itemMapper.selectOne(any())).thenReturn(item);
        when(chunkSetMapper.selectOne(any())).thenReturn(existing);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

        CanonicalChunkSnapshot snapshot = service.index(knowledgeBase, item);

        assertThat(snapshot.chunkSet()).isSameAs(existing);
        assertThat(snapshot.chunks()).containsExactly(chunk);
        assertThat(snapshot.segments().get(0).metadata()
                .getString(ZhiMeshConstant.MetadataKey.CHUNK_UUID)).isEqualTo(chunk.getUuid());
        verify(chunkSetMapper, never()).insert(any(KnowledgeBaseChunkSet.class));
        verify(chunkMapper, never()).insert(any(KnowledgeBaseChunk.class));
        verify(chunkMapper, never()).delete(any());
    }

    @Test
    void stableConfigHashChangesWhenAnyEffectiveSplitParameterChanges() {
        CanonicalChunkIndexService.SplitConfig baseline = new CanonicalChunkIndexService.SplitConfig(
                ZhiMeshConstant.SplitStrategy.RECURSIVE, 400, 60, "", "openai");
        CanonicalChunkIndexService.SplitConfig changed = new CanonicalChunkIndexService.SplitConfig(
                ZhiMeshConstant.SplitStrategy.RECURSIVE, 401, 60, "", "openai");

        assertThat(CanonicalChunkIndexService.splitConfigHash(baseline))
                .hasSize(64)
                .isEqualTo(CanonicalChunkIndexService.splitConfigHash(baseline))
                .isNotEqualTo(CanonicalChunkIndexService.splitConfigHash(changed));
        assertThat(CanonicalChunkIndexService.sha256("中文 content"))
                .isEqualTo(CanonicalChunkIndexService.sha256("中文 content"))
                .isNotEqualTo(CanonicalChunkIndexService.sha256("中文 content "));
    }

    @Test
    void deletesChunksBeforeSetsAndClearsAllDerivedPointers() {
        when(itemMapper.selectOne(any())).thenReturn(item("content"));

        service.deleteByItemUuid("item0000000000000000000000000001");

        var order = inOrder(itemMapper, chunkMapper, chunkSetMapper);
        order.verify(itemMapper).selectOne(any());
        order.verify(chunkMapper).delete(any());
        order.verify(chunkSetMapper).delete(any());
        order.verify(itemMapper).update(isNull(), any(Wrapper.class));
    }

    private static KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(7L);
        knowledgeBase.setUuid("kb000000000000000000000000000001");
        knowledgeBase.setIngestSplitStrategy(ZhiMeshConstant.SplitStrategy.LINE);
        knowledgeBase.setIngestMaxSegmentSize(200);
        knowledgeBase.setIngestMaxOverlap(0);
        knowledgeBase.setIngestCustomSeparator("");
        knowledgeBase.setIngestTokenEstimator(ZhiMeshConstant.TokenEstimator.OPENAI);
        return knowledgeBase;
    }

    private static void initializeTableInfo(Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }

    private static KnowledgeBaseItem item(String content) {
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setId(11L);
        item.setUuid("item0000000000000000000000000001");
        item.setKbId(7L);
        item.setKbUuid("kb000000000000000000000000000001");
        item.setRemark(content);
        return item;
    }

    private static KnowledgeBaseChunkSet chunkSet(KnowledgeBase kb, KnowledgeBaseItem item, String uuid) {
        KnowledgeBaseChunkSet set = new KnowledgeBaseChunkSet();
        set.setId(31L);
        set.setUuid(uuid);
        set.setKbId(kb.getId());
        set.setKbUuid(kb.getUuid());
        set.setKbItemId(item.getId());
        set.setKbItemUuid(item.getUuid());
        return set;
    }

    private static KnowledgeBaseChunk chunk(KnowledgeBase kb, KnowledgeBaseItem item,
                                             KnowledgeBaseChunkSet set) {
        KnowledgeBaseChunk chunk = new KnowledgeBaseChunk();
        chunk.setId(41L);
        chunk.setUuid("chunk000000000000000000000000001");
        chunk.setChunkSetId(set.getId());
        chunk.setChunkSetUuid(set.getUuid());
        chunk.setKbId(kb.getId());
        chunk.setKbUuid(kb.getUuid());
        chunk.setKbItemId(item.getId());
        chunk.setKbItemUuid(item.getUuid());
        chunk.setChunkIndex(0);
        chunk.setContent(item.getRemark());
        chunk.setContentHash(CanonicalChunkIndexService.sha256(item.getRemark()));
        chunk.setTokenCount(3);
        return chunk;
    }
}
