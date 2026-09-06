package com.pppp.zhimesh.common.openrouter.data;

import com.pppp.zhimesh.common.entity.AiModel;
import lombok.Builder;
import lombok.Data;

/**
 * Result of the lightweight OpenRouter probe for one model already configured
 * in the local model catalog.
 */
@Data
@Builder
public class OpenRouterModelHealthCheck {

    private AiModel model;
    private OpenRouterProbeResult probe;
    private boolean healthy;
    private String reason;
}
