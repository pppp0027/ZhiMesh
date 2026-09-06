package com.pppp.zhimesh.common.openrouter.data;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OpenRouterSyncPlan {
    private String platform;
    private int catalogCount;
    private int freeCount;
    private int eligibleCount;
    private int probedCount;
    private int targetActiveCount;
    private int availableAfterSync;
    private boolean rateLimited;
    private List<OpenRouterSyncDecision> decisions;
}
