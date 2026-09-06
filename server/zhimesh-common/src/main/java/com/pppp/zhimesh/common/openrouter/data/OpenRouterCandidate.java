package com.pppp.zhimesh.common.openrouter.data;

import com.pppp.zhimesh.common.entity.AiModel;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenRouterCandidate {
    private OpenRouterCatalogModel catalogModel;
    private OpenRouterEndpointMetrics endpoint;
    private OpenRouterProbeResult probe;
    private AiModel existingModel;
    private boolean catalogEligible;
    private boolean endpointEligible;
    private String modelType;
    private String inputTypes;
    private String responseFormatTypes;
    private boolean reasoner;
    private String catalogStatus;
    private String rejectionReason;
}
