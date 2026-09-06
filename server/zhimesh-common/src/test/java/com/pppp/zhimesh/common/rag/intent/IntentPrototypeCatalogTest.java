package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentPrototypeCatalogTest {
    @Test
    void loadsClasspathCatalogLazilyAndCachesEmbeddedPrototypes() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingModel model = segments -> {
            calls.incrementAndGet();
            return Response.from(segments.stream().map(ignored -> Embedding.from(new float[]{1F, 0.5F})).toList());
        };
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setPrototypeResource("classpath:rag/intent-prototypes.zh-CN.json");
        IntentPrototypeCatalog catalog = new IntentPrototypeCatalog(
                new ObjectMapper(), new DefaultResourceLoader(), model, properties);

        IntentPrototypeCatalog.EmbeddedCatalog first = catalog.get();
        IntentPrototypeCatalog.EmbeddedCatalog second = catalog.get();

        assertThat(first).isSameAs(second);
        assertThat(first.dimension()).isEqualTo(2);
        assertThat(first.vectors()).containsKeys(QueryIntent.NO_RAG,
                QueryIntent.KNOWLEDGE_LOOKUP, QueryIntent.RELATIONSHIP);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsCorruptMetadataAndDimensionMismatch() {
        String json = """
                {"version":"v1","embeddingModel":"different-model","embeddingDimension":3,
                 "prototypes":{"KNOWLEDGE_LOOKUP":["knowledge"],"RELATIONSHIP":["relation"]}}
                """;
        ResourceLoader loader = new ResourceLoader() {
            @Override
            public org.springframework.core.io.Resource getResource(String location) {
                return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
        EmbeddingModel model = segments -> Response.from(
                segments.stream().map(ignored -> Embedding.from(new float[]{1F, 0F})).toList());
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.setEmbeddingModel("runtime-model");
        properties.getIntentRouting().setPrototypeResource("memory:test");

        assertThatThrownBy(() -> new IntentPrototypeCatalog(
                new ObjectMapper(), loader, model, properties).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");

        properties.setEmbeddingModel("different-model");
        assertThatThrownBy(() -> new IntentPrototypeCatalog(
                new ObjectMapper(), loader, model, properties).get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension");
    }
}
