package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalRelevanceGateTest {

    private final ZhiMeshProperties.Retrieval properties = new ZhiMeshProperties.Retrieval();

    @Test
    void rejectsTechnicalDocumentationForUnrelatedNarutoQuestionWithoutReranker() {
        RetrievedCandidate candidate = candidate(
                "技术文档的信息架构与版本治理需要统一版本标识", null);

        List<RetrievedCandidate> result = RetrievalRelevanceGate.filter(
                "火影忍者里面的面具男是谁？", List.of(candidate), false, false, properties);

        assertTrue(result.isEmpty());
    }

    @Test
    void acceptsEvidenceWithInformativeLexicalOverlapWithoutReranker() {
        RetrievedCandidate candidate = candidate(
                "技术文档应建立版本治理流程和统一版本标识", null);

        List<RetrievedCandidate> result = RetrievalRelevanceGate.filter(
                "请根据知识库说明版本治理", List.of(candidate), false, false, properties);

        assertEquals(List.of(candidate), result);
    }

    @Test
    void successfulRerankAppliesAbsoluteFloorAndCanRejectEverything() {
        RetrievedCandidate weak = candidate("弱相关内容", null);
        weak.setRerankScore(0.29D);

        assertTrue(RetrievalRelevanceGate.filter(
                "任意问题", List.of(weak), true, true, properties).isEmpty());
    }

    @Test
    void highConfidenceVectorCanSurviveRerankerFallback() {
        RetrievedCandidate highConfidence = candidate("没有字面重合但向量高度相关", 0.83D);

        assertEquals(List.of(highConfidence), RetrievalRelevanceGate.filter(
                "完全不同的查询字面", List.of(highConfidence), false, true, properties));
    }

    @Test
    void preRerankGateDropsOnlyWeakSingleRouteVectorWithoutLexicalOverlap() {
        RetrievedCandidate weakUnrelated = candidate("技术文档版本治理", 0.69D);
        RetrievedCandidate weakButLexical = candidate("面具男的身份分析", 0.69D);
        RetrievedCandidate strongSemantic = candidate("没有字面重合", 0.82D);

        assertEquals(List.of(weakButLexical, strongSemantic), RetrievalRelevanceGate.preFilter(
                "火影忍者里面的面具男是谁", List.of(
                        weakUnrelated, weakButLexical, strongSemantic), properties));
    }

    @Test
    void preRerankGateCanBeDisabledForCalibrationRollback() {
        properties.setPreRerankGateEnabled(false);
        RetrievedCandidate weak = candidate("技术文档版本治理", 0.10D);

        assertEquals(List.of(weak), RetrievalRelevanceGate.preFilter(
                "火影忍者里面的面具男是谁", List.of(weak), properties));
    }

    @Test
    void rerankerFallbackKeepsTopRankedGraphEvidenceWithoutLexicalOverlap() {
        RetrievedCandidate graphTop = routedCandidate("订单流程触发库存扣减", "graph", 1);
        RetrievedCandidate graphSecond = routedCandidate("库存扣减触发物流发货", "graph", 2);

        assertEquals(List.of(graphTop, graphSecond), RetrievalRelevanceGate.filter(
                "用户下单之后仓库怎么处理", List.of(graphTop, graphSecond), false, true, properties));
    }

    @Test
    void rerankerFallbackCapsGraphExemptionsAndDropsDeepGraphTail() {
        RetrievedCandidate graphTop = routedCandidate("订单流程触发库存扣减", "graph", 1);
        RetrievedCandidate graphSecond = routedCandidate("库存扣减触发物流发货", "graph", 2);
        RetrievedCandidate graphThird = routedCandidate("物流发货结束订单生命周期", "graph", 3);

        assertEquals(List.of(graphTop, graphSecond), RetrievalRelevanceGate.filter(
                "用户下单之后仓库怎么处理",
                List.of(graphTop, graphSecond, graphThird), false, true, properties));
    }

    @Test
    void rerankerFallbackKeepsCorroboratedHybridCandidates() {
        RetrievedCandidate corroborated = routedCandidate("版本治理统一管理文档发布", "vector", 3);
        corroborated.merge(routedCandidate("版本治理统一管理文档发布", "graph", 2));

        assertEquals(List.of(corroborated), RetrievalRelevanceGate.filter(
                "如何管理文档的迭代发布", List.of(corroborated), false, true, properties));
    }

    @Test
    void graphExemptionCanBeDisabledByRankFloorZero() {
        properties.setFallbackGraphRankFloor(0);
        RetrievedCandidate graphTop = routedCandidate("订单流程触发库存扣减", "graph", 1);

        assertTrue(RetrievalRelevanceGate.filter(
                "用户下单之后仓库怎么处理", List.of(graphTop), false, true, properties).isEmpty());
    }

    private static RetrievedCandidate candidate(String text, Double vectorScore) {
        Metadata metadata = vectorScore == null
                ? new Metadata()
                : new Metadata(Map.of(RetrievedCandidate.VECTOR_SCORE, vectorScore));
        return RetrievedCandidate.from(
                Content.from(TextSegment.from(text, metadata)), "vector", 1);
    }

    private static RetrievedCandidate routedCandidate(String text, String route, int rank) {
        return RetrievedCandidate.from(Content.from(TextSegment.from(text)), route, rank);
    }
}
