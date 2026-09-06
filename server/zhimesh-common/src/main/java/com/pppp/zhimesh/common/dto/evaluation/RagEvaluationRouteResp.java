package com.pppp.zhimesh.common.dto.evaluation;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagEvaluationRouteResp {
    private String route;
    private String status;
    private long durationMs;
    private int candidateCount;
    private String errorType;
    private String errorMessage;
}
