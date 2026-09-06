package com.pppp.zhimesh.common.dto.evaluation;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RagEvaluationAskResp {
    private String questionId;
    private String kbUuid;
    private Long answerModelId;
    private String answerModel;
    private String answer;
    private List<String> contexts;
    private List<String> retrievedDocumentIds;
    private List<String> retrievedDocumentNames;
    private List<String> retrievedSegmentIds;
    private List<RagEvaluationCandidateResp> candidates;
    private List<RagEvaluationRouteResp> routes;
    private Map<String, Object> rerank;
    private Map<String, Object> graphTrace;
    private Map<String, Long> timingMs;
    private Map<String, Integer> usage;
    private Map<String, Object> configSnapshot;
    /** Admin evaluation only; omitted unless explicitly requested for offline router training. */
    private List<Float> queryEmbedding;
}
