package com.pppp.zhimesh.common.workflow.node.texttransform;

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

public class TextTransformNode extends AbstractWfNode {

    public TextTransformNode(WorkflowComponent wfComponent, WorkflowNode node, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, node, wfState, nodeState);
    }

    @Override
    protected NodeProcessResult onProcess() {
        TextTransformNodeConfig config = checkAndGetConfig(TextTransformNodeConfig.class);
        String input = state.getInputs().isEmpty() ? "" : getFirstInputText();
        String operation = StringUtils.defaultIfBlank(config.getOperation(), "trim");

        String output = switch (operation) {
            case "uppercase" -> input.toUpperCase();
            case "lowercase" -> input.toLowerCase();
            case "replace" -> StringUtils.replace(input, StringUtils.defaultString(config.getFindText()), StringUtils.defaultString(config.getReplaceText()));
            default -> input.trim();
        };

        return NodeProcessResult.builder()
                .content(List.of(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", output)))
                .build();
    }
}
