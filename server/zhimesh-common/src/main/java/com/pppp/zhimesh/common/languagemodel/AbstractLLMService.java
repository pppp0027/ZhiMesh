package com.pppp.zhimesh.common.languagemodel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.helper.TtsModelContext;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
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
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

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

        Map<ToolSpecification, McpClient> requestTools = discoverRequestTools(httpRequestParams);
        ChatRequest chatRequest = createChatRequest(httpRequestParams, requestTools.keySet());
        StreamingChatModel streamingChatModel = buildStreamingChatModel(modelProperties);
        InnerStreamChatParam innerStreamChatParam = InnerStreamChatParam.builder()
                .uuid(params.getUuid())
                .user(params.getUser())
                .streamingChatModel(streamingChatModel)
                .chatRequest(chatRequest)
                .sseUuid(params.getSseUuid())
                .mcpClients(httpRequestParams.getMcpClients())
                .toolSpecificationMcpClientMap(requestTools)
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
    private static final int MAX_TOOL_CALL_DEPTH = 5;

    private void innerStreamingChat(InnerStreamChatParam params) {
        if (params.getToolCallDepth() >= MAX_TOOL_CALL_DEPTH) {
            log.error("Tool call recursion depth exceeded {} times, terminating execution", MAX_TOOL_CALL_DEPTH);
            SseManager.errorAndShutdown(new RuntimeException("Tool call count exceeded limit"), params.getSseUuid());
            closeMcpClients(params.getMcpClients());
            return;
        }
        Map<ToolSpecification, McpClient> toolSpecificationMcpClientMap =
                params.getToolSpecificationMcpClientMap();
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
                    // 如果有工具执行请求
                    // If there are tool execution requests
                    List<ToolExecutionResultMessage> toolExecutionMessages = createToolExecutionMessages(responseAiMessage, toolSpecificationMcpClientMap, params.getSseUuid());

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
                ChatResponse finalResponse = innerChat(params.getUuid(), chatModel, chatModelRequest, chatRequest);
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
     * @return ChatResponse 聊天响应 / Chat response
     */
    private ChatResponse innerChat(String uuid, ChatModel chatModel, ChatModelRequest chatModelRequest, ChatRequest chatRequest) {
        return innerChatWithDepth(uuid, chatModel, chatModelRequest, chatRequest, 0);
    }

    private ChatResponse innerChatWithDepth(String uuid, ChatModel chatModel, ChatModelRequest chatModelRequest, ChatRequest chatRequest, int depth) {
        if (depth >= MAX_TOOL_CALL_DEPTH) {
            log.error("Tool call recursion depth exceeded {} times, terminating execution", MAX_TOOL_CALL_DEPTH);
            throw new BaseException(ErrorEnum.B_LLM_SERVICE_DISABLED);
        }
        try {
            ChatResponse chatResponse = chatModel.chat(chatRequest);
            AiMessage responseAiMessage = chatResponse.aiMessage();
            if (responseAiMessage.hasToolExecutionRequests()) {
                Map<ToolSpecification, McpClient> toolSpecificationMcpClientMap = getRequestTools(chatModelRequest.getMcpClients());
                List<ToolExecutionResultMessage> toolExecutionMessages = createToolExecutionMessages(responseAiMessage, toolSpecificationMcpClientMap, null);

                AiMessage aiMessage = AiMessage.aiMessage(responseAiMessage.toolExecutionRequests());
                List<ChatMessage> messages = new ArrayList<>(chatRequest.messages());
                messages.add(aiMessage);
                messages.addAll(toolExecutionMessages);

                cacheTokenUsage(uuid, chatResponse);

                return innerChatWithDepth(uuid, chatModel, chatModelRequest, ChatRequest.builder()
                        .messages(messages)
                        .parameters(chatRequest.parameters())
                        .build(), depth + 1);
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

    private Map<ToolSpecification, McpClient> getRequestTools(List<McpClient> mcpClients) {
        Map<ToolSpecification, McpClient> tools = McpToolRegistry.discover(mcpClients,
                name -> log.warn("Duplicate MCP tool name detected; keeping the first provider, toolName:{}", name));
        // native tools
//        chatRequest.tools().forEach(tool -> {
//            ToolSpecifications.toolSpecificationsFrom(tool)
//                    .forEach(spec -> tools.put(spec,
//                            (req, mem) -> new DefaultToolExecutor(tool, req).execute(req, mem)));
//        });
        return tools;
    }

    private ChatRequest createChatRequest(ChatModelRequest httpRequestParams) {

        return createChatRequest(httpRequestParams, discoverRequestTools(httpRequestParams).keySet());
    }

    private Map<ToolSpecification, McpClient> discoverRequestTools(ChatModelRequest httpRequestParams) {
        List<McpClient> mcpClients = httpRequestParams.getMcpClients();
        if (CollectionUtils.isEmpty(mcpClients)) {
            return Collections.emptyMap();
        }

        Map<ToolSpecification, McpClient> requestTools = getRequestTools(mcpClients);
        log.info("MCP tools available, clientCount:{}, toolCount:{}, toolNames:{}",
                mcpClients.size(), requestTools.size(),
                requestTools.keySet().stream().map(ToolSpecification::name).toList());
        return requestTools;
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

    private List<ToolExecutionResultMessage> createToolExecutionMessages(AiMessage aiMessage, Map<ToolSpecification, McpClient> toolSpecificationMcpClientMap, String sseUuid) {
        List<ToolExecutionResultMessage> toolExecutionMessages = new ArrayList<>();
        aiMessage.toolExecutionRequests().forEach(req -> {
            req = parseToolRequest(req);
            log.info("MCP tool execution requested, toolName:{}", req.name());
            McpClient selectedMcpClient = null;
            for (Map.Entry<ToolSpecification, McpClient> entry : toolSpecificationMcpClientMap.entrySet()) {
                if (entry.getKey().name().equals(req.name())) {
                    selectedMcpClient = entry.getValue();
                    break;
                }
            }
            if (null == selectedMcpClient) {
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req,
                        "No Tool executor found for this tool request"));
                return;
            }
            long toolStart = System.currentTimeMillis();
            try {
                final ToolExecutionResult toolResult = selectedMcpClient.executeTool(req);
                long toolDuration = System.currentTimeMillis() - toolStart;
                final String result = toolResult.resultText();
                log.info("MCP tool execution completed, toolName:{}, resultLength:{}, duration:{}ms",
                        req.name(), result == null ? 0 : result.length(), toolDuration);
                SseManager.sendToolCall(sseUuid, req.name(), toolDuration, true);
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, result));
            } catch (Exception e) {
                long toolDuration = System.currentTimeMillis() - toolStart;
                log.warn("MCP tool execution failed, toolName:{}, duration:{}ms, errorType:{}",
                        req.name(), toolDuration, e.getClass().getSimpleName());
                SseManager.sendToolCall(sseUuid, req.name(), toolDuration, false);
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, e.getMessage()));
            }
        });
        return toolExecutionMessages;
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
