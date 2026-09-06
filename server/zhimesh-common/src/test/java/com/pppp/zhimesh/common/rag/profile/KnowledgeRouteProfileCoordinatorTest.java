package com.pppp.zhimesh.common.rag.profile;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.util.RedisTemplateUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_ROUTE_PROFILE_REBUILD_SIGNAL;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRouteProfileCoordinatorTest {

    @Test
    void onlyAnExplicitIndexOperationCreatesAGenerationAndQueuesAsyncBuild() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeRouteProfileBuildService buildService = mock(KnowledgeRouteProfileBuildService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOperations);
        KnowledgeRouteProfileCoordinator coordinator = new KnowledgeRouteProfileCoordinator(
                mapper, buildService, redis, mock(RedisTemplateUtil.class), new ZhiMeshProperties());
        KnowledgeBase locked = new KnowledgeBase();
        locked.setUuid("kb-a");
        locked.setRouteProfileGeneration(1L);
        when(mapper.selectOne(any())).thenReturn(locked);
        when(mapper.markRouteProfileStale("kb-a")).thenReturn(1);
        long generation = coordinator.indexingRequested("kb-a");

        assertThat(generation).isEqualTo(2L);
        verify(mapper).markRouteProfileStale("kb-a");
        coordinator.indexingCompleted("kb-a", generation);
        verify(setOperations).add(KB_ROUTE_PROFILE_REBUILD_SIGNAL, "kb-a|2");
    }

    @Test
    void supersededBatchCompletionCannotQueueTheCurrentGeneration() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase current = new KnowledgeBase();
        current.setUuid("kb-a");
        current.setRouteProfileGeneration(3L);
        when(mapper.selectOne(any())).thenReturn(current);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(KB_ROUTE_PROFILE_REBUILD_SIGNAL))
                .thenReturn(java.util.Set.of("kb-a|2"));
        RedisTemplateUtil lock = mock(RedisTemplateUtil.class);
        when(lock.lock(anyString(), anyString(), anyLong())).thenReturn(true);
        KnowledgeRouteProfileBuildService buildService = mock(KnowledgeRouteProfileBuildService.class);
        KnowledgeRouteProfileCoordinator coordinator = new KnowledgeRouteProfileCoordinator(
                mapper, buildService, redis, lock, new ZhiMeshProperties());

        coordinator.indexingCompleted("kb-a", 2L);
        coordinator.processSignals();

        verify(buildService, never()).buildOrWarm(any());
        verify(setOperations).remove(KB_ROUTE_PROFILE_REBUILD_SIGNAL, "kb-a|2");
    }

    @Test
    void scheduledCacheRefreshNeverEntersTheRebuildPath() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeRouteProfileBuildService buildService = mock(KnowledgeRouteProfileBuildService.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUuid("kb-a");
        when(mapper.selectList(any())).thenReturn(java.util.List.of(kb));
        KnowledgeRouteProfileCoordinator coordinator = new KnowledgeRouteProfileCoordinator(
                mapper, buildService, mock(StringRedisTemplate.class),
                mock(RedisTemplateUtil.class), new ZhiMeshProperties());

        coordinator.refreshActiveCaches();

        verify(buildService).warmActive("kb-a");
        verify(buildService, never()).buildOrWarm(any());
    }
}
