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

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public boolean isRateLimited() {
        return "RATE_LIMITED".equals(status);
    }
}
