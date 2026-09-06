package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileBundle;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCache;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCodec;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultKnowledgeScopeProfileProbeTest {

    private final KnowledgeRouteProfileCache cache = mock(KnowledgeRouteProfileCache.class);
    private final KnowledgeRouteProfileCodec codec = new KnowledgeRouteProfileCodec();
    private final ZhiMeshProperties properties = new ZhiMeshProperties();

    DefaultKnowledgeScopeProfileProbeTest() {
        properties.setEmbeddingModel("local:bge-small-zh-v1.5");
    }

    @Test
    void comparesOnlyTheVersionedServingBundleAndMarksRebuildingScopeStale() {
        KbInfoResp kb = kb("BUILDING", 2L, 1L);
        KnowledgeRouteProfileBundle bundle = bundle(1L, new float[]{1F, 0F});
        when(cache.getAll(anyMap())).thenReturn(Map.of("kb-a", bundle));

        KnowledgeScopeProfileProbe.Result result = new DefaultKnowledgeScopeProfileProbe(
                cache, codec, properties).probe(
                List.of(Embedding.from(new float[]{1F, 0F})), List.of(kb));

        assertThat(result.complete()).isTrue();
        assertThat(result.containsStaleProfile()).isTrue();
        assertThat(result.maxScore()).isGreaterThan(0.99D);
        assertThat(result.comparedProfiles()).isEqualTo(1);
    }

    @Test
    void missingBundleFailsOpenAsIncomplete() {
        when(cache.getAll(anyMap())).thenReturn(Map.of());

        KnowledgeScopeProfileProbe.Result result = new DefaultKnowledgeScopeProfileProbe(
                cache, codec, properties).probe(
                List.of(Embedding.from(new float[]{1F, 0F})), List.of(kb("READY", 1L, 1L)));

        assertThat(result.complete()).isFalse();
    }

    @Test
    void incompatibleSourceOrGeneratorFailsOpenAsIncomplete() {
        KbInfoResp kb = kb("READY", 1L, 1L);
        KnowledgeRouteProfileBundle wrongGenerator = new KnowledgeRouteProfileBundle(
                "kb-a", 1L, "set-a", "hash", 0L,
                properties.getEmbeddingModel(), 2, "legacy-generator",
                List.of(new KnowledgeRouteProfileBundle.Profile(
                        "TOPIC", "topic", "topic", codec.encode(new float[]{1F, 0F}))));
        when(cache.getAll(anyMap())).thenReturn(Map.of("kb-a", wrongGenerator));

        KnowledgeScopeProfileProbe.Result result = new DefaultKnowledgeScopeProfileProbe(
                cache, codec, properties).probe(
                List.of(Embedding.from(new float[]{1F, 0F})), List.of(kb));

        assertThat(result.complete()).isFalse();
    }

    @Test
    void takesTheHighestScoreAcrossOriginalAndRewrittenQueriesFromOneBundleRead() {
        KbInfoResp kb = kb("READY", 1L, 1L);
        when(cache.getAll(anyMap())).thenReturn(Map.of("kb-a", bundle(1L, new float[]{1F, 0F})));

        KnowledgeScopeProfileProbe.Result result = new DefaultKnowledgeScopeProfileProbe(
                cache, codec, properties).probe(
                List.of(Embedding.from(new float[]{0F, 1F}), Embedding.from(new float[]{1F, 0F})),
                List.of(kb));

        assertThat(result.maxScore()).isGreaterThan(0.99D);
        assertThat(result.comparedProfiles()).isEqualTo(1);
        assertThat(result.containsStaleProfile()).isTrue();
    }

    @Test
    void usesTheAdaptiveBudgetWhenProbingAProfileBundle() {
        KbInfoResp kb = kb("READY", 1L, 1L);
        kb.setEmbeddingCount(10_000);
        List<KnowledgeRouteProfileBundle.Profile> profiles = IntStream.range(0, 32)
                .mapToObj(index -> new KnowledgeRouteProfileBundle.Profile(
                        "TOPIC", "topic-" + index, "topic-" + index,
                        codec.encode(new float[]{1F, 0F})))
                .toList();
        KnowledgeRouteProfileBundle oversized = new KnowledgeRouteProfileBundle(
                "kb-a", 1L, "set-a", "hash", 0L,
                properties.getEmbeddingModel(), 2,
                properties.getKnowledgeScopeGate().getGeneratorVersion(), profiles);
        when(cache.getAll(anyMap())).thenReturn(Map.of("kb-a", oversized));

        KnowledgeScopeProfileProbe.Result result = new DefaultKnowledgeScopeProfileProbe(
                cache, codec, properties).probe(
                List.of(Embedding.from(new float[]{1F, 0F})), List.of(kb));

        assertThat(result.complete()).isTrue();
        assertThat(result.comparedProfiles()).isEqualTo(24);
        assertThat(result.matches().get("kb-a").comparedProfiles()).isEqualTo(24);
        assertThat(result.matches().get("kb-a").containsStaleProfile()).isTrue();
    }

    private KbInfoResp kb(String status, long generation, long activeGeneration) {
        KbInfoResp kb = new KbInfoResp();
        kb.setUuid("kb-a");
        kb.setItemCount(1);
        kb.setRouteProfileStatus(status);
        kb.setRouteProfileGeneration(generation);
        kb.setRouteProfileActiveGeneration(activeGeneration);
        kb.setRouteProfileSetUuid("set-a");
        kb.setRouteProfileSourceHash("hash");
        kb.setRouteProfileModelIdentity(properties.getEmbeddingModel());
        return kb;
    }

    private KnowledgeRouteProfileBundle bundle(long generation, float[] vector) {
        return new KnowledgeRouteProfileBundle("kb-a", generation, "set-a", "hash", 0L,
                properties.getEmbeddingModel(), vector.length,
                properties.getKnowledgeScopeGate().getGeneratorVersion(),
                List.of(new KnowledgeRouteProfileBundle.Profile(
                        "TOPIC", "topic", "topic", codec.encode(vector))));
    }
}
