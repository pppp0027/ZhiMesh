package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRoutingServiceTest {
    @Test
    void disabledConfigurationPreservesHybridAndDoesNotInvokeClassifier() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setEnabled(false);
        LinearRoutingIntentRecognizer classifier = mock(LinearRoutingIntentRecognizer.class);
        IntentRoutingService service = service(properties, classifier,
                new RetrieverCapabilityRegistry());

        RetrievalPlan plan = service.route("query", Embedding.from(new float[]{1F, 0F}), null);

        assertThat(plan.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);
        assertThat(plan.decision().recognizer()).isEqualTo("disabled");
    }

    @Test
    void enabledClassifierRouteIsExecutedDirectly() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setEnabled(true);
        properties.getIntentRouting().setClassifierEnabled(true);
        LinearRoutingIntentRecognizer classifier = mock(LinearRoutingIntentRecognizer.class);
        when(classifier.recognize(any())).thenReturn(new IntentDecision(
                QueryIntent.KNOWLEDGE_LOOKUP, 0.96D, 0.7D, Set.of(), Set.of(),
                "linear-router:test", "test",
                Map.of(RoutingCapability.VECTOR_SUFFICIENT, 0.96D),
                Set.of(RoutingCapability.VECTOR_SUFFICIENT)));

        RetrievalPlan plan = service(properties, classifier,
                new RetrieverCapabilityRegistry()).route(
                "query", Embedding.from(new float[]{1F, 0F}), null);

        assertThat(plan.proposed().routes()).containsExactly(RetrievalRoute.VECTOR);
        assertThat(plan.effective().routes()).containsExactly(RetrievalRoute.VECTOR);
    }

    @Test
    void classifierFailureFallsBackToCurrentBaseline() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setEnabled(true);
        properties.getIntentRouting().setClassifierEnabled(true);
        LinearRoutingIntentRecognizer classifier = mock(LinearRoutingIntentRecognizer.class);
        when(classifier.recognize(any())).thenThrow(new IllegalStateException("broken model"));

        RetrievalPlan plan = service(properties, classifier,
                new RetrieverCapabilityRegistry()).route(
                "query", Embedding.from(new float[]{1F, 0F}), null);

        assertThat(plan.fallback()).isTrue();
        assertThat(plan.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);
    }

    @Test
    void explicitEvaluationModeBypassesClassifier() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setEnabled(true);
        properties.getIntentRouting().setClassifierEnabled(true);
        LinearRoutingIntentRecognizer classifier = mock(LinearRoutingIntentRecognizer.class);

        RetrievalPlan plan = service(properties, classifier,
                new RetrieverCapabilityRegistry()).route(
                "relationship query", null, RetrievalMode.GRAPH);

        assertThat(plan.effective().routes()).containsExactly(RetrievalRoute.GRAPH);
    }

    @Test
    void bm25IsExecutedOnlyWhenTheScopedCallerDeclaresItReady() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getIntentRouting().setEnabled(true);
        properties.getIntentRouting().setClassifierEnabled(true);
        LinearRoutingIntentRecognizer classifier = mock(LinearRoutingIntentRecognizer.class);
        when(classifier.recognize(any())).thenReturn(new IntentDecision(
                QueryIntent.KNOWLEDGE_LOOKUP, 0.96D, 0.7D, Set.of(), Set.of(),
                "linear-router:test", "test",
                Map.of(RoutingCapability.BM25_REQUIRED, 0.96D),
                Set.of(RoutingCapability.BM25_REQUIRED)));
        RetrieverCapabilityRegistry registry = new RetrieverCapabilityRegistry();
        registry.register(KnowledgeSourceType.DOCUMENT_KB, RetrievalRoute.BM25);
        IntentRoutingService service = service(properties, classifier, registry);

        RetrievalPlan notReady = service.route("ERR_AUTH_401 是什么", null, null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH));
        assertThat(notReady.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);

        RetrievalPlan ready = service.route("ERR_AUTH_401 是什么", null, null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25));
        assertThat(ready.effective().routes()).containsExactlyInAnyOrder(
                RetrievalRoute.VECTOR, RetrievalRoute.BM25);
    }

    private static IntentRoutingService service(ZhiMeshProperties properties,
                                                LinearRoutingIntentRecognizer classifier,
                                                RetrieverCapabilityRegistry registry) {
        return new IntentRoutingService(classifier, mock(PrototypeRouteIntentRecognizer.class),
                new IntentRoutingPolicy(registry), properties);
    }
}
