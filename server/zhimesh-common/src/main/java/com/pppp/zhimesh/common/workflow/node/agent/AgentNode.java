package com.pppp.zhimesh.common.workflow.node.agent;

import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.service.AgentService;
import com.pppp.zhimesh.common.service.LocalAgentService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.AgentRequest;
import com.pppp.zhimesh.common.vo.AgentResult;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.WorkflowUtil;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.metrics.AgentMetrics;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

/**
 * Workflow node that executes a Character in agent mode.
 *
 * <p>Invokes a Character with its full capabilities:
 * system prompt, knowledge base RAG, MCP tools, memory, web search.</p>
 *
 * <p>Node config format: {@link AgentNodeConfig}</p>
 */
@Slf4j
public class AgentNode extends AbstractWfNode {

    private final AgentService agentService;

    public AgentNode(WorkflowComponent wfComponent, WorkflowNode nodeDef,
                     WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        this.agentService = SpringUtil.getBean(AgentService.class);
        state.setMetrics(new AgentMetrics());
    }

    /**
     * nodeConfig 格式：
     * <pre>{@code
     * {
     *   "character_uuid": "xxx",
     *   "conversation_uuid": "xxx",    // 可选；为空时工作流保持无状态
     *   "model_platform": "deepseek",     // 可选
     *   "model_name": "deepseek-chat",     // 可选
     *   "prompt": "总结：{input}",          // 可选
     *   "enable_rag": true,
     *   "enable_mcp": true,
     *   "enable_web_search": false
     * }
     * }</pre>
     */
    @Override
    public NodeProcessResult onProcess() {
        AgentNodeConfig config = checkAndGetConfig(AgentNodeConfig.class);
        String inputText = getFirstInputText();

        //渲染 prompt 模板（同 LLMAnswerNode 模式）
        String prompt = inputText;
        if (StringUtils.isNotBlank(config.getPrompt())) {
            prompt = WorkflowUtil.renderTemplate(config.getPrompt(), state.getInputs());
        }

        log.info("Agent node invoking character:{}, input length:{}", config.getCharacterUuid(), prompt.length());

        //构建通用请求
        AgentRequest request = AgentRequest.builder()
                .characterUuid(config.getCharacterUuid())
                .conversationUuid(config.getConversationUuid())
                .modelPlatform(config.getModelPlatform())
                .modelName(config.getModelName())
                .inputText(prompt)
                .enableRag(Boolean.TRUE.equals(config.getEnableRag()))
                .enableMcp(!Boolean.FALSE.equals(config.getEnableMcp()))
                .enableWebSearch(Boolean.TRUE.equals(config.getEnableWebSearch()))
                .build();

        //调用 Agent
        AgentResult result = agentService.invoke(request, wfState.getUser(), wfState.getUuid());

        //记录指标
        AgentMetrics metrics = (AgentMetrics) state.getMetrics();
        if (result.getInputTokens() != null) {
            metrics.setInputTokens(result.getInputTokens());
        }
        if (result.getOutputTokens() != null) {
            metrics.setOutputTokens(result.getOutputTokens());
        }
        metrics.setCharacterUuid(config.getCharacterUuid());
        if (result.getRetrievalCount() != null) {
            metrics.setRetrievalCount(result.getRetrievalCount());
        }
        if (config.getModelName() != null) {
            metrics.setModelName(config.getModelName());
        }
        if (config.getModelPlatform() != null) {
            metrics.setModelPlatform(config.getModelPlatform());
        }

        return NodeProcessResult.builder()
                .content(List.of(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", result.getAnswer())))
                .build();
    }
}
