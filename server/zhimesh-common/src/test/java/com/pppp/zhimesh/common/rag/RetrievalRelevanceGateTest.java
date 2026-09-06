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

    private static RetrievedCandidate candidate(String text, Double vectorScore) {
        Metadata metadata = vectorScore == null
                ? new Metadata()
                : new Metadata(Map.of(RetrievedCandidate.VECTOR_SCORE, vectorScore));
        return RetrievedCandidate.from(
                Content.from(TextSegment.from(text, metadata)), "vector", 1);
    }
}
