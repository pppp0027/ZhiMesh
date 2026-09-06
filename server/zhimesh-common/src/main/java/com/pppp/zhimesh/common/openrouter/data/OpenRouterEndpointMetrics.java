package com.pppp.zhimesh.common.openrouter.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class OpenRouterEndpointMetrics {
    private String providerName;
    private Integer contextLength;
    private Integer latencyP50Ms;
    private BigDecimal throughputP50;
    private BigDecimal uptime1d;
    private Map<String, String> pricing;
    private ObjectNode rawMetadata;
}
