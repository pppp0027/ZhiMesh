package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationCandidateResp;
import com.pppp.zhimesh.common.rag.intent.IntentDecision;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopeDecision;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.QueryIntent;
import com.pppp.zhimesh.common.rag.intent.RetrievalPlan;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrievalSelection;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationResultMapperTest {

    @Test
    void mapsStableCandidateMetadataAndDocumentNames() {
        Content content = Content.from(TextSegment.from("evidence", new Metadata(Map.ofEntries(
                Map.entry("content_type", "original_segment"),
                Map.entry("retrieval_routes", "vector,graph,bm25"),
                Map.entry("source_document_ids", "doc-1,doc-2"),
                Map.entry("source_segment_ids", "segment-1"),
                Map.entry("vector_score", 0.87D),
                Map.entry("vector_rank", 1),
                Map.entry("graph_rank", 2),
                Map.entry("bm25_rank", 3),
                Map.entry("bm25_score", 4.2D),
                Map.entry("route_ranks", "vector=1,graph=2,bm25=3"),
                Map.entry("rerank_score", 0.93D)
        ))));
        TokenCountEstimator estimator = new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                return text.length();
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return 0;
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                return 0;
            }
        };

        RagEvaluationCandidateResp result = RagEvaluationResultMapper.candidates(
                List.of(content), List.of(content),
                Map.of("doc-1", "first.txt", "doc-2", "second.txt"), estimator).get(0);

        assertEquals(List.of("vector", "graph", "bm25"), result.getRoutes());
        assertEquals(List.of("doc-1", "doc-2"), result.getSourceDocumentIds());
        assertEquals(List.of("first.txt", "second.txt"), result.getSourceDocumentNames());
        assertEquals(0.93D, result.getRerankScore());
        assertEquals(4.2D, result.getBm25Score());
        assertEquals(3, result.getBm25Rank());
        assertEquals(Map.of("vector", 1, "graph", 2, "bm25", 3), result.getRouteRanks());
        assertEquals(8, result.getTokenCount());
        assertTrue(result.isSelected());
    }

    @Test
    void echoesIntentDecisionWithContractKeyOrderAndSortedRoutes() {
        IntentDecision decision = new IntentDecision(QueryIntent.RELATIONSHIP, 0.81D, 0.09D,
                Set.of(), Set.of(KnowledgeSourceType.DOCUMENT_KB),
                "route-prototype:v3", "route prototype threshold passed");
        RetrievalSelection selection = new RetrievalSelection(
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.GRAPH, RetrievalRoute.BM25, RetrievalRoute.VECTOR));
        RetrievalPlan plan = new RetrievalPlan(decision, selection, selection, false, "plan reason");
        KnowledgeScopeDecision scopeDecision = new KnowledgeScopeDecision(
                KnowledgeScopeDecision.Status.RELATED, 0.83D,
                "a route profile passed the related threshold", List.of(), 12L);

        Map<String, Object> intent = RagEvaluationResultMapper.intent(
                plan, scopeDecision, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH), true);

        // 顶层键序与回显契约一一对应
        assertEquals(List.of("routingEnabled", "scopePreflight", "availableRoutes", "intent",
                "recognizer", "confidence", "margin", "proposedRoutes", "effectiveRoutes",
                "fallback", "reason", "recognitionReason"), List.copyOf(intent.keySet()));
        assertEquals(true, intent.get("routingEnabled"));
        // scopePreflight 嵌套：状态小写、skip/durationMs/reason 透传
        Map<String, Object> scopePreflight = (Map<String, Object>) intent.get("scopePreflight");
        assertEquals(List.of("status", "score", "skip", "durationMs", "reason"),
                List.copyOf(scopePreflight.keySet()));
        assertEquals("related", scopePreflight.get("status"));
        assertEquals(0.83D, scopePreflight.get("score"));
        assertEquals(false, scopePreflight.get("skip"));
        assertEquals(12L, scopePreflight.get("durationMs"));
        assertEquals("a route profile passed the related threshold", scopePreflight.get("reason"));
        // 可用路由透传，决策路由按 ordinal 排序后小写
        assertEquals(List.of("vector", "graph"), intent.get("availableRoutes"));
        assertEquals("relationship", intent.get("intent"));
        assertEquals("route-prototype:v3", intent.get("recognizer"));
        assertEquals(0.81D, intent.get("confidence"));
        assertEquals(0.09D, intent.get("margin"));
        assertEquals(List.of("vector", "graph", "bm25"), intent.get("proposedRoutes"));
        assertEquals(List.of("vector", "graph", "bm25"), intent.get("effectiveRoutes"));
        assertEquals(false, intent.get("fallback"));
        assertEquals("plan reason", intent.get("reason"));
        assertEquals("route prototype threshold passed", intent.get("recognitionReason"));
    }

    @Test
    void noRagPlanEchoesEmptyEffectiveRoutes() {
        IntentDecision decision = new IntentDecision(QueryIntent.NO_RAG, 0D, 0D,
                Set.of(), Set.of(), "rule", "definite no-rag question");
        RetrievalSelection empty = new RetrievalSelection(
                Set.of(KnowledgeSourceType.DOCUMENT_KB), Set.of());
        RetrievalPlan plan = new RetrievalPlan(decision, empty, empty, false, "no-rag plan");
        KnowledgeScopeDecision scopeDecision = new KnowledgeScopeDecision(
                KnowledgeScopeDecision.Status.UNCERTAIN, -1D, "gray band", List.of(), 5L);

        Map<String, Object> intent = RagEvaluationResultMapper.intent(
                plan, scopeDecision, Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH), true);

        // NO_RAG ⇒ effectiveRoutes 为空集是合法核心场景（不强制非空）
        assertEquals("no_rag", intent.get("intent"));
        assertEquals(List.of(), intent.get("effectiveRoutes"));
        assertEquals(List.of(), intent.get("proposedRoutes"));
        assertEquals(false, intent.get("fallback"));
    }

    @Test
    void nullPlanKeepsDecisionKeysNullAndEffectiveRoutesEmpty() {
        KnowledgeScopeDecision scopeDecision = new KnowledgeScopeDecision(
                KnowledgeScopeDecision.Status.UNRELATED, 0.12D,
                "dedicated knowledge-base QA keeps the selected scope", List.of(), 8L);

        Map<String, Object> intent = RagEvaluationResultMapper.intent(
                null, scopeDecision, Set.of(), true);

        // plan==null：决策字段为 null、effectiveRoutes=[]、availableRoutes 仍输出实参
        assertEquals(List.of(), intent.get("availableRoutes"));
        assertNull(intent.get("intent"));
        assertNull(intent.get("recognizer"));
        assertNull(intent.get("confidence"));
        assertNull(intent.get("margin"));
        assertNull(intent.get("proposedRoutes"));
        assertEquals(List.of(), intent.get("effectiveRoutes"));
        assertNull(intent.get("fallback"));
        assertNull(intent.get("reason"));
        assertNull(intent.get("recognitionReason"));
        Map<String, Object> scopePreflight = (Map<String, Object>) intent.get("scopePreflight");
        assertEquals("unrelated", scopePreflight.get("status"));
        assertEquals(true, scopePreflight.get("skip"));
    }

    @Test
    void routingEnabledFlagPassesThrough() {
        IntentDecision decision = new IntentDecision(QueryIntent.UNCERTAIN, 0D, 0D,
                Set.of(), Set.of(), "disabled", "intent routing is disabled");
        RetrievalSelection baseline = new RetrievalSelection(
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH));
        RetrievalPlan plan = new RetrievalPlan(decision, baseline, baseline, false,
                "intent routing is disabled");

        Map<String, Object> enabled = RagEvaluationResultMapper.intent(
                plan, null, Set.of(RetrievalRoute.VECTOR), true);
        Map<String, Object> disabled = RagEvaluationResultMapper.intent(
                plan, null, Set.of(RetrievalRoute.VECTOR), false);

        assertEquals(true, enabled.get("routingEnabled"));
        assertEquals(false, disabled.get("routingEnabled"));
        // 服务端开关关闭时 recognizer 透传 disabled，供采集端硬拒识别
        assertEquals("disabled", enabled.get("recognizer"));
        assertEquals("intent routing is disabled", enabled.get("recognitionReason"));
        // scopeDecision 为 null 时嵌套对象仍保持键契约与空值
        Map<String, Object> scopePreflight = (Map<String, Object>) enabled.get("scopePreflight");
        assertNull(scopePreflight.get("status"));
        assertEquals(false, scopePreflight.get("skip"));
        assertEquals(0L, scopePreflight.get("durationMs"));
    }
}
