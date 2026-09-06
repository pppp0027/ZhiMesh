package com.pppp.zhimesh.common.workflow.node.variableaggregator;

import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

public class VariableAggregatorNode extends AbstractWfNode {

    public VariableAggregatorNode(WorkflowComponent wfComponent, WorkflowNode node, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, node, wfState, nodeState);
    }

    @Override
    protected NodeProcessResult onProcess() {
        VariableAggregatorNodeConfig config = checkAndGetConfig(VariableAggregatorNodeConfig.class);
        String separator = config.getSeparator() == null ? "\n" : config.getSeparator();
        String output = state.getInputs().stream()
                .map(NodeIOData::valueToString)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.joining(separator));

        return NodeProcessResult.builder()
                .content(List.of(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", output)))
                .build();
    }
}
