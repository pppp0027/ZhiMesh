package com.pppp.zhimesh.common.rag.profile;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.KnowledgeBaseRouteProfile;
import com.pppp.zhimesh.common.entity.KnowledgeBaseRouteProfileSet;
import com.pppp.zhimesh.common.enums.EmbeddingStatusEnum;
import com.pppp.zhimesh.common.mapper.AiModelMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseRouteProfileMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseRouteProfileSetMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRouteProfileBuildServiceTest {

    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeBaseItemMapper itemMapper = mock(KnowledgeBaseItemMapper.class);
    private final KnowledgeBaseRouteProfileSetMapper setMapper = mock(KnowledgeBaseRouteProfileSetMapper.class);
    private final KnowledgeBaseRouteProfileMapper profileMapper = mock(KnowledgeBaseRouteProfileMapper.class);
    private final AiModelMapper aiModelMapper = mock(AiModelMapper.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final KnowledgeRouteProfileCandidateCollector collector = mock(KnowledgeRouteProfileCandidateCollector.class);
    private final KnowledgeRouteProfileManifestBuilder manifestBuilder = mock(KnowledgeRouteProfileManifestBuilder.class);
    private final KnowledgeRouteProfileSelector selector = mock(KnowledgeRouteProfileSelector.class);
    private final KnowledgeRouteProfileCache cache = mock(KnowledgeRouteProfileCache.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final ZhiMeshProperties properties = new ZhiMeshProperties();
    private KnowledgeRouteProfileBuildService service;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        initializeTableInfo(KnowledgeBase.class);
        initializeTableInfo(KnowledgeBaseRouteProfileSet.class);
    }

    @BeforeEach
    void setUp() {
        properties.setEmbeddingModel("local:bge-small-zh-v1.5");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(aiModelMapper.selectList(any())).thenReturn(List.of());
        service = new KnowledgeRouteProfileBuildService(
                knowledgeBaseMapper, itemMapper, setMapper, profileMapper, aiModelMapper,
                embeddingModel, properties, collector, manifestBuilder, selector, cache,
                new KnowledgeRouteProfileCodec(), transactionManager);
    }

    @Test
    void redisPublishFailureNeverSwitchesTheDatabaseActiveGeneration() {
        arrangeReadyRelease(2L);
        when(cache.put(any())).thenReturn(false);

        KnowledgeRouteProfileBuildService.Outcome outcome = service.buildOrWarm("kb-a");

        assertThat(outcome).isEqualTo(KnowledgeRouteProfileBuildService.Outcome.RETRY);
        verify(knowledgeBaseMapper, never()).activateRouteProfile(
                anyString(), any(Long.class), anyString(), anyString(), any(Long.class), anyString());
    }

    @Test
    void aCompletedOldGenerationCannotActivateAfterANewerUpdate() {
        KnowledgeBase initial = arrangeReadyRelease(2L);
        KnowledgeBase newer = knowledgeBase(3L);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(initial, newer);
        when(cache.put(any())).thenReturn(true);

        KnowledgeRouteProfileBuildService.Outcome outcome = service.buildOrWarm("kb-a");

        assertThat(outcome).isEqualTo(KnowledgeRouteProfileBuildService.Outcome.RETRY);
        verify(knowledgeBaseMapper, never()).activateRouteProfile(
                anyString(), any(Long.class), anyString(), anyString(), any(Long.class), anyString());
    }

    @Test
    void aFailedActivePointerUpdateRollsBackTheWholeReleaseSwitch() {
        KnowledgeBase kb = arrangeReadyRelease(2L);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb, kb);
        when(cache.put(any())).thenReturn(true);
        when(knowledgeBaseMapper.activateRouteProfile(
                anyString(), any(Long.class), anyString(), anyString(), any(Long.class), anyString()))
                .thenReturn(0);

        KnowledgeRouteProfileBuildService.Outcome outcome = service.buildOrWarm("kb-a");

        assertThat(outcome).isEqualTo(KnowledgeRouteProfileBuildService.Outcome.RETRY);
        verify(transactionStatus).setRollbackOnly();
    }

    @Test
    void cacheWarmReusesTheActiveReleaseWithoutInspectingMutableSourceContent() {
        KnowledgeBase kb = knowledgeBase(2L);
        kb.setRouteProfileStatus("READY");
        kb.setRouteProfileActiveGeneration(2L);
        kb.setRouteProfileSetUuid("set-a");
        KnowledgeBaseRouteProfileSet set = new KnowledgeBaseRouteProfileSet();
        set.setUuid("set-a");
        set.setKbUuid("kb-a");
        set.setGeneration(2L);
        set.setStatus("ACTIVE");
        set.setEmbeddingModelId(0L);
        set.setEmbeddingModelIdentity(properties.getEmbeddingModel());
        set.setEmbeddingDimension(2);
        set.setGeneratorVersion("v1");
        set.setProfileLimit(8);
        set.setProfileCount(1);
        KnowledgeBaseRouteProfile profile = new KnowledgeBaseRouteProfile();
        profile.setProfileType("TOPIC");
        profile.setProfileKey("topic-a");
        profile.setProfileText("报销流程");
        profile.setProfileEmbedding(new float[]{1F, 0F});
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(setMapper.selectOne(any())).thenReturn(set);
        when(profileMapper.selectList(any())).thenReturn(List.of(profile));
        when(cache.put(any())).thenReturn(true);

        assertThat(service.warmActive("kb-a")).isTrue();

        verifyNoInteractions(manifestBuilder, collector, selector, embeddingModel);
    }

    @Test
    void cacheWarmRetiresAReleaseThatDoesNotMatchTheCurrentProfileBudget() {
        KnowledgeBase kb = knowledgeBase(2L);
        kb.setRouteProfileStatus("READY");
        kb.setRouteProfileActiveGeneration(2L);
        kb.setRouteProfileSetUuid("set-a");
        KnowledgeBaseRouteProfileSet set = new KnowledgeBaseRouteProfileSet();
        set.setUuid("set-a");
        set.setKbUuid("kb-a");
        set.setGeneration(2L);
        set.setStatus("ACTIVE");
        set.setProfileLimit(16);
        set.setProfileCount(16);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(setMapper.selectOne(any())).thenReturn(set);

        assertThat(service.warmActive("kb-a")).isFalse();

        verify(knowledgeBaseMapper).markRouteProfileStale("kb-a");
        verifyNoInteractions(manifestBuilder, collector, selector, embeddingModel, cache);
    }

    @Test
    void graphOrBm25TriggeredProfileBuildDoesNotDependOnEmbeddingStatus() {
        KnowledgeBase kb = arrangeReadyRelease(2L);
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setUuid("item-a");
        item.setKbUuid("kb-a");
        item.setEmbeddingStatus(EmbeddingStatusEnum.FAIL);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(cache.put(any())).thenReturn(false);

        KnowledgeRouteProfileBuildService.Outcome outcome = service.buildOrWarm("kb-a");

        assertThat(outcome).isEqualTo(KnowledgeRouteProfileBuildService.Outcome.RETRY);
        verify(manifestBuilder).build(eq(kb), any());
    }

    private KnowledgeBase arrangeReadyRelease(long generation) {
        KnowledgeBase kb = knowledgeBase(generation);
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setUuid("item-a");
        item.setKbUuid("kb-a");
        item.setEmbeddingStatus(EmbeddingStatusEnum.DONE);
        item.setActiveChunkSetUuid("chunk-set-a");
        item.setEmbeddingChunkSetUuid("chunk-set-a");
        KnowledgeBaseRouteProfileSet set = new KnowledgeBaseRouteProfileSet();
        set.setId(20L);
        set.setUuid("set-a");
        set.setKbUuid("kb-a");
        set.setGeneration(generation);
        set.setSourceManifestHash("manifest-a");
        set.setEmbeddingModelId(0L);
        set.setEmbeddingModelIdentity(properties.getEmbeddingModel());
        set.setEmbeddingDimension(2);
        set.setGeneratorVersion(properties.getKnowledgeScopeGate().getGeneratorVersion());
        set.setProfileLimit(8);
        set.setProfileCount(1);
        set.setStatus("READY");
        KnowledgeBaseRouteProfile profile = new KnowledgeBaseRouteProfile();
        profile.setProfileType("TOPIC");
        profile.setProfileKey("topic-a");
        profile.setProfileText("报销流程");
        profile.setProfileEmbedding(new float[]{1F, 0F});
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(kb);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(manifestBuilder.build(eq(kb), any())).thenReturn("manifest-a");
        when(setMapper.selectOne(any())).thenReturn(set);
        when(profileMapper.selectList(any())).thenReturn(List.of(profile));
        return kb;
    }

    private static KnowledgeBase knowledgeBase(long generation) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        kb.setUuid("kb-a");
        kb.setIsEnabled(true);
        kb.setRouteProfileStatus("BUILDING");
        kb.setRouteProfileGeneration(generation);
        kb.setRouteProfileActiveGeneration(generation - 1);
        return kb;
    }

    private static void initializeTableInfo(Class<?> entityType) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(entityType.getName());
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
