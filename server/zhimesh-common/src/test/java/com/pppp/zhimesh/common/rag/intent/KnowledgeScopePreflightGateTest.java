package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeScopePreflightGateTest {

    private final KnowledgeScopeProfileProbe probe = mock(KnowledgeScopeProfileProbe.class);
    private final ZhiMeshProperties properties = new ZhiMeshProperties();
    private final Embedding queryEmbedding = Embedding.from(new float[]{1F, 0F});
    private KnowledgeScopePreflightGate gate;

    @BeforeEach
    void setUp() {
        gate = new KnowledgeScopePreflightGate(probe, new RuleIntentRecognizer(), properties);
    }

    @Test
    void weaklyNegativeProfileDoesNotSkipKnowledgeRouting() {
        when(probe.probe(any(), any()))
                .thenReturn(new KnowledgeScopeProfileProbe.Result(true, false, 0.59D, 3, "fresh"));

        KnowledgeScopeDecision decision = gate.evaluate(
                "火影忍者里面的面具男是谁？", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
        assertTrue(decision.prefetchedVectorContents().isEmpty());
    }

    @Test
    void grayBandFailsOpenAndReusesProbeCandidates() {
        when(probe.probe(any(), any()))
                .thenReturn(new KnowledgeScopeProfileProbe.Result(true, false, 0.78D, 3, "fresh"));

        KnowledgeScopeDecision decision = gate.evaluate(
                "服务应该怎样部署？", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
        assertEquals(0.78D, decision.maxVectorScore());
    }

    @Test
    void staleLowScoreCanNeverBlockKnowledgeRouting() {
        when(probe.probe(any(), any()))
                .thenReturn(new KnowledgeScopeProfileProbe.Result(true, true, 0.20D, 3, "stale"));

        KnowledgeScopeDecision decision = gate.evaluate(
                "新增制度的审批规则", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void updatingProfileDefaultsToRelatedWithoutRedisProbe() {
        KbInfoResp updating = readyKb();
        updating.setRouteProfileStatus("BUILDING");
        updating.setRouteProfileGeneration(2L);
        updating.setRouteProfileActiveGeneration(1L);

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(updating));

        assertEquals(KnowledgeScopeDecision.Status.RELATED, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
        verifyNoInteractions(probe);
    }

    @Test
    void exclusionThresholdIsStrictlyBelowPointSix() {
        when(probe.probe(any(), any()))
                .thenReturn(new KnowledgeScopeProfileProbe.Result(true, false, 0.60D, 3, "fresh"));

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void explicitKnowledgeRequestAndExactIdentifierBypassProbe() {
        KnowledgeScopeDecision explicit = gate.evaluate(
                "根据知识库说明部署流程", queryEmbedding, List.of(readyKb()));
        KnowledgeScopeDecision identifier = gate.evaluate(
                "ERR_CONNECTION_RESET 怎么处理", queryEmbedding, List.of(readyKb()));
        KnowledgeScopeDecision catalog = gate.evaluate(
                "你能和我讲一讲这个知识库有哪些内容", queryEmbedding, List.of(readyKb()));
        KnowledgeScopeDecision listing = gate.evaluate(
                "你有什么知识库", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.RELATED, explicit.status());
        assertEquals(KnowledgeScopeDecision.Status.RELATED, identifier.status());
        assertEquals(KnowledgeScopeDecision.Status.RELATED, catalog.status());
        assertEquals(KnowledgeScopeDecision.Status.RELATED, listing.status());
        verifyNoInteractions(probe);
    }

    @Test
    void explicitKnowledgeRegexNormalizesFullWidthInputAndDoesNotTreatNegationAsPositive() {
        KnowledgeScopeDecision fullWidth = gate.evaluate(
                "根据　当前知识库说明部署流程！！！", queryEmbedding, List.of(readyKb()));
        KnowledgeScopeDecision negated = gate.evaluate(
                "不要根据当前知识库回答", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.RELATED, fullWidth.status());
        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, negated.status());
        assertFalse(negated.skipKnowledgeBaseRouting());
    }

    @Test
    void ambiguousFollowUpAndUnavailableStatisticsFailOpen() {
        KnowledgeScopeDecision followUp = gate.evaluate(
                "那个呢？", queryEmbedding, List.of(readyKb()));
        KnowledgeScopeDecision contextualSentence = gate.evaluate(
                "那它为什么失败了？", queryEmbedding, List.of(readyKb()));
        KbInfoResp incomplete = readyKb();
        incomplete.setItemCount(null);
        KnowledgeScopeDecision missingProfile = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(incomplete));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, followUp.status());
        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, contextualSentence.status());
        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, missingProfile.status());
        verifyNoInteractions(probe);
    }

    @Test
    void titleLexicalMatchAndStrictKbCannotBeBlocked() {
        KbInfoResp lexical = readyKb();
        lexical.setTitle("数据库部署手册");
        KbInfoResp strict = readyKb();
        strict.setIsStrict(true);

        assertEquals(KnowledgeScopeDecision.Status.RELATED,
                gate.evaluate("数据库部署步骤", queryEmbedding, List.of(lexical)).status());
        assertEquals(KnowledgeScopeDecision.Status.RELATED,
                gate.evaluate("完全无关的问题", queryEmbedding, List.of(strict)).status());
        verifyNoInteractions(probe);
    }

    @Test
    void dedicatedKnowledgeBaseProfileNegativeCannotSkipTheSelectedScope() {
        when(probe.probe(any(), any()))
                .thenReturn(new KnowledgeScopeProfileProbe.Result(true, false, 0.20D, 3, "fresh"));
        KbInfoResp strict = readyKb();
        strict.setIsStrict(true);

        KnowledgeScopeDecision decision = gate.evaluateDedicatedKnowledgeBase(
                "你是什么模型？", queryEmbedding, List.of(strict));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void dedicatedPerKnowledgeBaseNegativeCannotLeaveAnEmptyResolvedScope() {
        when(probe.probe(any(), any())).thenReturn(new KnowledgeScopeProfileProbe.Result(
                true, false, 0.20D, 3, "fresh", Map.of(
                "kb-a", new KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch(
                        true, false, 0.20D, 0.18D, 3, "fresh"))));

        KnowledgeScopeDecision decision = gate.evaluateDedicatedKnowledgeBase(
                "你是什么模型？", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.hasRetrievalScope());
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void emptyStatisticsFailOpen() {
        KbInfoResp empty = readyKb();
        empty.setItemCount(0);

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(empty));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
        verifyNoInteractions(probe);
    }

    @Test
    void dedicatedStrictKnowledgeBaseStillProtectsExplicitKnowledgeRequest() {
        KbInfoResp strict = readyKb();
        strict.setIsStrict(true);

        KnowledgeScopeDecision decision = gate.evaluateDedicatedKnowledgeBase(
                "根据知识库说明部署流程", queryEmbedding, List.of(strict));

        assertEquals(KnowledgeScopeDecision.Status.RELATED, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
        verifyNoInteractions(probe);
    }

    @Test
    void probeFailureFailsOpen() {
        when(probe.probe(any(), any()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(readyKb()));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void multiKnowledgeBaseScopeRetainsRelatedAndUncertainButExcludesStrongNegative() {
        KbInfoResp related = readyKb();
        related.setUuid("kb-related");
        KbInfoResp unrelated = readyKb();
        unrelated.setUuid("kb-unrelated");
        KbInfoResp uncertain = readyKb();
        uncertain.setUuid("kb-uncertain");
        when(probe.probe(any(), any())).thenReturn(new KnowledgeScopeProfileProbe.Result(
                true, false, 0.88D, 9, "fresh", Map.of(
                        "kb-related", new KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch(
                                true, false, 0.88D, 0.84D, 3, "fresh"),
                        "kb-unrelated", new KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch(
                                true, false, 0.12D, 0.10D, 3, "fresh"),
                        "kb-uncertain", new KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch(
                                true, false, 0.58D, 0.50D, 3, "fresh"))));

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(related, unrelated, uncertain));

        assertEquals(KnowledgeScopeDecision.Status.RELATED, decision.status());
        assertTrue(decision.hasRetrievalScope());
        assertEquals(Set.of("kb-related", "kb-uncertain"), decision.retrievalKnowledgeBaseUuids());
        assertFalse(decision.retrievalKnowledgeBaseUuids().contains("kb-unrelated"));
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void incompletePerKnowledgeBaseProfileFailsOpenForThatScope() {
        KbInfoResp unrelated = readyKb();
        unrelated.setUuid("kb-unrelated");
        KbInfoResp unavailable = readyKb();
        unavailable.setUuid("kb-unavailable");
        when(probe.probe(any(), any())).thenReturn(new KnowledgeScopeProfileProbe.Result(
                true, false, 0.12D, 3, "partial", Map.of(
                        "kb-unrelated", new KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch(
                                true, false, 0.12D, 0.10D, 3, "fresh"),
                        "kb-unavailable", KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch
                                .incomplete("redis payload missing"))));

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(unrelated, unavailable));

        assertEquals(KnowledgeScopeDecision.Status.UNCERTAIN, decision.status());
        assertEquals(Set.of("kb-unavailable"), decision.retrievalKnowledgeBaseUuids());
        assertFalse(decision.skipKnowledgeBaseRouting());
    }

    @Test
    void allFreshStrongNegativeKnowledgeBasesCanSkipTogether() {
        KbInfoResp first = readyKb();
        first.setUuid("kb-first");
        KbInfoResp second = readyKb();
        second.setUuid("kb-second");
        KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch negative =
                new KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch(
                        true, false, 0.12D, 0.10D, 3, "fresh");
        when(probe.probe(any(), any())).thenReturn(new KnowledgeScopeProfileProbe.Result(
                true, false, 0.12D, 6, "fresh", Map.of(
                        "kb-first", negative,
                        "kb-second", negative)));

        KnowledgeScopeDecision decision = gate.evaluate(
                "数据库部署方式", queryEmbedding, List.of(first, second));

        assertEquals(KnowledgeScopeDecision.Status.UNRELATED, decision.status());
        assertTrue(decision.hasRetrievalScope());
        assertTrue(decision.retrievalKnowledgeBaseUuids().isEmpty());
        assertTrue(decision.skipKnowledgeBaseRouting());
    }

    private static KbInfoResp readyKb() {
        KbInfoResp kb = new KbInfoResp();
        kb.setUuid("kb-a");
        kb.setTitle("技术资料");
        kb.setRemark("内部工程文档");
        kb.setItemCount(2);
        kb.setEmbeddingCount(20);
        kb.setIsStrict(false);
        kb.setRouteProfileStatus("READY");
        kb.setRouteProfileGeneration(1L);
        kb.setRouteProfileActiveGeneration(1L);
        return kb;
    }

}
