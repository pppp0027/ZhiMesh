package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class Bm25ReadinessServiceTest {

    @Test
    void readyKnowledgeBasesCachesPerKbWithinTtl() {
        Bm25Repository repository = mock(Bm25Repository.class);
        ZhiMeshProperties properties = enabledProperties(60_000L);
        when(repository.readyKnowledgeBases(anyCollection(), anyString()))
                .thenReturn(Set.of("kb-a"));
        Bm25ReadinessService service = new Bm25ReadinessService(repository, properties);

        assertThat(service.readyKnowledgeBases(Set.of("kb-a", "kb-b"))).containsExactly("kb-a");
        assertThat(service.readyKnowledgeBases(Set.of("kb-a", "kb-b"))).containsExactly("kb-a");

        verify(repository, times(1)).readyKnowledgeBases(anyCollection(), anyString());
    }

    @Test
    void onlyUnresolvedKnowledgeBasesHitTheRepository() {
        Bm25Repository repository = mock(Bm25Repository.class);
        ZhiMeshProperties properties = enabledProperties(60_000L);
        when(repository.readyKnowledgeBases(anyCollection(), anyString()))
                .thenReturn(Set.of("kb-a"));
        Bm25ReadinessService service = new Bm25ReadinessService(repository, properties);

        service.readyKnowledgeBases(Set.of("kb-a"));
        service.readyKnowledgeBases(Set.of("kb-a", "kb-b"));

        // 第二次只为未缓存的 kb-b 回源，kb-a 命中本地缓存
        ArgumentCaptor<Collection<String>> scopeCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(repository, times(2)).readyKnowledgeBases(scopeCaptor.capture(),
                eq(properties.getRetrieval().getBm25().getAnalyzerVersion()));
        assertThat(scopeCaptor.getAllValues().get(0)).containsExactly("kb-a");
        assertThat(scopeCaptor.getAllValues().get(1)).containsExactly("kb-b");
    }

    @Test
    void analyzerVersionChangeBypassesCache() {
        Bm25Repository repository = mock(Bm25Repository.class);
        ZhiMeshProperties properties = enabledProperties(60_000L);
        when(repository.readyKnowledgeBases(anyCollection(), anyString()))
                .thenReturn(Set.of("kb-a"));
        Bm25ReadinessService service = new Bm25ReadinessService(repository, properties);

        service.readyKnowledgeBases(Set.of("kb-a"));
        properties.getRetrieval().getBm25().setAnalyzerVersion("analyzer-v2");
        service.readyKnowledgeBases(Set.of("kb-a"));

        verify(repository, times(2)).readyKnowledgeBases(anyCollection(), anyString());
    }

    @Test
    void ttlDisabledQueriesRepositoryEveryTime() {
        Bm25Repository repository = mock(Bm25Repository.class);
        ZhiMeshProperties properties = enabledProperties(0L);
        when(repository.readyKnowledgeBases(anyCollection(), anyString()))
                .thenReturn(Set.of("kb-a"));
        Bm25ReadinessService service = new Bm25ReadinessService(repository, properties);

        service.readyKnowledgeBases(Set.of("kb-a"));
        service.readyKnowledgeBases(Set.of("kb-a"));

        verify(repository, times(2)).readyKnowledgeBases(anyCollection(), anyString());
    }

    @Test
    void requireReadyAcceptsCachedReadyScope() {
        Bm25Repository repository = mock(Bm25Repository.class);
        ZhiMeshProperties properties = enabledProperties(60_000L);
        when(repository.readyKnowledgeBases(anyCollection(), anyString()))
                .thenReturn(Set.of("kb-a", "kb-b"));
        Bm25ReadinessService service = new Bm25ReadinessService(repository, properties);

        service.requireReady(Set.of("kb-a", "kb-b"));
        service.requireReady(Set.of("kb-a", "kb-b"));

        verify(repository, times(1)).readyKnowledgeBases(anyCollection(), anyString());
    }

    private static ZhiMeshProperties enabledProperties(long ttlMs) {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getRetrieval().getBm25().setEnabled(true);
        properties.getRetrieval().getBm25().setReadinessCacheTtlMs(ttlMs);
        return properties;
    }
}
