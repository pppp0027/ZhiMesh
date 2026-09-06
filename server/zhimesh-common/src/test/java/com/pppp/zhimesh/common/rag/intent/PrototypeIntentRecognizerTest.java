package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrototypeIntentRecognizerTest {
    @Test
    void classifiesByTopThreeMeanAndReportsMargin() {
        ZhiMeshProperties properties = properties(0.50D, 0.10D);
        IntentPrototypeCatalog catalog = mock(IntentPrototypeCatalog.class);
        when(catalog.get()).thenReturn(new IntentPrototypeCatalog.EmbeddedCatalog("test-v1", 2, Map.of(
                QueryIntent.KNOWLEDGE_LOOKUP, List.of(v(1, 0), v(0.9F, 0.1F), v(0.8F, 0.2F)),
                QueryIntent.RELATIONSHIP, List.of(v(0, 1), v(0.1F, 0.9F), v(0.2F, 0.8F)))));
        PrototypeIntentRecognizer recognizer = new PrototypeIntentRecognizer(
                new RuleIntentRecognizer(), catalog, properties);

        IntentDecision decision = recognizer.recognize(context("如何部署服务", v(1, 0)));

        assertThat(decision.intent()).isEqualTo(QueryIntent.KNOWLEDGE_LOOKUP);
        assertThat(decision.confidence()).isGreaterThan(0.9D);
        assertThat(decision.margin()).isGreaterThan(0.5D);
        assertThat(decision.recognizer()).contains("test-v1");
    }

    @Test
    void rejectsLowMarginAndContextDependentQueries() {
        ZhiMeshProperties properties = properties(0.1D, 0.20D);
        IntentPrototypeCatalog catalog = mock(IntentPrototypeCatalog.class);
        when(catalog.get()).thenReturn(new IntentPrototypeCatalog.EmbeddedCatalog("test", 2, Map.of(
                QueryIntent.KNOWLEDGE_LOOKUP, List.of(v(1, 0)),
                QueryIntent.RELATIONSHIP, List.of(v(0.99F, 0.01F)))));
        PrototypeIntentRecognizer recognizer = new PrototypeIntentRecognizer(
                new RuleIntentRecognizer(), catalog, properties);

        assertThat(recognizer.recognize(context("说明功能", v(1, 0))).intent())
                .isEqualTo(QueryIntent.UNCERTAIN);
        assertThat(recognizer.recognize(context("这个", v(1, 0))).intent())
                .isEqualTo(QueryIntent.UNCERTAIN);
    }

    @Test
    void rejectsInvalidVectors() {
        assertThatThrownBy(() -> PrototypeIntentRecognizer.cosine(v(1, 0), v(1, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrototypeIntentRecognizer.cosine(v(0, 0), v(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrototypeIntentRecognizer.cosine(v(Float.NaN, 0), v(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IntentRoutingContext context(String query, float[] embedding) {
        return new IntentRoutingContext(query, Embedding.from(embedding), null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH), "test");
    }

    private static ZhiMeshProperties properties(double score, double margin) {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setMinTopScore(score);
        properties.getIntentRouting().setMinScoreMargin(margin);
        return properties;
    }

    private static float[] v(float... values) { return values; }
}
