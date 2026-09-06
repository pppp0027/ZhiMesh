package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalRoutePrototypeCatalogTest {
    @Test
    void loadsBalancedClasspathCatalogWithFifteenExamplesPerProfile() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingModel model = segments -> {
            calls.incrementAndGet();
            return Response.from(segments.stream()
                    .map(ignored -> Embedding.from(new float[]{1F, 0.5F})).toList());
        };
        ZhiMeshProperties properties = new ZhiMeshProperties();
        RetrievalRoutePrototypeCatalog catalog = new RetrievalRoutePrototypeCatalog(
                new ObjectMapper(), new DefaultResourceLoader(), model, properties);

        RetrievalRoutePrototypeCatalog.EmbeddedCatalog first = catalog.get();
        RetrievalRoutePrototypeCatalog.EmbeddedCatalog second = catalog.get();

        assertThat(first).isSameAs(second);
        assertThat(first.vectors()).hasSize(4);
        assertThat(first.vectors().values()).allSatisfy(vectors -> assertThat(vectors).hasSize(15));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsUnbalancedOrOutOfRangeProfileCounts() throws Exception {
        assertThatThrownBy(() -> catalog(json(profileCounts(16, 15, 15, 15))).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same number");

        assertThatThrownBy(() -> catalog(json(profileCounts(14, 14, 14, 14))).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 15 and 25");

        assertThatThrownBy(() -> catalog(json(profileCounts(26, 26, 26, 26))).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 15 and 25");
    }

    @Test
    void classpathProfilesExposeUnambiguousRoutingSignals() throws Exception {
        JsonNode prototypes = new ObjectMapper().readTree(new DefaultResourceLoader()
                        .getResource("classpath:rag/retrieval-route-prototypes.zh-CN.json")
                        .getInputStream())
                .path("prototypes");
        RuleIntentRecognizer rules = new RuleIntentRecognizer();

        assertSignals(prototypes, rules, RetrievalRouteProfile.VECTOR_ONLY, false, false);
        assertSignals(prototypes, rules, RetrievalRouteProfile.VECTOR_GRAPH, true, false);
        assertSignals(prototypes, rules, RetrievalRouteProfile.VECTOR_BM25, false, true);
        assertSignals(prototypes, rules, RetrievalRouteProfile.VECTOR_GRAPH_BM25, true, true);
    }

    private static RetrievalRoutePrototypeCatalog catalog(byte[] json) {
        ResourceLoader loader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(json);
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
        EmbeddingModel model = segments -> Response.from(segments.stream()
                .map(ignored -> Embedding.from(new float[]{1F, 0F})).toList());
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setRoutePrototypeResource("memory:route-prototypes");
        return new RetrievalRoutePrototypeCatalog(new ObjectMapper(), loader, model, properties);
    }

    private static byte[] json(Map<String, List<String>> prototypes) throws Exception {
        return new ObjectMapper().writeValueAsBytes(Map.of(
                "version", "test-v1",
                "embeddingModel", "*",
                "embeddingDimension", 2,
                "prototypes", prototypes));
    }

    private static Map<String, List<String>> profileCounts(int vector, int graph, int bm25, int all) {
        Map<String, List<String>> profiles = new LinkedHashMap<>();
        profiles.put("VECTOR_ONLY", examples("vector", vector));
        profiles.put("VECTOR_GRAPH", examples("graph", graph));
        profiles.put("VECTOR_BM25", examples("bm25", bm25));
        profiles.put("VECTOR_GRAPH_BM25", examples("all", all));
        return profiles;
    }

    private static List<String> examples(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(index -> prefix + "-example-" + index).toList();
    }

    private static void assertSignals(JsonNode prototypes, RuleIntentRecognizer rules,
                                      RetrievalRouteProfile profile,
                                      boolean relationship, boolean exactIdentifier) {
        for (JsonNode example : prototypes.path(profile.name())) {
            RuleIntentRecognizer.SignalAnalysis analysis = rules.analyze(example.asText());
            assertThat(analysis.signals().contains(IntentSignal.RELATION_QUERY))
                    .as("%s relationship signal: %s", profile, example.asText())
                    .isEqualTo(relationship);
            assertThat(analysis.signals().contains(IntentSignal.EXACT_IDENTIFIER))
                    .as("%s exact-identifier signal: %s", profile, example.asText())
                    .isEqualTo(exactIdentifier);
            assertThat(analysis.signals()).doesNotContain(
                    IntentSignal.NO_RAG_PATTERN, IntentSignal.AMBIGUOUS_CONTEXT);
        }
    }
}
