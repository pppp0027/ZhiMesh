package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinearRoutingIntentRecognizerTest {
    private static final String MODEL = """
            {
              "schemaVersion": 1,
              "modelVersion": "router-test-v1",
              "embeddingModel": "local:test",
              "embeddingDimension": 2,
              "normalization": "l2",
              "outputs": {
                "vectorSufficient": {"enabled": true, "weights": [10.0, 0.0], "bias": 0.0, "threshold": 0.8},
                "graphRequired": {"enabled": true, "weights": [-10.0, 0.0], "bias": 0.0, "threshold": 0.8},
                "bm25Required": {"enabled": true, "weights": [0.0, 10.0], "bias": 0.0, "threshold": 0.8}
              }
            }
            """;

    @Test
    void predictsIndependentCapabilitiesFromTheFrozenEmbedding() {
        ZhiMeshProperties properties = properties();
        RoutingClassifierModelCatalog catalog = catalog(MODEL, properties);
        LinearRoutingIntentRecognizer recognizer = new LinearRoutingIntentRecognizer(
                new RuleIntentRecognizer(), catalog);

        IntentDecision decision = recognizer.recognize(new IntentRoutingContext(
                "如何配置服务", Embedding.from(new float[]{1F, 0F}), null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH), "local:test"));

        assertThat(decision.intent()).isEqualTo(QueryIntent.KNOWLEDGE_LOOKUP);
        assertThat(decision.activatedCapabilities())
                .containsExactly(RoutingCapability.VECTOR_SUFFICIENT);
        assertThat(decision.routeScores()).containsKeys(
                RoutingCapability.VECTOR_SUFFICIENT, RoutingCapability.GRAPH_REQUIRED,
                RoutingCapability.BM25_REQUIRED);
        assertThat(decision.recognizer()).contains("router-test-v1");
    }

    @Test
    void rejectsWrongChecksumAndEmbeddingDimension() throws Exception {
        ZhiMeshProperties checksumProperties = properties();
        checksumProperties.getIntentRouting().setClassifierSha256("deadbeef");
        assertThatThrownBy(() -> catalog(MODEL, checksumProperties).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256");

        ZhiMeshProperties dimensionProperties = properties();
        String wrongDimension = MODEL.replace("\"embeddingDimension\": 2", "\"embeddingDimension\": 3");
        assertThatThrownBy(() -> catalog(wrongDimension, dimensionProperties).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");

        ZhiMeshProperties validProperties = properties();
        validProperties.getIntentRouting().setClassifierSha256(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(MODEL.getBytes(StandardCharsets.UTF_8))));
        assertThat(catalog(MODEL, validProperties).get().sha256()).isNotBlank();
    }

    private static ZhiMeshProperties properties() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.setEmbeddingModel("local:test");
        properties.getIntentRouting().setClassifierResource("memory:router");
        return properties;
    }

    private static RoutingClassifierModelCatalog catalog(String json, ZhiMeshProperties properties) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ResourceLoader loader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(bytes);
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
        return new RoutingClassifierModelCatalog(new ObjectMapper(), loader, properties);
    }
}
