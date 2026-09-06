package com.pppp.zhimesh.common.workflow.node.variableaggregator;

import lombok.Data;

@Data
public class VariableAggregatorNodeConfig {
    /** Defaults to a new line, which is the most useful format for prompt construction. */
    private String separator = "\n";
}
