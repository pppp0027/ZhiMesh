package com.pppp.zhimesh.common.workflow.node.template;

import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.workflow.*;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

@Slf4j
public class TemplateNode extends AbstractWfNode {

    public TemplateNode(WorkflowComponent wfComponent, WorkflowNode node, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, node, wfState, nodeState);
    }

    @Override
    protected NodeProcessResult onProcess() {
        // A newly added template node used to have an empty config object and failed before the
        // author could enter any text. Treat an empty template as a pass-through instead: it is
        // safe for an in-progress workflow and preserves the upstream document/text for the next node.
        TemplateNodeConfig nodeConfig = node.getNodeConfig() == null
                ? new TemplateNodeConfig()
                : JsonUtil.fromJson(node.getNodeConfig(), TemplateNodeConfig.class);
        if (nodeConfig == null) {
            nodeConfig = new TemplateNodeConfig();
        }
        log.info("Template node config:{}", nodeConfig);
        WfNodeIODataUtil.changeFilesContentToMarkdown(state.getInputs());
        String content = StringUtils.isBlank(nodeConfig.getTemplate())
                ? getFirstInputText()
                : WorkflowUtil.renderTemplate(nodeConfig.getTemplate(), state.getInputs());
        NodeIOData output = NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", content);
        return NodeProcessResult.builder().content(List.of(output)).build();
    }
}
