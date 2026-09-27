package com.pppp.zhimesh.common.languagemodel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.helper.TtsModelContext;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalRequiredDecorator;
import com.pppp.zhimesh.common.languagemodel.tool.McpToolExecutor;
import com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool;
import com.pppp.zhimesh.common.languagemodel.tool.SuspensionSignal;
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
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
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

    /**
     * 挂起次数达上限（zhimesh.agent.max-suspensions）时回给模型的引导文本：不挂起，
     * 模型基于已有信息直接作答（无限追问防护）
     * <p>
     * Guidance text returned to the model once the suspension cap
     * (zhimesh.agent.max-suspensions) is hit: no further suspension, the model
     * answers directly from what it already gathered (infinite-asking guard).
     */
    static final String SUSPENSION_LIMIT_REACHED_TEXT = "已达追问上限，请基于已有信息直接回答";

    /**
     * 本请求未接线挂起回调（无 ToolContext.suspensionSink，如 blocking 路径误注册协作类
     * 工具）时回给模型的引导文本
     * <p>
     * Guidance text for the model when this request has no suspension sink
     * wired on ToolContext (e.g. a collaborative tool mistakenly registered on
     * the blocking path).
     */
    static final String SUSPENSION_UNSUPPORTED_TEXT = "当前会话不支持挂起提问，请基于已有信息直接回答";

    /**
     * adi_agent_pending_checkpoint.pending_request_id 的列宽上限：落库前截断（044 迁移
     * varchar(128)；正常 provider 的请求 id 远短于此）
     * <p>
     * Column width of adi_agent_pending_checkpoint.pending_request_id:
     * truncated before persistence (migration 044, varchar(128); normal
     * provider request ids are far shorter).
     */
    static final int PENDING_REQUEST_ID_MAX_CHARS = 128;

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
                // 挂起恢复轮从检查点继承的工具迭代深度起步（跨挂起累计总迭代数）；
                // 普通请求该值为空，从 0 开始，行为不变
                // A resumed round starts the tool-loop depth inherited from its
                // checkpoint (total iterations accumulate across suspensions);
                // ordinary requests leave it null and start at 0, unchanged
                .toolCallDepth(null != httpRequestParams.getResumedToolCallDepth()
                        && httpRequestParams.getResumedToolCallDepth() > 0
                        ? httpRequestParams.getResumedToolCallDepth() : 0)
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
                    // 如果有工具执行请求：先执行非协作类同伴工具，再检查是否有协作类工具
                    // （ask_user 等）把本轮挂起——挂起时不递归调用模型，改走合成收尾
                    // If there are tool execution requests: execute the non-collaborative
                    // peers first, then check whether a collaborative tool (ask_user etc.)
                    // suspended this round — when suspended, no recursive model call
                    // happens; the synthesized wrap-up path takes over
                    ToolRoundOutcome toolRound = executeToolRound(responseAiMessage, toolExecutorMap,
                            params.getToolContext(), params.getSseUuid());
                    SuspensionSignal suspensionSignal = null != params.getToolContext()
                            ? params.getToolContext().getSuspensionSignal() : null;
                    if (null != suspensionSignal) {
                        suspendToolLoop(params, response, toolRound, suspensionSignal);
                        return;
                    }
                    // 中间工具轮的 token 也必须入账，否则该轮消耗被漏记导致配额少扣；
                    // 最终轮仍由下方 calculateToken 统一入账，保证每轮恰好记录一次。
                    // 挂起轮不在此入账——其真实 usage 由合成收尾响应经 calculateToken
                    // 恰好入账一次
                    // Intermediate tool rounds must also record tokens, otherwise that round is
                    // missed and the quota is under-charged; the final round is still recorded
                    // once by calculateToken below, keeping exactly one record per round. A
                    // suspending round skips this cache — its real usage is recorded exactly
                    // once by the synthesized wrap-up response through calculateToken
                    if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                        LLMTokenUtil.cacheTokenUsage(getStringRedisTemplate(), params.getUuid(),
                                response.metadata().tokenUsage());
                    }
                    List<ToolExecutionResultMessage> toolExecutionMessages = toolRound.resultMessages();

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
                    completeStreamingResponse(params, response);
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

    /**
     * 流式请求的正常完成路径（原 else 分支原样抽出，行为逐字节等价）：成功健康记录、
     * token 结算与 meta 下发、回调消费、关闭 MCP 客户端。挂起轮的合成收尾复用同一
     * 方法，保证 token 结算/回调/资源关闭与正常完成完全一致
     * <p>
     * The ordinary completion path of a streaming request (extracted verbatim
     * from the former else branch, byte-equal behavior): success health record,
     * token settlement and meta emission, consumer callback, MCP-client close.
     * The suspending round's synthesized wrap-up reuses this exact method so
     * token settlement / callback / resource cleanup match the normal
     * completion path completely.
     */
    private void completeStreamingResponse(InnerStreamChatParam params, ChatResponse response) {
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

    /**
     * 工具轮执行结果载体：parsedRequests 为解析补全 id/name 后的请求（快照 AiMessage
     * 必须与执行用的是同一批对象——parseToolRequest 对空 id 不幂等，每次调用生成新
     * 随机 id，重复解析会破坏「请求-结果」配对）；resultMessages 仅含本轮已执行工具
     * 的结果，挂起请求与其余协作请求的结果由恢复轮统一注入
     * <p>
     * Carrier of one tool round's outcome: parsedRequests are the requests
     * after parseToolRequest completed their ids/names (the snapshot AiMessage
     * must reuse the very same objects used for execution — parseToolRequest
     * is not idempotent for blank ids, generating a fresh random id per call,
     * so re-parsing would break request/result pairing); resultMessages holds
     * only the executed tools' results — the suspended request and the other
     * collaborative requests get theirs injected at resume time.
     */
    private record ToolRoundOutcome(List<ToolExecutionRequest> parsedRequests,
                                    List<ToolExecutionResultMessage> resultMessages) {
    }

    /**
     * 协作类工具（ask_user / request_human_approval / 需审批 MCP 装饰器）挂起本轮后的
     * 合成收尾：不再递归调用模型。落检查点（经 ToolContext.suspensionSink 回调，
     * AbstractLLMService 保持无 Spring 依赖），快照 = 当前请求消息链 + 本轮 AiMessage
     * （解析后的工具请求）+ 已执行同伴的结果；检查点持久化成功后才发挂起事件
     * （按 kind 参数化：ASK_USER→agent_question，APPROVAL/MCP_APPROVAL→
     * approval_request；宁可不发卡，也不出现「前端已见卡片但无检查点可恢复」的半挂起态）；
     * 随后以「问题文本」为消息内容、本轮真实 tokenUsage 构造合成 ChatResponse，复用
     * 正常完成路径结算与收尾（挂起轮未预入账 token，由 calculateToken 恰好入账一次）
     * <p>
     * Synthesized wrap-up after a collaborative tool (ask_user /
     * request_human_approval / an approval-required MCP decorator) suspends
     * this round: no further model call. Persists the checkpoint via the
     * ToolContext.suspensionSink callback (keeping AbstractLLMService free of
     * Spring dependencies); the snapshot = current request message chain + this
     * round's AiMessage (parsed tool requests) + the executed peers' results.
     * The suspension event (parameterized by kind: ASK_USER → agent_question,
     * APPROVAL/MCP_APPROVAL → approval_request) is emitted only after the
     * checkpoint is durably persisted (rather send no card at all than a
     * half-suspended state where the frontend saw a card yet no checkpoint
     * exists to resume from). A synthetic ChatResponse carrying the question
     * text as its content and this round's real tokenUsage then flows through
     * the ordinary completion path (the suspending round skipped the
     * intermediate token cache, so calculateToken records it exactly once).
     */
    private void suspendToolLoop(InnerStreamChatParam params, ChatResponse response,
                                 ToolRoundOutcome toolRound, SuspensionSignal signal) {
        ToolContext toolContext = params.getToolContext();
        // 防御：能走到这里说明协作工具绕过了 executeCollaborativeRequest 的无 sink
        // 闸门直接置位了信号（当前不存在该形态，T5 新增审批类协作工具时防患）——
        // 显式失败好过持久化一行时 NPE
        // Defense: reaching here means a collaborative tool bypassed
        // executeCollaborativeRequest's no-sink gate and raised the signal
        // directly (no such shape exists today; guards the T5 approval tools)
        // — failing explicitly beats an NPE mid-persist
        if (null == toolContext.getSuspensionSink()) {
            throw new IllegalStateException(
                    "Collaborative tool suspended without a checkpoint sink, toolName:" + signal.getToolName());
        }
        // 挂起轮本身计入迭代预算：检查点存 depth+1，恢复轮从该深度续跑；恢复后续跑
        // 达上限时复用既有 toollessFinalRound 优雅收尾
        // The suspending round itself consumes one iteration: the checkpoint
        // stores depth+1 and the resumed round continues from it; hitting the
        // cap after resume reuses the existing toollessFinalRound wrap-up
        int suspensionToolCallDepth = params.getToolCallDepth() + 1;
        int suspensionCount = toolContext.getSuspensionCount() + 1;
        // pendingRequestId 截断到 044 迁移的 varchar(128) 列宽
        // Truncate pendingRequestId to the varchar(128) width of migration 044
        signal.setRequestId(StringUtils.substring(signal.getRequestId(), 0, PENDING_REQUEST_ID_MAX_CHARS));
        List<ChatMessage> snapshot = new ArrayList<>(params.getChatRequest().messages());
        snapshot.add(AiMessage.aiMessage(toolRound.parsedRequests()));
        snapshot.addAll(toolRound.resultMessages());
        String checkpointUuid = toolContext.getSuspensionSink().persist(signal, snapshot,
                suspensionToolCallDepth, suspensionCount);
        signal.setCheckpointUuid(checkpointUuid);
        // 检查点已持久化，才向前端发协作卡片（uuid 随载荷下发供卡片↔检查点关联）；
        // 事件名按 kind 参数化：ASK_USER→agent_question（问题卡片），
        // APPROVAL/MCP_APPROVAL→approval_request（审批卡片），旧前端对未知事件忽略
        // The checkpoint is durably persisted, so the collaborative card is now
        // safe to emit (the uuid rides in the payload for card↔checkpoint
        // correlation); the event name is parameterized by kind:
        // ASK_USER → agent_question (question card), APPROVAL/MCP_APPROVAL →
        // approval_request (approval card); old frontends ignore unknown events
        Map<String, Object> suspensionPayload = buildSuspensionEventPayload(signal);
        if (isApprovalKind(signal.getKind())) {
            SseManager.sendApprovalRequest(params.getSseUuid(), suspensionPayload);
        } else {
            SseManager.sendAgentQuestion(params.getSseUuid(), suspensionPayload);
        }
        log.info("Tool loop suspended by collaborative tool, toolName:{}, checkpointUuid:{}, suspensionCount:{}, toolCallDepth:{}",
                signal.getToolName(), checkpointUuid, suspensionCount, suspensionToolCallDepth);
        TokenUsage roundUsage = null != response.metadata() ? response.metadata().tokenUsage() : null;
        ChatResponse syntheticResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(signal.getQuestion()))
                .metadata(null != roundUsage
                        ? ChatResponseMetadata.builder().tokenUsage(roundUsage).build()
                        : ChatResponseMetadata.builder().build())
                .build();
        completeStreamingResponse(params, syntheticResponse);
    }

    /**
     * 挂起是否属审批类（APPROVAL / MCP_APPROVAL）：事件名分发与载荷形态的判定依据
     * <p>
     * Whether the suspension is an approval kind (APPROVAL / MCP_APPROVAL):
     * the dispatch basis for both the event name and the payload shape.
     */
    static boolean isApprovalKind(PendingCheckpointKind kind) {
        return PendingCheckpointKind.APPROVAL == kind || PendingCheckpointKind.MCP_APPROVAL == kind;
    }

    /**
     * 挂起 SSE 事件的载荷：公共键 kind/toolName/question/checkpointUuid；ASK_USER 追加
     * options（为空不带该键），审批类（APPROVAL/MCP_APPROVAL）追加 action/summary/
     * riskLevel（可空省略）——审批卡片的渲染依据，question 为卡片正文兜底。旧前端忽略
     * 未知事件与未知键，安全。checkpointUuid 与 AnswerMeta.suspension 载荷同源（信号
     * 回填值），供前端卡片↔检查点关联与历史重放
     * <p>
     * Payload of the suspension SSE event: common keys kind/toolName/question/
     * checkpointUuid; ASK_USER adds options (omitted when empty) and the
     * approval kinds (APPROVAL/MCP_APPROVAL) add action/summary/riskLevel
     * (each omitted when null) — the approval-card rendering basis, with
     * question as the card-body fallback. Old frontends ignore unknown events
     * and keys, so this is safe. checkpointUuid comes from the same signal
     * backfill as the AnswerMeta.suspension payload, for frontend
     * card↔checkpoint correlation and history replay.
     */
    private Map<String, Object> buildSuspensionEventPayload(SuspensionSignal signal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", null != signal.getKind() ? signal.getKind().getCode() : null);
        payload.put("toolName", signal.getToolName());
        payload.put("question", signal.getQuestion());
        if (CollectionUtils.isNotEmpty(signal.getOptions())) {
            payload.put("options", signal.getOptions());
        }
        if (isApprovalKind(signal.getKind())) {
            if (null != signal.getAction()) {
                payload.put("action", signal.getAction());
            }
            if (null != signal.getSummary()) {
                payload.put("summary", signal.getSummary());
            }
            if (null != signal.getRiskLevel()) {
                payload.put("riskLevel", signal.getRiskLevel());
            }
        }
        payload.put("checkpointUuid", signal.getCheckpointUuid());
        return payload;
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
     * 发现本次请求可用的全部工具：MCP 来源照旧（命中 approvalRequiredTools 的先包
     * {@link ApprovalRequiredDecorator} 审批门），再合并请求级内置工具（重名时内置优先，
     * 内置覆盖装饰器——审批门仅约束 MCP 工具）
     * Discover all tools available for this request: MCP sources as before
     * (those hit by approvalRequiredTools are first wrapped with the
     * {@link ApprovalRequiredDecorator} approval gate), then merge
     * request-scoped builtin tools (builtin wins on name conflicts, overriding
     * a decorator too — the approval gate constrains MCP tools only)
     */
    private Map<String, ToolExecutor> discoverRequestTools(ChatModelRequest httpRequestParams) {
        List<McpClient> mcpClients = httpRequestParams.getMcpClients();
        Map<String, ToolExecutor> requestTools = new LinkedHashMap<>(getMcpToolExecutors(mcpClients));
        wrapApprovalRequiredTools(requestTools, httpRequestParams.getApprovalRequiredTools());
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
                    if (previous instanceof ApprovalRequiredDecorator) {
                        // 审批门被内置覆盖：标记名与内置工具重名，approvalRequiredMcpTools
                        // 对该名不再生效（审批门仅约束 MCP 工具的既有语义），显式留痕
                        // The approval gate is overridden by a builtin: the
                        // marked name collides with a builtin tool and
                        // approvalRequiredMcpTools no longer applies to it
                        // (the gate-constrains-MCP-only semantics), logged
                        // explicitly
                        log.warn("Builtin tool overrides the approval-gated MCP tool with the same name; the approval marking no longer applies, toolName:{}",
                                toolName);
                    } else {
                        log.warn("Builtin tool overrides MCP tool with the same name, toolName:{}", toolName);
                    }
                }
            }
        }
        return requestTools;
    }

    /**
     * 对命中审批标记集合的 MCP 执行器就地包审批装饰器（装配期包装，McpToolExecutor 零改动）：
     * 未持批准凭证不执行真实调用、挂起转审批；凭证精确匹配（仅本恢复链有效）才放行。
     * 集合为空时原样返回（无审批门，行为不变）
     * <p>
     * Wrap the MCP executors hit by the approval-marked set with the approval
     * decorator in place (assembly-time wrapping, McpToolExecutor untouched):
     * without a matching grant the real call never runs — the loop suspends for
     * approval; an exact grant match (valid only within this resume chain)
     * lets it through. An empty set returns the map unchanged (no approval
     * gate, unchanged behavior).
     */
    static void wrapApprovalRequiredTools(Map<String, ToolExecutor> executors, Set<String> approvalRequiredTools) {
        if (null == approvalRequiredTools || approvalRequiredTools.isEmpty()) {
            return;
        }
        for (String toolName : approvalRequiredTools) {
            ToolExecutor executor = executors.get(toolName);
            if (null == executor || executor.isCollaborative()) {
                // 未发现的标记名静默跳过（MCP 未绑定该工具）；已是协作类（防重复包装）
                // Silently skip an unknown marked name (the MCP tool is not
                // bound); already-collaborative entries prevent double wrapping
                continue;
            }
            executors.put(toolName, new ApprovalRequiredDecorator(executor));
        }
    }

    private List<ToolSpecification> toolSpecifications(Map<String, ToolExecutor> toolExecutorMap) {
        return toolExecutorMap.values().stream().map(ToolExecutor::spec).toList();
    }

    private ChatRequest createChatRequest(ChatModelRequest httpRequestParams,
                                          Collection<ToolSpecification> toolSpecifications) {

        log.info("sseChat,messageId:{}", httpRequestParams.getMemoryId());
        // 挂起恢复轮：消息链由「检查点快照 + 配对结果消息」构成，跳过常规的短期记忆
        // 窗口装配——用户答复已由消费赢者无条件 append 进短期记忆（上下文恒启用，
        // 2026-09-24 产品决策），跨挂起的记忆记账闭环。记账窗口仍按同口径预算计算：否则
        // saveAfterAiResponse 追加最终 AiMessage 时 memoryWindowMaxTokens 缺失，退化为
        // 模型全量输入上限，令本轮短期记忆超出 shortMemoryMaxHistoryTokens 预算。
        // 其余调用方 resumedMessages 为空，走原装配不变
        // A resumed round: the message chain is "checkpoint snapshot + paired
        // result messages" and skips the regular short-term-memory window
        // assembly — the consume winner has already appended the user's answer
        // into short-term memory unconditionally (context is always on per the
        // 2026-09-24 product decision), closing the cross-suspension memory
        // bookkeeping. The bookkeeping
        // window is still computed with the same budget: otherwise
        // saveAfterAiResponse finds memoryWindowMaxTokens unset when appending
        // the final AiMessage and degrades to the model's full input cap,
        // letting this round's short-term memory exceed the
        // shortMemoryMaxHistoryTokens budget. Every other caller leaves
        // resumedMessages null and takes the original assembly unchanged
        List<ChatMessage> chatMessages;
        if (null != httpRequestParams.getResumedMessages()) {
            if (StringUtils.isNotBlank(httpRequestParams.getMemoryId())
                    && null == httpRequestParams.getMemoryWindowMaxTokens()) {
                httpRequestParams.setMemoryWindowMaxTokens(calculateShortTermMemoryBudget(
                        httpRequestParams, toolSpecifications, resolveTokenCountEstimator())
                        .messageWindowTokens());
            }
            chatMessages = httpRequestParams.getResumedMessages();
        } else {
            chatMessages = createChatMessages(httpRequestParams, toolSpecifications);
        }

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

    /**
     * 阻塞路径（innerChatWithDepth）的既有入口：只取结果消息，行为与原实现一致。
     * 协作类工具在阻塞路径没有挂起回调（无 suspensionSink），到 executeToolRound
     * 里走「不支持挂起」的内联回答分支
     * <p>
     * Legacy entry for the blocking path (innerChatWithDepth): returns the
     * result messages only, behavior identical to the original implementation.
     * Collaborative tools have no suspension sink on the blocking path and take
     * the "suspension unsupported" inline-answer branch inside executeToolRound.
     */
    private List<ToolExecutionResultMessage> createToolExecutionMessages(AiMessage aiMessage, Map<String, ToolExecutor> toolExecutorMap, ToolContext toolContext, String sseUuid) {
        return executeToolRound(aiMessage, toolExecutorMap, toolContext, sseUuid).resultMessages();
    }

    /**
     * 执行一轮工具请求：请求先统一解析补全 id/name（快照与执行共用同一批对象，见
     * {@link ToolRoundOutcome}），非协作类工具照常执行；协作类工具（ask_user 等）交由
     * {@link #executeCollaborativeRequest} 处理（挂起/上限内联回答/同轮忽略）
     * <p>
     * Executes one round of tool requests: every request is parsed first to
     * complete its id/name (snapshot and execution share the same objects, see
     * {@link ToolRoundOutcome}); non-collaborative tools run exactly as before;
     * collaborative tools (ask_user etc.) go through
     * {@link #executeCollaborativeRequest} (suspend / cap-reached inline answer
     * / same-round skip).
     */
    private ToolRoundOutcome executeToolRound(AiMessage aiMessage, Map<String, ToolExecutor> toolExecutorMap, ToolContext toolContext, String sseUuid) {
        ZhiMeshProperties.Agent agentSettings = resolveAgentSettings();
        List<ToolExecutionRequest> parsedRequests = new ArrayList<>();
        List<ToolExecutionResultMessage> toolExecutionMessages = new ArrayList<>();
        for (ToolExecutionRequest rawRequest : aiMessage.toolExecutionRequests()) {
            ToolExecutionRequest req = parseToolRequest(rawRequest);
            parsedRequests.add(req);
            log.info("Tool execution requested, toolName:{}", req.name());
            ToolExecutor executor = toolExecutorMap.get(req.name());
            if (null == executor) {
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req,
                        "No Tool executor found for this tool request"));
                recordToolCallTrace(toolContext, req, 0L, false, "No Tool executor found for this tool request");
                continue;
            }
            if (executor.isCollaborative()) {
                executeCollaborativeRequest(req, executor, toolContext, sseUuid, agentSettings, toolExecutionMessages);
                continue;
            }
            // 执行前先发 TOOL_STARTED：前端实时点亮该工具的 running 步骤（与下方 TOOL_CALL
            // 完成事件配对）。「No Tool executor found」分支不发——没有可点亮的真实执行
            // Send TOOL_STARTED before execution so the frontend lights up the
            // running step in real time (paired with the TOOL_CALL completion
            // event below). The "No Tool executor found" branch sends nothing —
            // there is no real execution to light up.
            SseManager.sendToolStarted(sseUuid, req.name(), req.arguments());
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
        }
        return new ToolRoundOutcome(parsedRequests, toolExecutionMessages);
    }

    /**
     * 协作类工具请求的处理分支（ask_user 等）：
     * <ol>
     *   <li>同轮已有协作工具挂起 → 本请求不执行、不产生结果消息（“一轮只挂第一个”），
     *       「已被忽略，请单独重新发起」占位结果由恢复轮统一注入；</li>
     *   <li>挂起能力不可用（无 ToolContext / 未接线 suspensionSink）或挂起次数已达
     *       zhimesh.agent.max-suspensions 上限（防无限追问）→ 不挂起，返回引导文本，
     *       工具循环照常继续，模型基于已有信息直接作答；</li>
     *   <li>正常挂起 → 工具执行置位 ToolContext.suspensionSignal，轨迹记录
     *       （toolName、success=true、resultSummary=问题文本），本轮不产生
     *       结果消息，由 onCompleteResponse 的挂起分支落检查点并合成收尾；挂起事件
     *       （agent_question / approval_request，按 kind 分发）由挂起分支在检查点
     *       持久化成功后才发出（见 suspendToolLoop）。</li>
     * </ol>
     * <p>
     * Handling branch for collaborative tool requests (ask_user etc.):
     * <ol>
     *   <li>another collaborative tool already suspended this round → this
     *       request is neither executed nor given a result message ("only the
     *       first suspends"); its "ignored, re-ask alone" placeholder is
     *       injected at resume time;</li>
     *   <li>suspension unavailable (no ToolContext / no suspensionSink wired)
     *       or the zhimesh.agent.max-suspensions cap already hit (infinite
     *       asking guard) → no suspension; a guidance text is returned as the
     *       tool result and the loop continues normally so the model answers
     *       directly from what it has;</li>
     *   <li>ordinary suspension → the tool's execution sets
     *       ToolContext.suspensionSignal, the trace is recorded (toolName,
     *       success = true, resultSummary = the question), and this
     *       round yields no result message — onCompleteResponse's suspension
     *       branch persists the checkpoint and produces the synthesized
     *       wrap-up; the suspension event (agent_question / approval_request,
     *       dispatched by kind) is emitted by that branch only
     *       after the checkpoint is durably persisted (see suspendToolLoop).</li>
     * </ol>
     */
    private void executeCollaborativeRequest(ToolExecutionRequest req, ToolExecutor executor, ToolContext toolContext,
                                             String sseUuid, ZhiMeshProperties.Agent agentSettings,
                                             List<ToolExecutionResultMessage> toolExecutionMessages) {
        if (null != toolContext && null != toolContext.getSuspensionSignal()) {
            log.info("Collaborative tool request skipped: another suspension already raised in this round, toolName:{}",
                    req.name());
            return;
        }
        if (null == toolContext || null == toolContext.getSuspensionSink()
                || toolContext.getSuspensionCount() >= agentSettings.getMaxSuspensions()) {
            String guidance = null != toolContext && null != toolContext.getSuspensionSink()
                    ? SUSPENSION_LIMIT_REACHED_TEXT : SUSPENSION_UNSUPPORTED_TEXT;
            log.info("Collaborative tool answered inline instead of suspending, toolName:{}, guidance:{}",
                    req.name(), guidance);
            SseManager.sendToolCall(sseUuid, req.name(), 0L, true, req.arguments(), guidance);
            toolExecutionMessages.add(ToolExecutionResultMessage.from(req, guidance));
            recordToolCallTrace(toolContext, req, 0L, true, guidance);
            return;
        }
        // 通过协作闸门后才发 TOOL_STARTED：内联回答分支（不真正挂起/执行）不发，
        // 避免前端点亮一个从未执行的步骤
        // Send TOOL_STARTED only after the collaborative gates pass: the
        // inline-answer branches (no real suspension/execution) send nothing,
        // so the frontend never lights up a step that never ran.
        SseManager.sendToolStarted(sseUuid, req.name(), req.arguments());
        long toolStart = System.currentTimeMillis();
        try {
            final String result = executeToolWithGuardrails(executor, req, toolContext, agentSettings);
            long toolDuration = System.currentTimeMillis() - toolStart;
            SuspensionSignal raised = toolContext.getSuspensionSignal();
            if (null != raised) {
                log.info("Collaborative tool raised suspension, toolName:{}, duration:{}ms", req.name(), toolDuration);
                recordToolCallTrace(toolContext, req, toolDuration, true, raised.getQuestion());
                // agent_question 事件不在此发：挂起分支（suspendToolLoop）落检查点成功后
                // 才发卡，杜绝「前端已见卡片但无检查点可恢复」的半挂起态
                // The agent_question event is NOT emitted here: the suspension
                // branch (suspendToolLoop) sends the card only after the
                // checkpoint is durably persisted, precluding a half-suspended
                // state (card shown yet nothing to resume from)
                return;
            }
            // 防御：协作工具执行成功却未置挂起信号——按普通工具结果继续本轮循环
            // Defensive: a collaborative tool finished without raising the
            // suspension signal — treat it as an ordinary tool result
            log.info("Collaborative tool finished without suspension, toolName:{}, duration:{}ms",
                    req.name(), toolDuration);
            SseManager.sendToolCall(sseUuid, req.name(), toolDuration, true, req.arguments(), result);
            toolExecutionMessages.add(ToolExecutionResultMessage.from(req, result));
            recordToolCallTrace(toolContext, req, toolDuration, true, result);
        } catch (Exception e) {
            long toolDuration = System.currentTimeMillis() - toolStart;
            log.warn("Collaborative tool execution failed, toolName:{}, duration:{}ms, errorType:{}",
                    req.name(), toolDuration, e.getClass().getSimpleName());
            SseManager.sendToolCall(sseUuid, req.name(), toolDuration, false, req.arguments(), e.getMessage());
            toolExecutionMessages.add(ToolExecutionResultMessage.from(req, e.getMessage()));
            recordToolCallTrace(toolContext, req, toolDuration, false, e.getMessage());
        }
    }

    /**
     * 执行工具并应用超时与结果截断保护：内置工具始终受保护；MCP 工具是否纳入同一保护
     * 由 zhimesh.agent.mcp-guardrails-enabled 决定（默认关，保持原有直调零回归，
     * 开启后同样走下方超时——含池线程 run_workflow 递归标记传播——与结果截断）。
     * 轨迹（recordToolCallTrace）在调用点对 MCP 工具本就生效，此处不重复记录
     * <p>
     * Execute a tool, applying timeout and result-truncation guardrails:
     * builtin tools are always guarded; whether MCP tools join the same
     * guardrails is decided by zhimesh.agent.mcp-guardrails-enabled (default
     * off, preserving the legacy direct call with zero regression; once on,
     * MCP tools go through the timeout below — including the run_workflow
     * recursion-flag propagation on pool threads — and result truncation).
     * Tracing (recordToolCallTrace) already covers MCP tools at the call
     * sites, so it is not duplicated here.
     */
    private String executeToolWithGuardrails(ToolExecutor executor, ToolExecutionRequest req,
                                             ToolContext toolContext, ZhiMeshProperties.Agent agentSettings) throws Exception {
        if (!agentSettings.isMcpGuardrailsEnabled() && executor.isMcpTool()) {
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
