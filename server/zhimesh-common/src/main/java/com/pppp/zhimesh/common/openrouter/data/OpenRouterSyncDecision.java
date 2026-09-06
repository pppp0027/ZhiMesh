package com.pppp.zhimesh.common.openrouter.data;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.OpenRouterModelState;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenRouterSyncDecision {
    private String platform;
    private String modelName;
    private String action;
    private String reason;
    private boolean managed;
    private AiModel existingModel;
    private OpenRouterModelState existingState;
    private OpenRouterCandidate candidate;
}
