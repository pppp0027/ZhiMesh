package com.pppp.zhimesh.common.workflow.metrics;

import com.pppp.zhimesh.common.workflow.NodeExecutionMetrics;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * LLM 节点可观测指标 | LLM node observability metrics
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class LLMMetrics extends NodeExecutionMetrics {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer inputTokens;
    private Integer outputTokens;
    private String modelName;
    private String modelPlatform;
}
