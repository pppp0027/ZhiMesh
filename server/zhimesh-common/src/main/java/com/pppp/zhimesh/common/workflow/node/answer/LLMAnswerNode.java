package com.pppp.zhimesh.common.workflow.node.answer;

import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.WorkflowUtil;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.metrics.LLMMetrics;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【节点】LLM生成回答 <br/>
 * 节点内容固定格式：LLMAnswerNodeConfig
 */
@Slf4j
public class LLMAnswerNode extends AbstractWfNode {

    public static final String SCORE_OUTPUT_PARAM_NAME = "score";
    public static final String ROUTE_OUTPUT_PARAM_NAME = "route";

    private static final Pattern SCORE_MARKER_PATTERN = Pattern.compile(
            "(?im)^\\s*\\[(?:DEMO_)?SCORE]\\s*[:：]?\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern SCORE_JSON_PATTERN = Pattern.compile(
            "(?i)\\\"(?:demo_match_score|match_score|score)\\\"\\s*:\\s*\\\"?(-?\\d+(?:\\.\\d+)?)\\\"?");
    private static final Pattern PURE_NUMBER_PATTERN = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern ROUTE_MARKER_PATTERN = Pattern.compile(
            "(?im)^\\s*\\[(?:DEMO_)?ROUTE]\\s*[:：]?\\s*([A-Za-z0-9_-]+)\\s*$");
    private static final Pattern ROUTE_JSON_PATTERN = Pattern.compile(
            "(?i)\\\"(?:demo_route|route)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    public LLMAnswerNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        state.setMetrics(new LLMMetrics());
    }

    /**
     * nodeConfig格式：<br/>
     * {"prompt": "将以下内容翻译成英文：{input}","model_platform":"deepseek","model_name":"deepseek-chat"}<br/>
     *
     * @return LLM的返回内容
     */
    @Override
    public NodeProcessResult onProcess() {
        LLMAnswerNodeConfig nodeConfigObj = checkAndGetConfig(LLMAnswerNodeConfig.class);
        log.info("LLM answer node started, nodeUuid:{},modelPlatform:{},modelName:{}",
                state.getUuid(), nodeConfigObj.getModelPlatform(), nodeConfigObj.getModelName());
        String prompt;
        if (StringUtils.isNotBlank(nodeConfigObj.getPrompt())) {
            prompt = WorkflowUtil.renderTemplate(nodeConfigObj.getPrompt(), state.getInputs());
        } else {
            prompt = getFirstInputText();
        }
        if (StringUtils.isBlank(prompt)) {
            throw new BaseException("A0038", "生成回答节点缺少提示词或上游输入内容");
        }
        log.info("LLM answer node prompt prepared, nodeUuid:{},promptChars:{}", state.getUuid(), prompt.length());
        String modelName = nodeConfigObj.getModelName();
        //调用LLM
        WorkflowUtil.streamingInvokeLLM(
                wfState,
                state,
                node,
                nodeConfigObj.getModelPlatform(),
                modelName,
                List.of(UserMessage.from(prompt)),
                this::extractStructuredOutputs
        );
        return new NodeProcessResult();
    }

    private List<com.pppp.zhimesh.common.workflow.data.NodeIOData> extractStructuredOutputs(String responseText) {
        List<com.pppp.zhimesh.common.workflow.data.NodeIOData> outputs = new ArrayList<>();
        extractScore(responseText).ifPresent(score -> outputs.add(
                com.pppp.zhimesh.common.workflow.data.NodeIOData.createByNumber(
                        SCORE_OUTPUT_PARAM_NAME, "匹配分数", score)));
        extractRoute(responseText).ifPresent(route -> outputs.add(
                com.pppp.zhimesh.common.workflow.data.NodeIOData.createByText(
                        ROUTE_OUTPUT_PARAM_NAME, "路由标记", route)));
        return outputs;
    }

    private Optional<Double> extractScore(String responseText) {
        for (Pattern pattern : List.of(SCORE_MARKER_PATTERN, SCORE_JSON_PATTERN, PURE_NUMBER_PATTERN)) {
            Matcher matcher = pattern.matcher(StringUtils.defaultString(responseText));
            if (matcher.find()) {
                try {
                    return Optional.of(Double.parseDouble(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Continue with the next supported representation.
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractRoute(String responseText) {
        for (Pattern pattern : List.of(ROUTE_MARKER_PATTERN, ROUTE_JSON_PATTERN)) {
            Matcher matcher = pattern.matcher(StringUtils.defaultString(responseText));
            if (matcher.find() && StringUtils.isNotBlank(matcher.group(1))) {
                return Optional.of(matcher.group(1).trim());
            }
        }
        return Optional.empty();
    }
}
