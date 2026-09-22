package com.pppp.zhimesh.common.languagemodel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.helper.TtsModelContext;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.McpToolExecutor;
import com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTokenBudget;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryWindow;
import com.pppp.zhimesh.common.rag.TokenEstimatorFactory;
import com.pppp.zhimesh.common.rag.TokenEstimatorThreadLocal;
import com.pppp.zhimesh.common.service.ModelHealthService;
import com.pppp.zhimesh.common.util.*;
import com.pppp.zhimesh.common.vo.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.CustomChatRequestParameterKeys.ENABLE_WEB_SEARCH;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.CustomChatRequestParameterKeys.ENABLE_THINKING;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.LLM_MAX_INPUT_TOKENS_DEFAULT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.RESPONSE_FORMAT_TYPE_JSON_OBJECT;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.B_LLM_SERVICE_DISABLED;

@Slf4j
public abstract class AbstractLLMService extends CommonModelService {

    protected StringRedisTemplate stringRedisTemplate;

    //User#uuid => ttsJobInfo
    private final Cache<String, TtsJobInfo> ttsJobCache;

    /**
     * 内置工具超时执行线程池：守护线程避免阻塞 JVM 退出，仅在有内置工具执行时按需创建线程
     * <p>
     * Pool enforcing timeouts for builtin tool execution: daemon threads avoid
     * blocking JVM shutdown, and threads are created on demand only.
     */
    private static final ExecutorService TOOL_EXECUTION_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "builtin-tool-executor");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 工具循环达上限后注入的收尾指令：告知模型不得再调用工具，必须基于已获取的
     * 信息直接作答（design 验收标准 3 的优雅收尾）
     * <p>
     * Wrap-up instruction injected once the tool loop hits its cap: the model
     * must stop calling tools and answer directly from what it already gathered
     * (the graceful exhaustion of design acceptance criterion 3).
     */
    static final String TOOL_LIMIT_REACHED_INSTRUCTION =
            "[系统提示] 工具调用次数已达上限，请不要再调用任何工具，直接基于以上已获取的信息生成最终回答。";

    /**
     * 构造与 original 参数等价但不携带任何工具规格的 ChatRequestParameters。
     * LC4J 的 {@code overrideWith} 对"空集合"按未设置处理（探针实测空 list 不会
     * 覆盖已有工具），因此这里显式逐字段拷贝、唯独不设 toolSpecifications。
     * <p>
     * Build a ChatRequestParameters equivalent to {@code original} but carrying no
     * tool specifications. LC4J's {@code overrideWith} treats an empty collection
     * as unset (probed: an empty list does not override existing tools), so every
     * field is copied explicitly except toolSpecifications.
     */
    static ChatRequestParameters withoutToolSpecifications(ChatRequestParameters original) {
        if (null == original) {
            return null;
        }
        return ChatRequestParameters.builder()
                .modelName(original.modelName())
                .temperature(original.temperature())
                .topP(original.topP())
                .topK(original.topK())
                .frequencyPenalty(original.frequencyPenalty())
                .presencePenalty(original.presencePenalty())
                .maxOutputTokens(original.maxOutputTokens())
                .stopSequences(original.stopSequences())
                .responseFormat(original.responseFormat())
                .build();
    }

    /**
     * 循环截断在前端步骤条上的标注事件名：非真实工具，仅用于向用户标明本轮被上限截断
     * <p>
     * Event name marking loop truncation on the frontend step bar: not a real
     * tool, it only tells the user the round was cut short by the cap.
     */
    static final String TOOL_LIMIT_MARKER_NAME = "loop_limit_reached";

    @Getter
    private final TtsSetting ttsSetting;

    protected AbstractLLMService(AiModel aiModel, ModelPlatform modelPlatform) {
        super(aiModel, modelPlatform);

        initMaxInputTokens();
        ttsSetting = JsonUtil.fromJson(LocalCache.CONFIGS.get(ZhiMeshConstant.SysConfigKey.TTS_SETTING), TtsSetting.class);
        if (null == ttsSetting) {
            log.error("TTS configuration not found, please check adi_sys_config for tts_setting entry");
            throw new BaseException(ErrorEnum.B_TTS_SETTING_NOT_FOUND);
        }

        ttsJobCache = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();
    }

    private void initMaxInputTokens() {
        if (this.aiModel.getMaxInputTokens() < 1) {
            this.aiModel.setMaxInputTokens(LLM_MAX_INPUT_TOKENS_DEFAULT);
        }
    }

    public StringRedisTemplate getStringRedisTemplate() {
        if (null == this.stringRedisTemplate) {
            this.stringRedisTemplate = SpringUtil.getBean(StringRedisTemplate.class);
        }
        return this.stringRedisTemplate;
    }

    public AbstractLLMService setProxyAddress(InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
        return this;
    }

    /**
     * 检测该service是否可用（不可用的情况通常是没有配置key）
     * Check if this service is enabled (usually disabled when API key is not configured)
     *
     * @return
     */
    public abstract boolean isEnabled();

    /** Whether this provider adapter actually forwards the web-search flag. */
    public boolean supportsWebSearch() {
        return false;
    }

    protected boolean checkBeforeChat(SseAskParam params) {
        return true;
    }

    public ChatModel buildChatLLM(ChatModelBuilderProperties properties) {
        ChatModelBuilderProperties tmpProperties = properties;
        if (null == properties) {
            tmpProperties = new ChatModelBuilderProperties();
            tmpProperties.setTemperature(0.7);
            log.info("llmBuilderProperties is null, set default temperature:{}", tmpProperties.getTemperature());
        }
        if (null == tmpProperties.getTemperature() || tmpProperties.getTemperature() <= 0 || tmpProperties.getTemperature() > 1) {
            tmpProperties.setTemperature(0.7);
            log.info("llmBuilderProperties temperature is invalid, set default temperature:{}", tmpProperties.getTemperature());
        }
        return doBuildChatModel(tmpProperties);
    }

    /**
     * Record a user-facing LLM invocation failure so that the model can be
     * marked UNHEALTHY after consecutive failures.
     */
    private void recordInvocationFailure(Throwable error) {
        try {
            ModelHealthService healthService = SpringUtil.getBean(ModelHealthService.class);
            String reason = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
            healthService.recordFailure(platform.getName(), aiModel.getName(), reason);
        } catch (Exception ignored) {
            // ModelHealthService may not be available (e.g. during startup)
        }
    }

    /**
     * Clear stale health failures as soon as a real invocation succeeds.
     */
    private void recordInvocationSuccess() {
        try {
            SpringUtil.getBean(ModelHealthService.class).recordSuccess(platform.getName(), aiModel.getName());
        } catch (Exception ignored) {
            // ModelHealthService may not be available (e.g. during startup)
        }
    }

    protected abstract ChatModel doBuildChatModel(ChatModelBuilderProperties properties);

    public abstract StreamingChatModel buildStreamingChatModel(ChatModelBuilderProperties properties);

    protected abstract LLMException parseError(Object error);

    public abstract TokenCountEstimator getTokenEstimator();

    /**
     * 普通聊天，将原始的用户问题及历史消息发送给AI
     * Normal chat, send raw user question and history messages to AI
     *
     * @param params   请求参数 / Request parameters
     * @param consumer 响应结果回调 / Response result callback
     */
    public void streamingChat(SseAskParam params, TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> consumer) {
        if (!isEnabled()) {
            log.error("llm service is disabled");
            throw new BaseException(B_LLM_SERVICE_DISABLED);
        }
        if (!checkBeforeChat(params)) {
            log.error("Chat parameter validation failed");
            throw new BaseException(A_PARAMS_ERROR);
        }
        ChatModelRequest httpRequestParams = params.getHttpRequestParams();
        ChatModelBuilderProperties modelProperties = params.getModelProperties();
        log.info("sseChat,messageId:{}", httpRequestParams.getMemoryId());

        Map<String, ToolExecutor> requestTools = discoverRequestTools(httpRequestParams);
        ChatRequest chatRequest = createChatRequest(httpRequestParams, toolSpecifications(requestTools));
        StreamingChatModel streamingChatModel = buildStreamingChatModel(modelProperties);
        InnerStreamChatParam innerStreamChatParam = InnerStreamChatParam.builder()
                .uuid(params.getUuid())
                .user(params.getUser())
                .streamingChatModel(streamingChatModel)
                .chatRequest(chatRequest)
                .sseUuid(params.getSseUuid())
                .mcpClients(httpRequestParams.getMcpClients())
                .toolExecutorMap(requestTools)
                // 聊天入口带入的请求级上下文优先（检索接线/轨迹收集贯通工具循环）；为空时自建，存量调用方零变化
                // A request context passed in by the chat entry wins (retrieval wiring
                // / trace collectors flow through the loop); build one when absent so
                // existing callers are unaffected
                .toolContext(null != params.getToolContext()
                        ? params.getToolContext()
                        : createToolContext(params.getUser(), httpRequestParams))
                .answerContentType(params.getAnswerContentType())
                .consumer(consumer)
                .build();
        try {

//Day
            //如果系统设置的语音合成器类型是后端合成，并且当前聊天设置的返回内容是音频，则初始化tts任务并注册回调函数
            // If the system TTS synthesizer is server-side and the current chat is set to return audio, initialize TTS job and register callback
            // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面 if 块的注释
            /*
            if (TtsUtil.needTts(ttsSetting, params.getAnswerContentType())) {
                String ttsJobId = UuidUtil.createShort();
                TtsJobInfo jobInfo = new TtsJobInfo();
                TtsModelContext ttsModelContext = new TtsModelContext();
                jobInfo.setJobId(ttsJobId);
                jobInfo.setTtsModelContext(ttsModelContext);
                ttsJobCache.put(params.getUser().getUuid(), jobInfo);
                ttsModelContext.startTtsJob(ttsJobId, params.getVoice(), (ByteBuffer audioFrame) -> {
                    byte[] frameBytes = new byte[audioFrame.remaining()];
                    audioFrame.get(frameBytes);
                    String base64Audio = Base64.getEncoder().encodeToString(frameBytes);
                    SseManager.sendAudio(params.getSseUuid(), base64Audio);
                }, jobInfo::setFilePath, (String errorMsg) -> log.error("tts error: {}", errorMsg));
            }
            */

            //不管是不是需要返回音频文件，都需要innerStreamingChat()
            // Regardless of whether an audio file needs to be returned, innerStreamingChat() is always needed
            innerStreamingChat(innerStreamChatParam);
        } catch (Exception e) {
            // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面一行的注释
            // ttsJobCache.invalidate(params.getUser().getUuid());
            if (params.getHttpRequestParams() != null) {
                closeMcpClients(params.getHttpRequestParams().getMcpClients());
            }
            throw e;
        }

    }

    /**
     * 内部流式聊天方法，处理工具调用等复杂逻辑
     * Internal streaming chat method, handling complex logic such as tool calls
     *
     * @param params 参数对象，包含流式聊天所需的所有信息 / Parameter object containing all info needed for streaming chat
     */
    private void innerStreamingChat(InnerStreamChatParam params) {
        int maxToolIterations = resolveAgentSettings().getMaxToolIterations();
        if (params.getToolCallDepth() >= maxToolIterations && !params.isToollessFinalRound()) {
            // 达到迭代上限的优雅收尾：剥掉工具规格并追加指令，让模型基于已获取的信息
            // 直接作答，而不是立刻向客户端发 [ERROR]；前端步骤条同时收到截断标注事件
            // Graceful exhaustion at the iteration cap: strip tool specifications and
            // append an instruction so the model answers directly from what it already
            // gathered instead of an immediate client-facing [ERROR]; the frontend step
            // bar receives a truncation-marker event alongside
            log.warn("Tool call depth reached {} — forcing one tool-less wrap-up round", maxToolIterations);
            SseManager.sendToolCall(params.getSseUuid(), TOOL_LIMIT_MARKER_NAME, 0L, false, null,
                    "已达到工具调用上限，基于已获取的信息直接作答");
            // 截断标注同步落进轨迹：META 合并与历史回放（character_message_tool_call）
            // 都要能看到本轮被上限截断，否则刷新后前端步骤条丢失这条标注；
            // 无 ToolContext 的入口只走 SSE，不影响收尾
            // Record the truncation marker as a trace too: META merge and history
            // replay (character_message_tool_call) must both show the round was cut
            // short by the cap, or the frontend step bar loses the marker after a
            // refresh; entries without a ToolContext keep the SSE event only
            ToolContext markerContext = params.getToolContext();
            if (null != markerContext && null != markerContext.getToolTraces()) {
                List<ToolCallTrace> traces = markerContext.getToolTraces();
                traces.add(ToolCallTrace.builder()
                        .seq(traces.size())
                        .toolName(TOOL_LIMIT_MARKER_NAME)
                        .resultSummary("已达到工具调用上限，基于已获取的信息直接作答")
                        .durationMs(0L)
                        .success(false)
                        .build());
            }
            ChatRequest current = params.getChatRequest();
            List<ChatMessage> wrapUpMessages = new ArrayList<>(current.messages());
            wrapUpMessages.add(UserMessage.from(TOOL_LIMIT_REACHED_INSTRUCTION));
            params.setChatRequest(ChatRequest.builder()
                    .messages(wrapUpMessages)
                    .parameters(withoutToolSpecifications(current.parameters()))
                    .build());
            params.setToollessFinalRound(true);
            innerStreamingChat(params);
            return;
        }
        Map<String, ToolExecutor> toolExecutorMap = params.getToolExecutorMap();
        params.getStreamingChatModel().chat(params.getChatRequest(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                SseManager.parseAndSendPartialMsg(params.getSseUuid(), partialResponse);
                // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面一行的注释
                // ttsOnPartialMessage(params, partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                SseManager.sendThinking(params.getSseUuid(), partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                AiMessage responseAiMessage = response.aiMessage();
                if (responseAiMessage.hasToolExecutionRequests()) {
                    if (params.isToollessFinalRound()) {
                        // 收尾轮（请求已无工具规格）仍产生工具请求：提供商级异常，按错误终止，绝不无限循环
                        // The wrap-up round (whose request carries no tool specs)
                        // still produced tool requests: a provider-level anomaly,
                        // terminate as an error and never loop
                        log.error("Tool-less wrap-up round still requested tools, terminating execution");
                        SseManager.errorAndShutdown(
                                new RuntimeException("Tool call count exceeded limit"), params.getSseUuid());
                        closeMcpClients(params.getMcpClients());
                        return;
                    }
                    // 如果有工具执行请求
                    // If there are tool execution requests
                    // 中间工具轮的 token 也必须入账，否则该轮消耗被漏记导致配额少扣；
                    // 最终轮仍由下方 calculateToken 统一入账，保证每轮恰好记录一次
                    // Intermediate tool rounds must also record tokens, otherwise that round is
                    // missed and the quota is under-charged; the final round is still recorded
                    // once by calculateToken below, keeping exactly one record per round
                    if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                        LLMTokenUtil.cacheTokenUsage(getStringRedisTemplate(), params.getUuid(),
                                response.metadata().tokenUsage());
                    }
                    List<ToolExecutionResultMessage> toolExecutionMessages = createToolExecutionMessages(responseAiMessage, toolExecutorMap, params.getToolContext(), params.getSseUuid());

                    //mcp调用消息格式参考：https://docs.langchain4j.dev/tutorials/tools/
                    AiMessage aiMessage = AiMessage.aiMessage(responseAiMessage.toolExecutionRequests());
                    List<ChatMessage> messages = new ArrayList<>(params.getChatRequest().messages());
                    messages.add(aiMessage);
                    messages.addAll(toolExecutionMessages);
                    params.setChatRequest(ChatRequest.builder()
                            .messages(messages)
                            .parameters(params.getChatRequest().parameters())
                            .build());
                    params.setToolCallDepth(params.getToolCallDepth() + 1);
                    // recursive call now with tool calling results
                    innerStreamingChat(params);
                } else {
                    recordInvocationSuccess();
                    // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面两行的注释
                    // TtsJobInfo jobInfo = ttsOnComplete(params);
                    // String filePath = null != jobInfo ? jobInfo.getFilePath() : null;
                    String filePath = null; // 语音输出已停用：始终无音频文件
                    //结束整个对话任务
                    Pair<PromptMeta, AnswerMeta> pair = SseManager.calculateToken(response, params.getUuid());
                    params.getConsumer().accept(new LLMResponseContent(response.aiMessage().thinking(), response.aiMessage().text(), filePath), pair.getLeft(), pair.getRight());
                    closeMcpClients(params.getMcpClients());
                }
            }

            @Override
            public void onError(Throwable error) {
                recordInvocationFailure(error);
                SseManager.errorAndShutdown(error, params.getSseUuid());
                closeMcpClients(params.getMcpClients());
            }
        });
    }

    public ChatResponse chat(SseAskParam params) {
        if (!isEnabled()) {
            log.error("llm service is disabled");
            throw new BaseException(B_LLM_SERVICE_DISABLED);
        }
        if (!checkBeforeChat(params)) {
            log.error("Chat parameter validation failed");
            throw new BaseException(A_PARAMS_ERROR);
        }

        ChatModelRequest chatModelRequest = params.getHttpRequestParams();
        ChatModelBuilderProperties modelProperties = params.getModelProperties();
        ChatModel chatModel = buildChatLLM(modelProperties);
        ChatRequest chatRequest = createChatRequest(chatModelRequest);

        try {
            ChatResponse chatResponse = chatModel.chat(chatRequest);
            if (chatResponse.aiMessage().hasToolExecutionRequests()) {
                ChatResponse finalResponse = innerChat(params.getUuid(), chatModel, chatModelRequest, chatRequest,
                        createToolContext(params.getUser(), chatModelRequest));
                recordInvocationSuccess();
                return finalResponse;
            }

            cacheTokenUsage(params.getUuid(), chatResponse);
            recordInvocationSuccess();
            return chatResponse;
        } catch (Exception e) {
            recordInvocationFailure(e);
            throw e;
        }
    }

    /**
     * 程序内部调用的聊天方法，通常用于处理工具调用等复杂逻辑
     * Internal chat method, typically used for handling complex logic such as tool calls
     *
     * @param uuid                   唯一标识 / Unique identifier
     * @param chatModel              聊天模型 / Chat model
     * @param chatModelRequest 聊天模型参数 / Chat model parameters
     * @param chatRequest            聊天请求 / Chat request
     * @param toolContext            请求级工具上下文 / Request-scoped tool context
     * @return ChatResponse 聊天响应 / Chat response
     */
    private ChatResponse innerChat(String uuid, ChatModel chatModel, ChatModelRequest chatModelRequest, ChatRequest chatRequest, ToolContext toolContext) {
        return innerChatWithDepth(uuid, chatModel, chatModelRequest, chatRequest, toolContext, 0);
    }

    private ChatResponse innerChatWithDepth(String uuid, ChatModel chatModel, ChatModelRequest chatModelRequest, ChatRequest chatRequest, ToolContext toolContext, int depth) {
        int maxToolIterations = resolveAgentSettings().getMaxToolIterations();
        try {
            if (depth >= maxToolIterations) {
                // 达到迭代上限的优雅收尾，镜像流式路径 innerStreamingChat 的收尾轮：不再抛
                // B_LLM_SERVICE_DISABLED（"迭代上限"被误报成"服务禁用"的语义错位），而是剥掉
                // 工具规格并追加指令，让模型基于已获取的信息直接作答
                // Graceful exhaustion at the iteration cap, mirroring the wrap-up round
                // of the streaming path innerStreamingChat: instead of throwing
                // B_LLM_SERVICE_DISABLED (the mismatch of reporting an iteration cap
                // as a disabled service), strip tool specifications and append an
                // instruction so the model answers directly from what it gathered
                log.warn("Tool call depth reached {} — forcing one tool-less wrap-up round", maxToolIterations);
                // 阻塞路径没有 SSE 通道（与 createToolExecutionMessages 一致拿不到 sseUuid，
                // SseManager.sendToolCall 对 null uuid 自行短路），因此不发前端截断标注事件，
                // 截断标注只落进轨迹供 META 合并与历史回放使用
                // The blocking path has no SSE channel (no sseUuid is available, same
                // as in createToolExecutionMessages; SseManager.sendToolCall short-circuits
                // on a null uuid), so no frontend truncation event is emitted; the marker
                // goes into the trace only, for META merge and history replay
                if (null != toolContext && null != toolContext.getToolTraces()) {
                    List<ToolCallTrace> traces = toolContext.getToolTraces();
                    traces.add(ToolCallTrace.builder()
                            .seq(traces.size())
                            .toolName(TOOL_LIMIT_MARKER_NAME)
                            .resultSummary("已达到工具调用上限，基于已获取的信息直接作答")
                            .durationMs(0L)
                            .success(false)
                            .build());
                }
                List<ChatMessage> wrapUpMessages = new ArrayList<>(chatRequest.messages());
                wrapUpMessages.add(UserMessage.from(TOOL_LIMIT_REACHED_INSTRUCTION));
                ChatResponse wrapUpResponse = chatModel.chat(ChatRequest.builder()
                        .messages(wrapUpMessages)
                        .parameters(withoutToolSpecifications(chatRequest.parameters()))
                        .build());
                // 收尾轮是一次真实的模型调用，token 与其余各轮一样恰好记录一次
                // The wrap-up round is a real model call; its tokens are recorded
                // exactly once like every other round
                cacheTokenUsage(uuid, wrapUpResponse);
                if (wrapUpResponse.aiMessage().hasToolExecutionRequests()) {
                    // 收尾轮（请求已无工具规格）仍产生工具请求：提供商级异常，按错误终止，绝不无限循环
                    // The wrap-up round (whose request carries no tool specs) still
                    // produced tool requests: a provider-level anomaly, terminate as
                    // an error and never loop
                    log.error("Tool-less wrap-up round still requested tools, terminating execution");
                    throw new BaseException(ErrorEnum.B_TOOL_CALL_LIMIT_EXCEEDED);
                }
                return wrapUpResponse;
            }
            ChatResponse chatResponse = chatModel.chat(chatRequest);
            AiMessage responseAiMessage = chatResponse.aiMessage();
            if (responseAiMessage.hasToolExecutionRequests()) {
                Map<String, ToolExecutor> toolExecutorMap = discoverRequestTools(chatModelRequest);
                List<ToolExecutionResultMessage> toolExecutionMessages = createToolExecutionMessages(responseAiMessage, toolExecutorMap, toolContext, null);

                AiMessage aiMessage = AiMessage.aiMessage(responseAiMessage.toolExecutionRequests());
                List<ChatMessage> messages = new ArrayList<>(chatRequest.messages());
                messages.add(aiMessage);
                messages.addAll(toolExecutionMessages);

                cacheTokenUsage(uuid, chatResponse);

                return innerChatWithDepth(uuid, chatModel, chatModelRequest, ChatRequest.builder()
                        .messages(messages)
                        .parameters(chatRequest.parameters())
                        .build(), toolContext, depth + 1);
            }
            cacheTokenUsage(uuid, chatResponse);
            return chatResponse;
        } finally {
            if (depth == 0) {
                closeMcpClients(chatModelRequest.getMcpClients());
            }
        }
    }

    /**
     * 缓存token使用情况
     * Cache token usage
     *
     * @param uuid         唯一标识 / Unique identifier
     * @param chatResponse 聊天响应 / Chat response
     */
    private void cacheTokenUsage(String uuid, ChatResponse chatResponse) {
        int inputTokenCount = chatResponse.metadata().tokenUsage().inputTokenCount();
        int outputTokenCount = chatResponse.metadata().tokenUsage().outputTokenCount();
        log.info("ChatModel token cost,uuid:{},inputTokenCount:{},outputTokenCount:{}", uuid, inputTokenCount, outputTokenCount);
        LLMTokenUtil.cacheTokenUsage(getStringRedisTemplate(), uuid, chatResponse.metadata().tokenUsage());
    }


    private List<ChatMessage> createChatMessages(ChatModelRequest chatModelRequest,
                                                 Collection<ToolSpecification> toolSpecifications) {
        String memoryId = chatModelRequest.getMemoryId();
        List<Content> userContents = new ArrayList<>();
        userContents.add(TextContent.from(chatModelRequest.getUserMessage()));
        List<ChatMessage> chatMessages = new ArrayList<>();
        if (StringUtils.isNotBlank(memoryId)) {

            TokenCountEstimator tokenCountEstimator = resolveTokenCountEstimator();
            ShortTermMemoryTokenBudget tokenBudget = calculateShortTermMemoryBudget(
                    chatModelRequest, toolSpecifications, tokenCountEstimator);
            chatModelRequest.setMemoryWindowMaxTokens(tokenBudget.messageWindowTokens());

            //滑动窗口算法限制消息长度
            // Sliding window algorithm to limit message length
            ShortTermMemoryService shortTermMemoryService = SpringUtil.getBean(ShortTermMemoryService.class);
            TokenWindowChatMemory memory = ShortTermMemoryWindow.open(
                    shortTermMemoryService, memoryId,
                    tokenBudget.messageWindowTokens(), tokenCountEstimator);
            if (StringUtils.isNotBlank(chatModelRequest.getSystemMessage())) {
                memory.add(SystemMessage.from(chatModelRequest.getSystemMessage()));
            }

            //处理重复的UserMessage
            // Handle duplicate UserMessage
            if (!memory.messages().isEmpty()) {
                ChatMessage lastMessage = memory.messages().get(memory.messages().size() - 1);
                if (lastMessage instanceof UserMessage) {
                    List<ChatMessage> list = memory.messages().subList(0, memory.messages().size() - 1);
                    memory.clear();
                    list.forEach(memory::add);
                }
            }

            memory.add(UserMessage.from(userContents));

            //得到截断后符合maxTokens的文本消息
            // Get truncated text messages that fit within maxTokens
            List<ChatMessage> requestMemoryMessages = List.copyOf(memory.messages());
            chatMessages.addAll(requestMemoryMessages);

            // Retrieval evidence is request-scoped. Keep it in the model snapshot,
            // but persist only the original user text in Redis short-term memory.
            if (StringUtils.isNotBlank(chatModelRequest.getShortTermMemoryUserMessage())) {
                ShortTermMemoryWindow.persistRawUserMessage(
                        shortTermMemoryService,
                        memoryId,
                        requestMemoryMessages,
                        UserMessage.from(chatModelRequest.getShortTermMemoryUserMessage()));
            }

            //AI services currently do not support multimodality, use the low-level API for this. https://docs.langchain4j.dev/tutorials/ai-services#multimodality
            //重新组装用户消息及追加图片消息到chatMessage
            // Reassemble user message and append image messages to chatMessage
            List<Content> imageContents = ImageUtil.urlsToImageContent(chatModelRequest.getImageUrls());
            if (CollectionUtils.isNotEmpty(imageContents)) {
                int lastIndex = chatMessages.size() - 1;
                UserMessage lastMessage = (UserMessage) chatMessages.get(lastIndex);
                chatMessages.remove(lastIndex);
                List<Content> userMessage = new ArrayList<>();
                userMessage.addAll(lastMessage.contents());
                userMessage.addAll(imageContents);
                chatMessages.add(UserMessage.from(userMessage));
            }
            return chatMessages;
        } else {
            if (StringUtils.isNotBlank(chatModelRequest.getSystemMessage())) {
                chatMessages.add(SystemMessage.from(chatModelRequest.getSystemMessage()));
            }
            List<Content> imageContents = ImageUtil.urlsToImageContent(chatModelRequest.getImageUrls());
            if (CollectionUtils.isNotEmpty(imageContents)) {
                userContents.addAll(imageContents);
            }
            chatMessages.add(UserMessage.from(userContents));
        }
        return chatMessages;
    }

    public TokenCountEstimator resolveTokenCountEstimator() {
        String tokenEstimatorName = TokenEstimatorThreadLocal.getTokenEstimator();
        if (StringUtils.isBlank(tokenEstimatorName) && null != getTokenEstimator()) {
            return getTokenEstimator();
        }
        return TokenEstimatorFactory.create(tokenEstimatorName);
    }

    private ShortTermMemoryTokenBudget calculateShortTermMemoryBudget(
            ChatModelRequest chatModelRequest,
            Collection<ToolSpecification> toolSpecifications,
            TokenCountEstimator tokenCountEstimator) {
        List<ChatMessage> fixedMessages = new ArrayList<>();
        if (StringUtils.isNotBlank(chatModelRequest.getSystemMessage())) {
            fixedMessages.add(SystemMessage.from(chatModelRequest.getSystemMessage()));
        }
        fixedMessages.add(UserMessage.from(chatModelRequest.getUserMessage()));
        int fixedMessageTokens = tokenCountEstimator.estimateTokenCountInMessages(fixedMessages);
        int toolTokens = toolSpecifications.stream()
                .mapToInt(tool -> tokenCountEstimator.estimateTokenCountInText(tool.toJson()))
                .sum();

        ZhiMeshProperties.Conversation settings = SpringUtil.getBean(ZhiMeshProperties.class).getConversation();
        ShortTermMemoryTokenBudget budget = ShortTermMemoryTokenBudget.calculate(
                aiModel.getMaxInputTokens(),
                settings.getShortMemoryMaxHistoryTokens(),
                fixedMessageTokens,
                toolTokens,
                settings.getShortMemoryReservedOutputTokens(),
                settings.getShortMemorySafetyRatio());
        if (budget.overcommitted()) {
            log.warn("Short-term memory fixed input exceeds reserved model budget, model:{}, fixedTokens:{}, "
                            + "toolTokens:{}, outputReserve:{}, safetyTokens:{}, messageWindow:{}",
                    aiModel.getName(), budget.fixedMessageTokens(), budget.toolTokens(),
                    budget.reservedOutputTokens(), budget.safetyTokens(), budget.messageWindowTokens());
        } else {
            log.debug("Short-term memory budget, model:{}, messageWindow:{}, history:{}, fixed:{}, tools:{}",
                    aiModel.getName(), budget.messageWindowTokens(), budget.historyTokens(),
                    budget.fixedMessageTokens(), budget.toolTokens());
        }
        return budget;
    }

    /**
     * 将 MCP 客户端发现的工具包装为统一执行器，key 为工具名
     * Wrap tools discovered from MCP clients as unified executors, keyed by tool name
     */
    private Map<String, ToolExecutor> getMcpToolExecutors(List<McpClient> mcpClients) {
        Map<ToolSpecification, McpClient> tools = McpToolRegistry.discover(mcpClients,
                name -> log.warn("Duplicate MCP tool name detected; keeping the first provider, toolName:{}", name));
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        tools.forEach((spec, client) -> executors.put(spec.name(), new McpToolExecutor(spec, client)));
        return executors;
    }

    private ChatRequest createChatRequest(ChatModelRequest httpRequestParams) {

        return createChatRequest(httpRequestParams, toolSpecifications(discoverRequestTools(httpRequestParams)));
    }

    /**
     * 发现本次请求可用的全部工具：MCP 来源照旧，再合并请求级内置工具（重名时内置优先）
     * Discover all tools available for this request: MCP sources as before, then
     * merge request-scoped builtin tools (builtin wins on name conflicts)
     */
    private Map<String, ToolExecutor> discoverRequestTools(ChatModelRequest httpRequestParams) {
        List<McpClient> mcpClients = httpRequestParams.getMcpClients();
        Map<String, ToolExecutor> requestTools = new LinkedHashMap<>(getMcpToolExecutors(mcpClients));
        if (CollectionUtils.isNotEmpty(mcpClients)) {
            log.info("MCP tools available, clientCount:{}, toolCount:{}, toolNames:{}",
                    mcpClients.size(), requestTools.size(), requestTools.keySet());
        }

        List<ToolExecutor> builtinTools = httpRequestParams.getBuiltinTools();
        if (CollectionUtils.isNotEmpty(builtinTools)) {
            for (ToolExecutor builtinTool : builtinTools) {
                if (null == builtinTool || null == builtinTool.spec()) {
                    log.warn("Skipping invalid builtin tool without specification");
                    continue;
                }
                String toolName = builtinTool.spec().name();
                ToolExecutor previous = requestTools.put(toolName, builtinTool);
                if (null != previous) {
                    log.warn("Builtin tool overrides MCP tool with the same name, toolName:{}", toolName);
                }
            }
        }
        return requestTools;
    }

    private List<ToolSpecification> toolSpecifications(Map<String, ToolExecutor> toolExecutorMap) {
        return toolExecutorMap.values().stream().map(ToolExecutor::spec).toList();
    }

    private ChatRequest createChatRequest(ChatModelRequest httpRequestParams,
                                          Collection<ToolSpecification> toolSpecifications) {

        log.info("sseChat,messageId:{}", httpRequestParams.getMemoryId());
        List<ChatMessage> chatMessages = createChatMessages(httpRequestParams, toolSpecifications);

        DefaultChatRequestParameters.Builder<?> builder = ChatRequestParameters.builder();
        builder.toolSpecifications(new ArrayList<>(toolSpecifications));

        // Response format
        String responseFormat = httpRequestParams.getResponseFormat();
        log.info("Response format:{}", responseFormat);
        if (StringUtils.isNotBlank(responseFormat)) {
            if (aiModel.getResponseFormatTypes().contains(responseFormat)) {
                builder.responseFormat(RESPONSE_FORMAT_TYPE_JSON_OBJECT.equals(responseFormat) ? ResponseFormat.JSON : ResponseFormat.TEXT);
            } else {
                log.warn("Current model does not support JSON response format (most LLMs support JSON format, please check ai_model.response_format_types for json_object), model name: {}, supported formats: {}", aiModel.getName(), aiModel.getResponseFormatTypes());
            }
        }

        // Enable thinking
        Map<String, Object> customParameters = new HashMap<>();
        if (null != httpRequestParams.getReturnThinking()) {
            customParameters.put(ENABLE_THINKING, httpRequestParams.getReturnThinking());
        }
        if (null != httpRequestParams.getEnableWebSearch()) {
            customParameters.put(ENABLE_WEB_SEARCH, httpRequestParams.getEnableWebSearch());
        }
        ChatRequestParameters parameters = doCreateChatRequestParameters(builder.build(), customParameters);

        return ChatRequest.builder()
                .messages(chatMessages)
                .parameters(parameters)
                .build();
    }

    protected ChatRequestParameters doCreateChatRequestParameters(ChatRequestParameters defaultParameters, Map<String, Object> customParameters) {
        return defaultParameters;
    }

    private List<ToolExecutionResultMessage> createToolExecutionMessages(AiMessage aiMessage, Map<String, ToolExecutor> toolExecutorMap, ToolContext toolContext, String sseUuid) {
        ZhiMeshProperties.Agent agentSettings = resolveAgentSettings();
        List<ToolExecutionResultMessage> toolExecutionMessages = new ArrayList<>();
        aiMessage.toolExecutionRequests().forEach(req -> {
            req = parseToolRequest(req);
            log.info("Tool execution requested, toolName:{}", req.name());
            ToolExecutor executor = toolExecutorMap.get(req.name());
            if (null == executor) {
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req,
                        "No Tool executor found for this tool request"));
                recordToolCallTrace(toolContext, req, 0L, false, "No Tool executor found for this tool request");
                return;
            }
            long toolStart = System.currentTimeMillis();
            try {
                final String result = executeToolWithGuardrails(executor, req, toolContext, agentSettings);
                long toolDuration = System.currentTimeMillis() - toolStart;
                log.info("Tool execution completed, toolName:{}, resultLength:{}, duration:{}ms",
                        req.name(), result == null ? 0 : result.length(), toolDuration);
                SseManager.sendToolCall(sseUuid, req.name(), toolDuration, true, req.arguments(), result);
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, result));
                recordToolCallTrace(toolContext, req, toolDuration, true, result);
            } catch (Exception e) {
                long toolDuration = System.currentTimeMillis() - toolStart;
                log.warn("Tool execution failed, toolName:{}, duration:{}ms, errorType:{}",
                        req.name(), toolDuration, e.getClass().getSimpleName());
                SseManager.sendToolCall(sseUuid, req.name(), toolDuration, false, req.arguments(), e.getMessage());
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, e.getMessage()));
                recordToolCallTrace(toolContext, req, toolDuration, false, e.getMessage());
            }
        });
        return toolExecutionMessages;
    }

    /**
     * 执行工具并对内置（非 MCP）工具应用超时与结果截断保护；MCP 工具保持原有直调行为，
     * 避免改造引入回归
     * <p>
     * Execute a tool, applying timeout and result-truncation guardrails to
     * builtin (non-MCP) tools only; MCP tools keep the original direct
     * invocation behavior to avoid regressions from this refactor.
     */
    private String executeToolWithGuardrails(ToolExecutor executor, ToolExecutionRequest req,
                                             ToolContext toolContext, ZhiMeshProperties.Agent agentSettings) throws Exception {
        if (executor.isMcpTool()) {
            return executor.execute(req, toolContext);
        }
        String result;
        long timeoutMs = agentSettings.getToolTimeoutMs();
        if (timeoutMs > 0) {
            // 工具在池线程上执行：把提交线程的 run_workflow 递归标记带过去。工作流线程
            // （标记已置位）内再起 agent 时，其工具也走本执行器——不传播的话嵌套
            // run_workflow 会在池线程上被放行，递归防护失效；结束时一律清除，
            // 池线程复用不会携带脏标记
            // Tools execute on pool threads: carry the submitting thread's
            // run_workflow recursion flag across. An agent started inside a
            // workflow thread (flag set) also runs its tools through this
            // executor — without propagation a nested run_workflow would be
            // allowed on the pool thread and the guard would be moot; always
            // clear on exit so a reused pool thread never carries a stale flag
            Boolean inRunWorkflow = RunWorkflowTool.IN_RUN_WORKFLOW.get();
            Future<String> future = TOOL_EXECUTION_EXECUTOR.submit(() -> {
                if (Boolean.TRUE.equals(inRunWorkflow)) {
                    RunWorkflowTool.IN_RUN_WORKFLOW.set(Boolean.TRUE);
                } else {
                    RunWorkflowTool.IN_RUN_WORKFLOW.remove();
                }
                try {
                    return executor.execute(req, toolContext);
                } finally {
                    RunWorkflowTool.IN_RUN_WORKFLOW.remove();
                }
            });
            try {
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException(
                        "Tool execution timed out after " + timeoutMs + "ms, toolName:" + req.name(), e);
            } catch (ExecutionException e) {
                Throwable cause = null != e.getCause() ? e.getCause() : e;
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(cause);
            }
        } else {
            result = executor.execute(req, toolContext);
        }
        return truncateToolResult(result, agentSettings.getToolResultMaxChars());
    }

    /**
     * 超长工具结果截断到 maxChars，截断时末尾追加标记
     * Truncate an oversized tool result to maxChars, appending a marker when truncated
     */
    private String truncateToolResult(String result, int maxChars) {
        if (null == result || result.length() <= maxChars) {
            return result;
        }
        return result.substring(0, maxChars) + "\n...[truncated]";
    }

    /**
     * 记录一次工具调用轨迹到请求级上下文（上下文或收集器为空时静默跳过）
     * Record one tool-call trace into the request-scoped context (silently
     * skipped when the context or collector is absent)
     */
    private void recordToolCallTrace(ToolContext toolContext, ToolExecutionRequest req,
                                     long durationMs, boolean success, String resultSummary) {
        if (null == toolContext || null == toolContext.getToolTraces()) {
            return;
        }
        List<ToolCallTrace> traces = toolContext.getToolTraces();
        traces.add(ToolCallTrace.builder()
                .seq(traces.size())
                .toolName(req.name())
                .args(req.arguments())
                .resultSummary(resultSummary)
                .durationMs(durationMs)
                .success(success)
                .build());
    }

    /**
     * 构造请求级工具上下文，一次请求构造一次并贯穿所有递归工具调用轮次
     * Create the request-scoped tool context, constructed once per request and
     * shared across all recursive tool-call rounds
     */
    private ToolContext createToolContext(User user, ChatModelRequest chatModelRequest) {
        return ToolContext.builder()
                .user(user)
                .memoryId(chatModelRequest.getMemoryId())
                .toolTraces(new ArrayList<>())
                .build();
    }

    /**
     * 读取工具调用循环配置；容器或配置不可用时回退到默认值
     * Resolve tool-loop settings; falls back to defaults when the container or
     * the properties bean is unavailable
     */
    private ZhiMeshProperties.Agent resolveAgentSettings() {
        try {
            ZhiMeshProperties properties = SpringUtil.getBean(ZhiMeshProperties.class);
            if (null != properties && null != properties.getAgent()) {
                return properties.getAgent();
            }
        } catch (Exception e) {
            log.debug("ZhiMeshProperties unavailable, using default agent tool-loop settings", e);
        }
        return new ZhiMeshProperties.Agent();
    }

    /**
     * 将收到的内容转换成音频
     * 条件：系统设置了tts为服务端转换 && 答案类型为音频
     * Convert received content to audio
     * Condition: System TTS is set to server-side conversion && answer type is audio
     *
     * @param params          内部迭代方法入参 / Internal iteration method input
     * @param partialResponse 文本内容 / Text content
     */
    private void ttsOnPartialMessage(InnerStreamChatParam params, String partialResponse) {
        TtsJobInfo jobInfo = ttsJobCache.getIfPresent(params.getUser().getUuid());
        if (null != jobInfo && null != jobInfo.getTtsModelContext()
            && ZhiMeshConstant.TtsConstant.SYNTHESIZER_SERVER.equals(ttsSetting.getSynthesizerSide())
            && params.getAnswerContentType() == ZhiMeshConstant.CharacterConstant.ANSWER_CONTENT_TYPE_AUDIO) {
            jobInfo.getTtsModelContext().processPartialText(jobInfo.getJobId(), partialResponse);
        }
    }

    private TtsJobInfo ttsOnComplete(InnerStreamChatParam params) {
        TtsJobInfo jobInfo = ttsJobCache.getIfPresent(params.getUser().getUuid());
        if (null != jobInfo && null != jobInfo.getTtsModelContext()) {
            // complete() 内部会阻塞至 TTS 异步回调结束（最多 30s），保证 jobInfo.filePath 已被回填。
            // 详见 AbstractTtsModelService#complete 的模板方法实现。
            // <p>
            // complete() blocks until the TTS async callback finishes (up to 30s), guaranteeing that
            // jobInfo.filePath has been populated. See the template method in AbstractTtsModelService#complete.
            jobInfo.getTtsModelContext().complete(jobInfo.getJobId());
        }
        //Remove job info
        ttsJobCache.invalidate(params.getUser().getUuid());
        return jobInfo;
    }

    private void closeMcpClients(List<McpClient> mcpClients) {
        if (mcpClients == null) {
            return;
        }
        mcpClients.forEach(item -> {
            try {
                item.close();
            } catch (Exception e) {
                log.error("close mcp client error", e);
            }
        });
    }

    /**
     * 如果工具请求参数中没有包含id，则手动解析该参数以补充 id 和 name
     * 部分模型（如硅基流动）返回的工具请求可能没有id和name，需要手动解析，如 ToolExecutionRequest { id = "", name = "", arguments = "maps_weather {"city": "广州"}" }
     * If the tool request parameter does not contain an id, manually parse it to supplement id and name
     * Some models (e.g., SiliconFlow) may return tool requests without id and name, requiring manual parsing
     *
     * @param req 工具请求参数 / Tool request parameter
     */
    private ToolExecutionRequest parseToolRequest(ToolExecutionRequest req) {
        if (StringUtils.isBlank(req.id())) {
            String arguments = req.arguments();
            String name = req.name();
            if (StringUtils.isBlank(name) && StringUtils.isNotBlank(arguments) && !arguments.startsWith("{")) {
                String[] args = arguments.split(" ");
                if (args.length > 0) {
                    name = args[0];
                    arguments = arguments.substring(name.length()).trim();
                } else {
                    name = "name_" + UuidUtil.createShort();
                }
            }
            return ToolExecutionRequest.builder().id("id_" + UuidUtil.createShort()).name(name).arguments(arguments).build();
        }
        return req;
    }
}
