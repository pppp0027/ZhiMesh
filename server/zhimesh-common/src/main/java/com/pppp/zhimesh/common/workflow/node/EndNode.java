package com.pppp.zhimesh.common.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.workflow.*;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;


@Slf4j
public class EndNode extends AbstractWfNode {

    public EndNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
    }

    @Override
    protected NodeProcessResult onProcess() {
        List<NodeIOData> result = new ArrayList<>();
        JsonNode resultNode = node.getNodeConfig().get("result");
        String output = "";
        if (null == resultNode) {
            log.warn("EndNode result config is empty, nodeUuid: {}, title: {}", node.getUuid(), node.getTitle());
        } else {
            String resultTemplate = resultNode.asText();
            WfNodeIODataUtil.changeFilesContentToMarkdown(state.getInputs());
            output = WorkflowUtil.renderTemplate(resultTemplate, state.getInputs());
        }
        result.add(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", output));
        return NodeProcessResult.builder().content(result).build();
    }
}
