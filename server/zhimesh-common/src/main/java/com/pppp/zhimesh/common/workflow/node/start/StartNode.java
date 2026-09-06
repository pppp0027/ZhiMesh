package com.pppp.zhimesh.common.workflow.node.start;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeIODataUtil;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_WF_NODE_CONFIG_NOT_FOUND;

@Slf4j
public class StartNode extends AbstractWfNode {

    public StartNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
    }

    @Override
    public NodeProcessResult onProcess() {
        ObjectNode objectConfig = node.getNodeConfig();
        if (null == objectConfig) {
            throw new BaseException(A_WF_NODE_CONFIG_NOT_FOUND);
        }
        List<NodeIOData> result = WfNodeIODataUtil.changeInputsToOutputs(state.getInputs());
        return NodeProcessResult.builder().content(result).build();
    }

}
