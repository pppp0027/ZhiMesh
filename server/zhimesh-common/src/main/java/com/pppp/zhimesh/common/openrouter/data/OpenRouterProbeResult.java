package com.pppp.zhimesh.common.openrouter.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenRouterProbeResult {
    private String status;
    private Integer httpStatus;
    private Integer ttftMs;
    private Integer totalLatencyMs;
    private String errorCode;
    private String errorMessage;
    /**
     * True when the 429 originated from the upstream provider's shared free
     * pool (error metadata limit_source "upstream_*"): that congestion is
     * per-model and unrelated to this account, so it must not abort the run
     * or start the global cooldown the way an account-level rate limit does.
     */
    private boolean upstreamRateLimited;

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public boolean isRateLimited() {
        return "RATE_LIMITED".equals(status);
    }
}
