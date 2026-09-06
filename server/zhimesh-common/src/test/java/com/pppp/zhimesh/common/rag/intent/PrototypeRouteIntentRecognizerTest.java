package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrototypeRouteIntentRecognizerTest {
    @Test
    void selectsVectorBm25PlanFromRoutePrototype() {
        ZhiMeshProperties properties = properties(0.50D, 0.10D);
        RetrievalRoutePrototypeCatalog catalog = mock(RetrievalRoutePrototypeCatalog.class);
        when(catalog.get()).thenReturn(new RetrievalRoutePrototypeCatalog.EmbeddedCatalog("test-v1", 2, Map.of(
                RetrievalRouteProfile.VECTOR_ONLY, List.of(v(0, 1)),
                RetrievalRouteProfile.VECTOR_GRAPH, List.of(v(0, -1)),
                RetrievalRouteProfile.VECTOR_BM25, List.of(v(0.99F, 0.1F)),
                RetrievalRouteProfile.VECTOR_GRAPH_BM25, List.of(v(-1, 0)))));

        PrototypeRouteIntentRecognizer recognizer = new PrototypeRouteIntentRecognizer(
                new RuleIntentRecognizer(), catalog, properties);

        IntentDecision decision = recognizer.recognize(context("错误码 E1007 是什么意思", v(0.99F, 0.1F)));

        assertThat(decision.intent()).isEqualTo(QueryIntent.KNOWLEDGE_LOOKUP);
        assertThat(decision.activatedCapabilities()).containsExactly(RoutingCapability.BM25_REQUIRED);
        assertThat(decision.recognizer()).contains("test-v1");
    }

    @Test
    void abstainsWhenRouteScoresAreTooClose() {
        ZhiMeshProperties properties = properties(0.50D, 0.20D);
        RetrievalRoutePrototypeCatalog catalog = mock(RetrievalRoutePrototypeCatalog.class);
        when(catalog.get()).thenReturn(new RetrievalRoutePrototypeCatalog.EmbeddedCatalog("test-v1", 2, Map.of(
                RetrievalRouteProfile.VECTOR_ONLY, List.of(v(1, 0)),
                RetrievalRouteProfile.VECTOR_GRAPH, List.of(v(0.99F, 0.1F)),
                RetrievalRouteProfile.VECTOR_BM25, List.of(v(-1, 0)),
                RetrievalRouteProfile.VECTOR_GRAPH_BM25, List.of(v(0, -1)))));

        PrototypeRouteIntentRecognizer recognizer = new PrototypeRouteIntentRecognizer(
                new RuleIntentRecognizer(), catalog, properties);

        assertThat(recognizer.recognize(context("说明这个功能", v(1, 0))).intent())
                .isEqualTo(QueryIntent.UNCERTAIN);
    }

    @Test
    void strongRulesRemainHigherPriorityThanPrototypes() {
        RetrievalRoutePrototypeCatalog catalog = mock(RetrievalRoutePrototypeCatalog.class);
        PrototypeRouteIntentRecognizer recognizer = new PrototypeRouteIntentRecognizer(
                new RuleIntentRecognizer(), catalog, properties(0.5D, 0.1D));

        IntentDecision decision = recognizer.recognize(context("用户和角色之间是什么关系？", null));

        assertThat(decision.intent()).isEqualTo(QueryIntent.RELATIONSHIP);
        assertThat(decision.recognizer()).isEqualTo("rule");
    }

    private static IntentRoutingContext context(String query, float[] embedding) {
        return new IntentRoutingContext(query, embedding == null ? null : Embedding.from(embedding), null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25), "test");
    }

    private static ZhiMeshProperties properties(double score, double margin) {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setMinTopScore(score);
        properties.getIntentRouting().setMinScoreMargin(margin);
        return properties;
    }

    private static float[] v(float... values) {
        return values;
    }
}
