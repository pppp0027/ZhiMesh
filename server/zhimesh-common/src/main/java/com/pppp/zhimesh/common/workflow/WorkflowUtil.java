package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.entity.LLMCallRecord;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.service.LLMCallRecordService;
import com.pppp.zhimesh.common.util.LLMTokenUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
import com.pppp.zhimesh.common.vo.SseAskParam;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.data.NodeIODataContent;
import com.pppp.zhimesh.common.workflow.metrics.LLMMetrics;
import com.pppp.zhimesh.common.workflow.node.humanfeedback.HumanFeedbackNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bsc.langgraph4j.langchain4j.generators.StreamingChatGenerator;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

@Slf4j
public class WorkflowUtil {

    public static String renderTemplate(String template, List<NodeIOData> values) {
        String result = StringUtils.defaultString(template);
        if (values == null || values.isEmpty()) {
            return result;
        }
        for (NodeIOData next : values) {
            if (next == null || StringUtils.isBlank(next.getName()) || next.getContent() == null) {
                continue;
            }
            String name = next.getName();
            NodeIODataContent<?> dataContent = next.getContent();
            if (dataContent.getType().equals(WfIODataTypeEnum.FILES.getValue())) {
                Object rawValue = dataContent.getValue();
                String value = rawValue instanceof List<?> files
                        ? files.stream().filter(Objects::nonNull).map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
                        : StringUtils.defaultString(Objects.toString(rawValue, null));
                result = result.replace("{" + name + "}", value);
            } else if (dataContent.getType().equals(WfIODataTypeEnum.OPTIONS.getValue())) {
                Object rawValue = dataContent.getValue();
                String value = rawValue instanceof Map<?, ?> ? rawValue.toString() : StringUtils.defaultString(Objects.toString(rawValue, null));
                result = result.replace("{" + name + "}", value);
            } else {
                result = result.replace("{" + name + "}", StringUtils.defaultString(Objects.toString(dataContent.getValue(), null)));
            }
        }
        return result;
    }

    public static void streamingInvokeLLM(WfState wfState, WfNodeState state, WorkflowNode node, String modelPlatform, String modelName, List<ChatMessage> msgs) {
        streamingInvokeLLM(wfState, state, node, modelPlatform, modelName, msgs, null);
    }

    public static void streamingInvokeLLM(WfState wfState, WfNodeState state, WorkflowNode node,
                                          String modelPlatform, String modelName, List<ChatMessage> msgs,
                                          Function<String, List<NodeIOData>> additionalOutputFactory) {
        log.info("stream invoke");
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(modelPlatform, modelName);
        StreamingChatGenerator<AgentState> streamingGenerator = StreamingChatGenerator.builder()
                .mapResult(response -> {
                    String responseTxt = response.aiMessage().text();
                    log.info("Workflow streaming LLM completed, nodeUuid:{},responseChars:{}",
                            node.getUuid(), StringUtils.length(responseTxt));
                    TokenUsage tokenUsage = response.metadata().tokenUsage();
                    LLMTokenUtil.cacheTokenUsage(llmService.getStringRedisTemplate(), wfState.getUuid(), tokenUsage);
                    //记录节点级别的 token 消耗 | Record node-level token usage
                    if (state.getMetrics() instanceof LLMMetrics nodeMetrics) {
                        if (tokenUsage != null) {
                            nodeMetrics.setInputTokens(tokenUsage.inputTokenCount());
                            nodeMetrics.setOutputTokens(tokenUsage.outputTokenCount());
                        }
                        nodeMetrics.setModelName(modelName);
                        nodeMetrics.setModelPlatform(modelPlatform);
                    } else {
                        log.warn("streamingInvokeLLM: metrics is not LLMMetrics, skipping token recording for node {}", node.getUuid());
                    }
                    //Save LLM call record
                    saveLLMCallRecord(wfState, node, modelPlatform, modelName, tokenUsage);
                    NodeIOData output = NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", responseTxt);
                    wfState.getNodeStateByNodeUuid(node.getUuid()).ifPresent(item -> {
                        item.getOutputs().add(output);
                        if (additionalOutputFactory != null) {
                            try {
                                List<NodeIOData> additionalOutputs = additionalOutputFactory.apply(responseTxt);
                                if (additionalOutputs != null && !additionalOutputs.isEmpty()) {
                                    item.getOutputs().addAll(additionalOutputs);
                                }
                            } catch (Exception extractionError) {
                                // Structured extraction is an enhancement of the full answer. A malformed
                                // optional field must not discard an otherwise valid LLM response.
                                log.warn("Unable to extract structured LLM outputs, nodeUuid:{}", node.getUuid(), extractionError);
                            }
                        }
                    });
                    return Map.of("completeResult", response.aiMessage().text());
                })
                .startingNode(node.getUuid())
                .startingState(state)
                .build();
        StreamingChatModel streamingLLM = llmService.buildStreamingChatModel(
                ChatModelBuilderProperties
                        .builder()
                        .build()
        );
        ChatRequest request = ChatRequest.builder()
                .messages(msgs)
                .build();
        streamingLLM.chat(request, streamingGenerator.handler());
        wfState.getNodeToStreamingGenerator().put(node.getUuid(), streamingGenerator);
//LLM returned chunks are stored in blocking queue, not processed here, handled by WorkflowEngine
        //LLM返回的chunk存放在阻塞队列中，此处不做处理，交由WorkflowEngine统一处理
//            for (StreamingOutput<AgentState> r : streamingGenerator) {
//                log.info("chunk:{}", r);
//            }
//            Optional<Object> resultValue = streamingGenerator.resultValue();
//            if (resultValue.isPresent()) {
//                Map<String, String> resultMap = (Map<String, String>) resultValue.get();
//                WfNodeIODataText output = new WfNodeIODataText(DEFAULT_OUTPUT_PARAM_NAME, resultMap.get("completeResult"));
//                return List.of(output);
//            }
    }

