package com.pppp.zhimesh.common.workflow.node.faqextractor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.service.SysConfigService;
import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.WorkflowUtil;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.data.NodeIODataTextContent;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.metrics.LLMMetrics;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_INPUT_PARAM_NAME;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_WF_NODE_CONFIG_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_WF_NODE_CONFIG_NOT_FOUND;

/**
 * 【节点】常见问题抽取 <br/>
 * 节点内容固定格式：FaqExtractorNodeConfig
 */
@Slf4j
public class FaqExtractorNode extends AbstractWfNode {

    public FaqExtractorNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        state.setMetrics(new LLMMetrics());
    }

    /**
     * nodeConfig格式：<br/>
     * {"top_n": 10,"model_name":"deepseek-chat"}<br/>
     *
     * @return LLM的返回内容
     */
    @Override
    public NodeProcessResult onProcess() {
        ObjectNode objectConfig = node.getNodeConfig();
        if (objectConfig.isEmpty()) {
            throw new BaseException(A_WF_NODE_CONFIG_NOT_FOUND);
        }
        FaqExtractorNodeConfig nodeConfigObj = JsonUtil.fromJson(objectConfig, FaqExtractorNodeConfig.class);
        if (null == nodeConfigObj || StringUtils.isBlank(nodeConfigObj.getModelName())) {
            log.warn("FAQ extractor node configuration not found");
            throw new BaseException(A_WF_NODE_CONFIG_ERROR);
        }
        log.info("FaqExtractorNode config:{}", nodeConfigObj);
        if (state.getInputs().isEmpty()) {
            log.warn("FaqExtractorNode inputs is empty");
            return new NodeProcessResult();
        }
        String userInput = getFirstInputText();
        User user = wfState.getUser();
        String effectiveLocale = StringUtils.isNotBlank(user.getLocale())
                ? user.getLocale()
                : Objects.toString(SysConfigService.getByKey(ZhiMeshConstant.SysConfigKey.DEFAULT_LOCALE), "zh-CN");
        String prompt = (effectiveLocale != null && effectiveLocale.startsWith("zh"))
                ? FaqExtractorPrompt.getPrompt(nodeConfigObj.getTopN(), userInput)
                : FaqExtractorPrompt.getPromptEn(nodeConfigObj.getTopN(), userInput);
        List<ChatMessage> llmMessages = new ArrayList<>();
        llmMessages.add(UserMessage.from(prompt));
        log.info("FAQ extractor prompt prepared, nodeUuid:{},promptChars:{}", state.getUuid(), prompt.length());

        //调用LLM
        WorkflowUtil.streamingInvokeLLM(wfState, state, node, nodeConfigObj.getModelPlatform(), nodeConfigObj.getModelName(), llmMessages);
        return new NodeProcessResult();
    }
}