    public static NodeIOData invokeLLM(WfState wfState, WfNodeState nodeState, String modelPlatform, String modelName, String prompt) {
        log.info("common invoke");
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(modelPlatform, modelName);
        SseAskParam sseAskParam = new SseAskParam();
        sseAskParam.setUuid(wfState.getUuid());
        sseAskParam.setHttpRequestParams(ChatModelRequest.builder().systemMessage(StringUtils.EMPTY).userMessage(prompt).build());
        sseAskParam.setModelName(llmService.getAiModel().getName());
        sseAskParam.setUser(wfState.getUser());
        ChatResponse response = llmService.chat(sseAskParam);
        log.info("Workflow blocking LLM completed, workflowUuid:{},responseChars:{}",
                wfState.getUuid(), StringUtils.length(response.aiMessage().text()));
        //记录节点级别的 token 消耗 | Record node-level token usage
        if (nodeState != null && nodeState.getMetrics() instanceof LLMMetrics nodeMetrics && response.metadata() != null) {
            TokenUsage tokenUsage = response.metadata().tokenUsage();
            if (tokenUsage != null) {
                nodeMetrics.setInputTokens(tokenUsage.inputTokenCount());
                nodeMetrics.setOutputTokens(tokenUsage.outputTokenCount());
            }
            nodeMetrics.setModelName(modelName);
            nodeMetrics.setModelPlatform(modelPlatform);
            //Save LLM call record
            saveLLMCallRecord(wfState, null, modelPlatform, modelName, tokenUsage);
        }
        return NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", response.aiMessage().text());
    }

    public static String getHumanFeedbackTip(String nodeUuid, List<WorkflowNode> wfNodes) {
        WorkflowNode wfNode = wfNodes.stream().filter(item -> item.getUuid().equals(nodeUuid)).findFirst().orElse(null);
        if (null == wfNode) {
            return "";
        }
        return HumanFeedbackNode.getTip(wfNode);
    }

    /**
     * 异步保存 LLM 调用记录 | Save LLM call record asynchronously
     */
    private static void saveLLMCallRecord(WfState wfState, WorkflowNode node, String modelPlatform, String modelName, TokenUsage tokenUsage) {
        try {
            Long sourceId = node != null ? node.getId() : 0L;
            LLMCallRecord record = new LLMCallRecord();
            record.setUuid(UuidUtil.createShort());
            record.setSourceType(LLMCallRecordSourceType.WORKFLOW_NODE.getValue());
            record.setSourceId(sourceId);
            record.setUserId(wfState.getUser().getId());
            record.setModelPlatform(modelPlatform);
            record.setModelName(modelName);
            if (tokenUsage != null) {
                record.setInputTokens(tokenUsage.inputTokenCount());
                record.setOutputTokens(tokenUsage.outputTokenCount());
            }
        SpringUtil.getBean(LLMCallRecordService.class).saveRecord(record);
        } catch (Exception e) {
            log.error("Failed to save LLM call record for workflow node", e);
        }
    }
}
