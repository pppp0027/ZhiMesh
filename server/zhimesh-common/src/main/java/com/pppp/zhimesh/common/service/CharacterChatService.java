package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterMessage;
import com.pppp.zhimesh.common.entity.LLMCallRecord;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ChatMessageRoleEnum;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.file.FileOperatorContext;
import com.pppp.zhimesh.common.file.LocalFileUtil;
import com.pppp.zhimesh.common.helper.AsrModelContext;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.QuotaHelper;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.memory.longterm.LongTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryWindow;
import com.pppp.zhimesh.common.memory.vo.MemoryAddParam;
import com.pppp.zhimesh.common.rag.ZhiMeshEmbeddingStoreContentRetriever;
import com.pppp.zhimesh.common.rag.DeduplicatingContentRetriever;
import com.pppp.zhimesh.common.rag.GraphStoreContentRetriever;
import com.pppp.zhimesh.common.rag.bm25.Bm25ContentRetriever;
import com.pppp.zhimesh.common.rag.intent.MemoryRetrievalPolicy;
import com.pppp.zhimesh.common.util.*;
import com.pppp.zhimesh.common.util.NumberUtil;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ws.schild.jave.info.MultimediaInfo;

import java.util.*;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.*;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CHARACTER_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.B_MESSAGE_NOT_FOUND;

/**
 * Chat orchestration service: orchestrates the full SSE/blocking chat pipeline.
 *
 * <p>Agent = Character in execution. This service coordinates:
 * AgentService (LLM pipeline), CharacterService (Character data), CharacterMessageService (message CRUD).</p>
 *
 * <p>Dependency direction: CharacterChatService → AgentService → CharacterService → CharacterMessageService</p>
 */
@Slf4j
@Service
public class CharacterChatService {

    @Lazy
    @Resource
    private CharacterChatService self;

    @Resource
    private CharacterService characterService;

    @Resource
    private CharacterMessageService characterMessageService;

    @Resource
    private ChatContextResolver chatContextResolver;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ZhiMeshProperties adiProperties;

    @Resource
    private ShortTermMemoryService shortTermMemoryService;
    @Resource
    private ShortTermMemoryTurnCoordinator shortTermMemoryTurnCoordinator;

    @Resource
    private AgentService agentService;

    @Resource
    private QuotaHelper quotaHelper;

    @Resource
    private UserDayCostService userDayCostService;

    @Resource
    private SseManager sseManager;

    @Resource
    private FileService fileService;

    @Resource
    private AsyncTaskExecutor chatExecutor;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource
    private LLMCallRecordService llmCallRecordService;

    public SseEmitter sseAsk(AskReq askReq) {
        String sseUuid = UuidUtil.createShort();
        SseEmitter sseEmitter = new SseEmitter(SSE_TIMEOUT);
        User user = ThreadContext.getCurrentUser();
        if (!sseManager.checkOrComplete(user, sseUuid, sseEmitter)) { // 检查是否违反了速率控制和SSE并发控制
            return sseEmitter;
        }
        sseManager.startSse(user, sseUuid, sseEmitter, null); // 正式启动SSE
        try {
            self.asyncCheckAndChat(sseUuid, user, askReq);
        } catch (TaskRejectedException exception) {
            log.warn("Chat task rejected, userId:{}, sseUuid:{}", user.getId(), sseUuid);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage("A_SYSTEM_BUSY"));
        }
        // 异步分派核心逻辑（加载角色，asr语音，知识库检索+记忆检索，prompt拼装，构建请求参数等）
        return sseEmitter; // 立刻返回SSE连接给前端
    }

    /**
     * Blocking chat: calls AgentService for the core pipeline, then persists messages to DB.
     */
    public Map<String, Object> blockingAsk(AskReq askReq) {
        User user = ThreadContext.getExistCurrentUser();
        ChatContext chatContext = chatContextResolver.resolve(user, askReq);
        try (ShortTermMemoryTurnCoordinator.TurnLease turnLease =
                     shortTermMemoryTurnCoordinator.acquire(chatContext.shortTermMemoryId(), null)) {
        Character character = chatContext.character();

        // Resolve and persist the model that will actually be used. External blocking requests
        // commonly omit modelPlatform/modelName; leaving them blank makes message persistence
        // unable to resolve ai_model_id even though LocalAgentService selected a valid fallback.
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(
                askReq.getModelPlatform(), askReq.getModelName());
        askReq.setModelPlatform(llmService.getPlatform().getName());
        askReq.setModelName(llmService.getAiModel().getName());

        validateModelCapabilities(character, askReq, llmService);

        // Quota check
        AiModel aiModel = llmService.getAiModel();
        if (null != aiModel && !aiModel.getIsFree()) {
            ErrorEnum quotaError = quotaHelper.checkTextQuota(user);
            if (null != quotaError) {
                throw new BaseException(quotaError);
            }
        }

        // Build generic request and invoke via AgentService
        String questionUuid = UuidUtil.createShort();
        AgentRequest request = AgentRequest.builder()
                .characterUuid(character.getUuid())
                .memoryId(chatContext.shortTermMemoryId())
                .manageMemoryTurn(false)
                .modelPlatform(askReq.getModelPlatform())
                .modelName(askReq.getModelName())
                .inputText(askReq.getPrompt())
                .enableRag(true)
                .enableMcp(true)
                .enableWebSearch(true)
                .build();
        long llmStartTime = System.currentTimeMillis();
        AgentResult result = agentService.invoke(request, user, questionUuid);
        int llmDuration = NumberUtil.saturatedCastToInt(System.currentTimeMillis() - llmStartTime);

        // Persist messages to DB
        PromptMeta questionMeta = new PromptMeta(
                result.getInputTokens() != null ? result.getInputTokens() : 0, questionUuid);
        AnswerMeta answerMeta = AnswerMeta.builder()
                .inputTokens(result.getInputTokens() != null ? result.getInputTokens() : 0)
                .outputTokens(result.getOutputTokens() != null ? result.getOutputTokens() : 0)
                .duration(llmDuration)
                .uuid(UuidUtil.createShort())
                .build();
        LLMResponseContent responseContent = new LLMResponseContent(
                result.getThinking(), result.getAnswer(), null);
        self.saveAfterAiResponse(chatContext, askReq, new ArrayList<>(), responseContent,
                questionMeta, answerMeta, null, llmService, result.getMemoryWindowMaxTokens());

        // Build response
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", questionUuid);
        data.put("answer", result.getAnswer());
        data.put("conversation_uuid", chatContext.conversation() == null ? null : chatContext.conversation().getUuid());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", result.getInputTokens() != null ? result.getInputTokens() : 0);
        usage.put("completion_tokens", result.getOutputTokens() != null ? result.getOutputTokens() : 0);
        usage.put("total_tokens", (result.getInputTokens() != null ? result.getInputTokens() : 0)
                + (result.getOutputTokens() != null ? result.getOutputTokens() : 0));
        data.put("usage", usage);
        return data;
        }
    }

    private void validateModelCapabilities(Character character, AskReq askReq, AbstractLLMService llmService) {
        boolean webSearchRequested = Boolean.TRUE.equals(character.getIsEnableWebSearch());
        boolean webSearchSupported = Boolean.TRUE.equals(llmService.getAiModel().getIsSupportWebSearch())
                && llmService.supportsWebSearch();
        if (webSearchRequested && !webSearchSupported) {
            throw new BaseException(ErrorEnum.B_WEB_SEARCH_NOT_SUPPORTED);
        }
        if (CollectionUtils.isNotEmpty(askReq.getImageUrls())
                && !Arrays.stream(StringUtils.defaultString(llmService.getAiModel().getInputTypes()).split(","))
                .map(String::trim)
                .anyMatch("image"::equalsIgnoreCase)) {
            throw new BaseException(ErrorEnum.B_IMAGE_INPUT_NOT_SUPPORTED);
        }
    }

    private boolean checkModelQuota(String sseUuid, User user, AiModel aiModel) {
        try {
            // Character-count quota belongs to CharacterService creation paths so
            // existing Characters remain usable. Check quota against the model that
            // was actually selected after health-based fallback.
            if (null != aiModel && !aiModel.getIsFree()) {
                ErrorEnum errorMsg = quotaHelper.checkTextQuota(user);
                if (null != errorMsg) {
                    sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage(errorMsg.getInfo()));
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("error", e);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, e.getMessage());
            return false;
        }
        return true;
    }

    @Async("chatExecutor")
    public void asyncCheckAndChat(String sseUuid, User user, AskReq askReq) {
        try {
            executeChat(sseUuid, user, askReq);
        } catch (Exception e) {
            log.error("Async chat pipeline failed, userId:{}, characterUuid:{}, conversationUuid:{}, sseUuid:{}",
                    user.getId(), askReq.getCharacterUuid(), askReq.getConversationUuid(), sseUuid, e);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, e.getMessage());
        }
    }

    private void executeChat(String sseUuid, User user, AskReq askReq) {
        log.info("asyncCheckAndChat,userId:{}", user.getId());
        // 1.解析会话和角色
        ChatContext chatContext;
        try {
            chatContext = chatContextResolver.resolve(user, askReq);
        } catch (Exception e) {
            log.warn("Chat context resolution failed, userId:{}, characterUuid:{}, conversationUuid:{}, sseUuid:{}",
                    user.getId(), askReq.getCharacterUuid(), askReq.getConversationUuid(), sseUuid, e);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, e.getMessage());
            return;
        }
        // 2.处理“重新生成”
        Character character = chatContext.character();
        if (StringUtils.isNotBlank(askReq.getRegenerateQuestionUuid())) {
            CharacterMessage originalQuestion = getRegeneratePrompt(chatContext, askReq.getRegenerateQuestionUuid());
            askReq.setPrompt(originalQuestion.getRemark());
            askReq.setProcessedPrompt(null);
            if (CollectionUtils.isEmpty(askReq.getImageUrls()) && StringUtils.isNotBlank(originalQuestion.getAttachments())) {
                askReq.setImageUrls(Arrays.asList(originalQuestion.getAttachments().split(",")));
            }
        }

        // 3.通知前端：正在分析问题
        SseManager.sendPartial(sseUuid, SSEEventName.STATE_CHANGED, SSEEventData.STATE_QUESTION_ANALYSING);
        // 4.如果是语音输入，将音频转成文本
        // [语音功能已停用] 如需启用语音输入(ASR)，请取消下面 if 块的注释
        /*
        if (StringUtils.isNotBlank(askReq.getAudioUuid())) {
            String path = fileService.getImagePath(askReq.getAudioUuid());
            String audioText = new AsrModelContext().audioToText(path);
            if (StringUtils.isBlank(audioText)) {
                sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage("B_ASR_PARSE_FAIL"));
                return;
            }
            askReq.setPrompt(audioText);
        }
        */
        // 5.选择实际LLM（包含健康检查与降级）
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(askReq.getModelPlatform(), askReq.getModelName());
        askReq.setModelPlatform(llmService.getPlatform().getName());
        askReq.setModelName(llmService.getAiModel().getName());
        validateModelCapabilities(character, askReq, llmService);

        // 6.仅根据最终实际模型检查收费额度，避免免费模型降级到收费模型后漏检。
        if (!checkModelQuota(sseUuid, user, llmService.getAiModel())) {
            return;
        }

        // 7.如果关联了知识库，筛选出有效的知识库以待后续查询
        List<KbInfoResp> filteredKb = new ArrayList<>();
        filteredKb = characterService.filterEnableKb(user, character);
        if (!filteredKb.isEmpty()) {

            //同时发送搜索知识库事件给前端用户
            SseManager.sendPartial(sseUuid, SSEEventName.STATE_CHANGED, SSEEventData.STATE_KNOWLEDGE_SEARCHING);
        }
        //8.检索知识库和长期记忆
        List<RetrieverWrapper> retrieverWrappers = CharacterChatHelper.retrieve(
                character.getId(), filteredKb, llmService, embeddingModel, askReq.getPrompt(),
                Boolean.TRUE.equals(character.getUnderstandContextEnable())
                        ? chatContext.shortTermMemoryId() : null);

        // 9.拼装增强后的Prompt：用户问题 + 知识库检索结果 + 长期记忆（语义记忆 + 情景记忆）
        int answerContentType = getAnswerContentType(character, askReq);
        // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面一行的注释
        // boolean answerToAudio = TtsUtil.needTts(llmService.getTtsSetting(), answerContentType);
        boolean answerToAudio = false; // 语音输出已停用：始终不追加口语化提示
        String effectiveLocale = StringUtils.isNotBlank(user.getLocale())
                ? user.getLocale()
                : Objects.toString(SysConfigService.getByKey(ZhiMeshConstant.SysConfigKey.DEFAULT_LOCALE), "zh-CN");
        Pair<String, String> memoryAndKnowledge = CharacterChatHelper.buildMemoryAndKnowledge(retrieverWrappers);
        // The selected KB scope is request data in its own right. Include a
        // compact, authorized catalog in the known-information section so a
        // question such as “你有什么知识库” can be answered from the actual
        // selection even when no document chunk matches that meta-question.
        String knowledgeContext = appendKnowledgeScopeCatalog(memoryAndKnowledge.getRight(), filteredKb);
        String audioExtra = answerToAudio ? (effectiveLocale.startsWith("zh") ? PROMPT_EXTRA_AUDIO : PROMPT_EXTRA_AUDIO_EN) : "";
        String processedPrompt = PromptUtil.createPrompt(askReq.getPrompt(), memoryAndKnowledge.getLeft(), knowledgeContext, audioExtra, effectiveLocale);
        if (!Objects.equals(askReq.getPrompt(), processedPrompt)) {
            askReq.setProcessedPrompt(processedPrompt);
        }

        String questionUuid = StringUtils.isNotBlank(askReq.getRegenerateQuestionUuid()) ? askReq.getRegenerateQuestionUuid() : UuidUtil.createShort();
        // 10.构造本次SSE调用上下文
        SseAskParam sseAskParam = new SseAskParam();
        sseAskParam.setUser(user);
        sseAskParam.setUuid(questionUuid);
        sseAskParam.setModelPlatform(askReq.getModelPlatform());
        sseAskParam.setModelName(askReq.getModelName());
        sseAskParam.setSseUuid(sseUuid);
        sseAskParam.setRegenerateQuestionUuid(askReq.getRegenerateQuestionUuid());
        sseAskParam.setAnswerContentType(answerContentType);
        // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面 if 块的注释
        /*
        if (null != character.getAudioConfig() && null != character.getAudioConfig().getVoice()) {
            //如果对话配置了语音，则使用对话的语音配置
            sseAskParam.setVoice(character.getAudioConfig().getVoice().getParamName());
        }
        */
        // 11.装配System Message、短期记忆 + MCP等
        ChatModelRequest chatRequestParams = CharacterChatHelper.buildChatRequestParams(
                character, chatContext.shortTermMemoryId(),
                askReq.getProcessedPrompt() != null ? askReq.getProcessedPrompt() : askReq.getPrompt(),
                user, llmService, true, Boolean.TRUE.equals(character.getIsEnableWebSearch()), askReq.getImageUrls());
        chatRequestParams.setShortTermMemoryUserMessage(askReq.getPrompt());
        // 12.设置temperature、是否返回思考等模型参数
        sseAskParam.setHttpRequestParams(chatRequestParams);
        sseAskParam.setModelProperties(
                ChatModelBuilderProperties.builder()
                        .temperature(character.getLlmTemperature())
                        .returnThinking(chatRequestParams.getReturnThinking())
                        .build()
        );
        ShortTermMemoryTurnCoordinator.TurnLease turnLease;
        try {
            turnLease = shortTermMemoryTurnCoordinator.acquire(
                    chatContext.shortTermMemoryId(), sseUuid);
        } catch (Exception exception) {
            log.warn("Unable to acquire short-memory turn lock, memoryId:{}, sseUuid:{}",
                    chatContext.shortTermMemoryId(), sseUuid, exception);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, exception.getMessage());
            return;
        }
        try {
            long llmStartTime = System.currentTimeMillis();
            // 13.发起流式LLM调用
            sseManager.call(llmService, sseAskParam, chatExecutor, (response, questionMeta, answerMeta) -> {
                turnLease.requireValid();
                answerMeta.setDuration(NumberUtil.saturatedCastToInt(System.currentTimeMillis() - llmStartTime));

                AudioInfo audioInfo = null;
                // [语音功能已停用] 如需启用语音输出(TTS)的音频落库，请取消下面 if 块的注释
                /*
                if (StringUtils.isNotBlank(response.getAudioPath())) {

                    audioInfo = new AudioInfo();
                    MultimediaInfo multimediaInfo = LocalFileUtil.getAudioFileInfo(response.getAudioPath());
                    if (null != multimediaInfo) {
                        audioInfo.setDuration((int) multimediaInfo.getDuration() / 1000);
                    }
                    audioInfo.setPath(response.getAudioPath());
                    //存储到数据库并返回真实的URL
                    ZhiMeshFile adiFile = fileService.saveFromPath(user, response.getAudioPath());
                    audioInfo.setUuid(adiFile.getUuid());
                    audioInfo.setUrl(FileOperatorContext.getFileUrl(adiFile));
                }
                */
                boolean isRefEmbedding = false;
                boolean isRefGraph = false;
                boolean isRefMemoryEmbedding = false;
                boolean isRefBm25 = false;
                for (RetrieverWrapper wrapper : retrieverWrappers) {
                    if (RetrieveContentFrom.KNOWLEDGE_BASE.equals(wrapper.getContentFrom())) {
                        for (ContentRetriever sourceRetriever : DeduplicatingContentRetriever.unwrapSourceRetrievers(wrapper.getRetriever())) {
                            if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever embeddingStoreContentRetriever) {
                                isRefEmbedding = isRefEmbedding
                                        || !embeddingStoreContentRetriever.getRetrievedEmbeddingToScore().isEmpty();
                            } else if (sourceRetriever instanceof GraphStoreContentRetriever graphStoreContentRetriever) {
                                RefGraphDto graphDto = graphStoreContentRetriever.getGraphRef();
                                isRefGraph = isRefGraph || !graphDto.getVertices().isEmpty() || !graphDto.getEdges().isEmpty();
                            } else if (sourceRetriever instanceof Bm25ContentRetriever bm25ContentRetriever) {
                                isRefBm25 = isRefBm25 || !bm25ContentRetriever.getRetrievedHits().isEmpty();
                            }
                        }
                    } else if (RetrieveContentFrom.CHARACTER_MEMORY.equals(wrapper.getContentFrom())
                            || RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC.equals(wrapper.getContentFrom())) {
                        for (ContentRetriever sourceRetriever
                                : DeduplicatingContentRetriever.unwrapSourceRetrievers(wrapper.getRetriever())) {
                            if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever memoryRetriever) {
                                isRefMemoryEmbedding = isRefMemoryEmbedding
                                        || !memoryRetriever.getRetrievedEmbeddingToScore().isEmpty();
                            }
                        }
                    }
                }
                answerMeta.setIsRefEmbedding(isRefEmbedding);
                answerMeta.setIsRefGraph(isRefGraph);
                answerMeta.setIsRefMemoryEmbedding(isRefMemoryEmbedding);
                answerMeta.setIsRefBm25(isRefBm25);
                self.saveAfterAiResponse(chatContext, askReq, retrieverWrappers, response,
                        questionMeta, answerMeta, audioInfo, llmService,
                        chatRequestParams.getMemoryWindowMaxTokens());
                sseManager.sendComplete(user.getId(), sseUuid, questionMeta, answerMeta, audioInfo,
                        chatContext.conversation() == null ? null : chatContext.conversation().getUuid());
            });
        } catch (Exception e) {
            turnLease.close();
            log.error("Chat pipeline failed, userId:{}, sseUuid:{}", user.getId(), sseUuid, e);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, e.getMessage());
        }
    }

    @Transactional
    public void saveAfterAiResponse(ChatContext chatContext, AskReq askReq, List<RetrieverWrapper> retrievers,
                                    LLMResponseContent response, PromptMeta questionMeta, AnswerMeta answerMeta,
                                    AudioInfo audioInfo, AbstractLLMService llmService,
                                    Integer memoryWindowMaxTokens) {
        User user = chatContext.user();
        Character character = chatContext.character();
        String prompt = askReq.getPrompt();
        String characterUuid = character.getUuid();
        String modelPlatform = askReq.getModelPlatform();
        String modelName = askReq.getModelName();
        AiModel aiModel = LLMContext.getAiModel(modelPlatform, modelName);

        //Check if regenerate question
        CharacterMessage promptMsg;
        if (StringUtils.isNotBlank(askReq.getRegenerateQuestionUuid())) {
            promptMsg = getRegeneratePrompt(chatContext, askReq.getRegenerateQuestionUuid());
        } else {
            //Save new question message
            CharacterMessage question = new CharacterMessage();
            question.setUserId(user.getId());
            question.setUuid(questionMeta.getUuid());
            question.setCharacterId(character.getId());
            question.setCharacterUuid(characterUuid);
            setConversationOwnership(question, chatContext);
            question.setMessageRole(ChatMessageRoleEnum.USER.getValue());
            question.setRemark(prompt);
            question.setProcessedRemark(askReq.getProcessedPrompt());
            question.setAiModelId(aiModel.getId());
            // [语音功能已停用] 如需启用语音输入(ASR)，请取消下面两行的注释
            // question.setAudioUuid(askReq.getAudioUuid());
            // question.setAudioDuration(askReq.getAudioDuration());
            question.setAttachments(String.join(",", askReq.getImageUrls()));
            characterMessageService.save(question);

            promptMsg = characterMessageService.lambdaQuery().eq(CharacterMessage::getUuid, questionMeta.getUuid()).one();

        }

        //save response message
        CharacterMessage aiAnswer = new CharacterMessage();
        aiAnswer.setUserId(user.getId());
        aiAnswer.setUuid(answerMeta.getUuid());
        aiAnswer.setCharacterId(character.getId());
        aiAnswer.setCharacterUuid(characterUuid);
        setConversationOwnership(aiAnswer, chatContext);
        aiAnswer.setMessageRole(ChatMessageRoleEnum.ASSISTANT.getValue());
        aiAnswer.setThinkingContent(Objects.toString(response.getThinkingContent(), ""));
        aiAnswer.setRemark(response.getContent());
        //TODO: If AI response content is non-compliant, store filtered version in processedRemark; frontend should prefer processedRemark over remark
        //aiAnswer.setProcessedRemark(filteredContent);
        // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面两行的注释
        // aiAnswer.setAudioUuid(null == audioInfo ? "" : Objects.toString(audioInfo.getUuid(), ""));
        // aiAnswer.setAudioDuration(null == audioInfo ? 0 : audioInfo.getDuration());
        aiAnswer.setParentMessageId(promptMsg.getId());
        aiAnswer.setAiModelId(aiModel.getId());
        aiAnswer.setIsRefEmbedding(answerMeta.getIsRefEmbedding());
        aiAnswer.setIsRefGraph(answerMeta.getIsRefGraph());
        aiAnswer.setIsRefMemoryEmbedding(answerMeta.getIsRefMemoryEmbedding());
        aiAnswer.setIsRefBm25(answerMeta.getIsRefBm25());
        int answerContentType = getAnswerContentType(character, askReq);
        aiAnswer.setContentType(answerContentType);
        characterMessageService.save(aiAnswer);

        if (chatContext.conversation() != null) {
            if (characterMessageService.countByConversationId(chatContext.conversation().getId()) == 2) {
                conversationService.editTitle(user.getId(), chatContext.conversation().getUuid(),
                        StringUtils.substring(prompt, 0, 100));
            }
            conversationService.touch(chatContext.conversation().getId(), aiAnswer.getCreateTime());
        }

        //Save LLM call record
        LLMCallRecord callRecord = new LLMCallRecord();
        callRecord.setUuid(UuidUtil.createShort());
        callRecord.setSourceType(LLMCallRecordSourceType.CHARACTER_CHAT.getValue());
        callRecord.setSourceId(aiAnswer.getId());
        callRecord.setUserId(user.getId());
        callRecord.setModelPlatform(modelPlatform);
        callRecord.setModelName(modelName);
        callRecord.setInputTokens(answerMeta.getInputTokens());
        callRecord.setOutputTokens(answerMeta.getOutputTokens());
        callRecord.setDuration(answerMeta.getDuration());
        llmCallRecordService.saveRecord(callRecord);

        createRef(retrievers, user, aiAnswer.getId());

        calcTodayCost(user, character, questionMeta, answerMeta, aiModel.getIsFree());

        //Short-term memory
        if (Boolean.TRUE.equals(character.getUnderstandContextEnable())) {
            // reasoning_content is stored in AiMessage.thinking() via returnThinking(true),
            // and will be sent back to DeepSeek API via sendThinking(true) during multi-turn tool-call scenarios.
            // <p>
            // reasoning_content 通过 returnThinking(true) 存入 AiMessage.thinking()，
            // 并在多轮工具调用场景中通过 sendThinking(true) 自动回传 DeepSeek API。
            int maxTokens = memoryWindowMaxTokens != null && memoryWindowMaxTokens > 0
                    ? memoryWindowMaxTokens
                    : aiModel.getMaxInputTokens();
            TokenCountEstimator tokenCountEstimator = llmService.resolveTokenCountEstimator();
            ShortTermMemoryWindow.append(
                    shortTermMemoryService,
                    chatContext.shortTermMemoryId(),
                    maxTokens,
                    tokenCountEstimator,
                    AiMessage.builder().text(response.getContent()).thinking(response.getThinkingContent()).build());
        }

        if (!SpringUtil.getBean(MemoryRetrievalPolicy.class).shouldExtract(askReq.getPrompt())) {
            log.info("Skipping long-term memory extraction for a trivial turn, characterId:{}", character.getId());
            return;
        }

        // Prefer the current model for long-term memory; fall back to getFirstEnableAndFree()
        // if the current model does not support JSON structured output (e.g., vision models).
        // <p>
        // 长期记忆优先使用当前模型；如果当前模型不支持 JSON 结构化输出（如视觉模型），则降级到 getFirstEnableAndFree()。
        String memoryPlatform = modelPlatform;
        String memoryModelName = modelName;
        boolean memoryIsFreeToken = aiModel.getIsFree();
        boolean supportsJsonOutput = aiModel.getResponseFormatTypes() != null
                && aiModel.getResponseFormatTypes().contains("json_object");
        if (!supportsJsonOutput) {
            Optional<AbstractLLMService> fallback = LLMContext.getFirstEnableAndFree();
            if (fallback.isEmpty()) {
                log.warn("No available model supports JSON output, skipping long-term memory for characterId:{}", character.getId());
            } else {
                memoryPlatform = fallback.get().getPlatform().getName();
                memoryModelName = fallback.get().getAiModel().getName();
                memoryIsFreeToken = fallback.get().getAiModel().getIsFree();
                log.info("Current model {} does not support JSON output, using fallback model {} for long-term memory", modelName, memoryModelName);
                submitLongTermMemory(MemoryAddParam.builder()
                        .characterId(character.getId())
                        .modelPlatform(memoryPlatform)
                        .modelName(memoryModelName)
                        .userMessage(askReq.getPrompt())
                        .assistantMessage(response.getContent())
                        .user(user)
                        .isFreeToken(memoryIsFreeToken)
                        .sourceMsgId(aiAnswer.getId())
                        .sourceMessageTime(aiAnswer.getCreateTime())
                        .build());
            }
        } else {
            submitLongTermMemory(MemoryAddParam.builder()
                    .characterId(character.getId())
                    .modelPlatform(memoryPlatform)
                    .modelName(memoryModelName)
                    .userMessage(askReq.getPrompt())
                    .assistantMessage(response.getContent())
                    .user(user)
                    .isFreeToken(memoryIsFreeToken)
                    .sourceMsgId(aiAnswer.getId())
                    .sourceMessageTime(aiAnswer.getCreateTime())
                    .build());
        }
    }

    private static String appendKnowledgeScopeCatalog(String retrievedKnowledge, List<KbInfoResp> knowledgeBases) {
        if (knowledgeBases == null || knowledgeBases.isEmpty()) {
            return retrievedKnowledge;
        }
        StringBuilder catalog = new StringBuilder("[Attached knowledge bases]\n");
        knowledgeBases.stream()
                .filter(Objects::nonNull)
                // System/preset knowledge is intentionally non-disclosable;
                // it remains available to retrieval but is not listed to the
                // user-facing answer model.
                .filter(kb -> !Boolean.TRUE.equals(kb.getIsSystem()))
                .forEach(kb -> catalog.append("- ")
                        .append(StringUtils.defaultIfBlank(kb.getTitle(), kb.getUuid()))
                        .append(StringUtils.isBlank(kb.getRemark()) ? "" : ": " + kb.getRemark())
                        .append("\n"));
        catalog.append("\n[Retrieved knowledge]\n").append(StringUtils.defaultString(retrievedKnowledge));
        return catalog.toString();
    }

    private void submitLongTermMemory(MemoryAddParam request) {
        try {
            longTermMemoryService.asyncAdd(request);
        } catch (TaskRejectedException exception) {
            // Long-term memory is best-effort and must never roll back the completed
            // chat response when the bounded background executor is saturated.
            log.warn("Long-term memory task rejected, characterId:{}, sourceMsgId:{}",
                    request.getCharacterId(), request.getSourceMsgId());
        }
    }

    private void calcTodayCost(User user, Character character, PromptMeta questionMeta, AnswerMeta answerMeta, boolean isFreeToken) {

        int todayTokenCost = answerMeta.getInputTokens() + answerMeta.getOutputTokens();
        try {
            // 用户级 token 累计；按 character 维度的 token 总量可在需要时从 llm_call_record 聚合得到。
            // <p>
            // User-level token accumulation. Character-level totals can be aggregated from
            // llm_call_record on demand.
            userDayCostService.appendCostToUser(user, todayTokenCost, isFreeToken);
        } catch (Exception e) {
            log.error("calcTodayCost error", e);
        }
    }

    private CharacterMessage getPromptMsgByQuestionUuid(String questionUuid, Long userId, Long characterId) {
        return characterMessageService.lambdaQuery()
                .eq(CharacterMessage::getUuid, questionUuid)
                .eq(CharacterMessage::getUserId, userId)
                .eq(CharacterMessage::getCharacterId, characterId)
                .eq(CharacterMessage::getParentMessageId, 0L)
                
                .oneOpt()
                .orElseThrow(() -> new BaseException(B_MESSAGE_NOT_FOUND));
    }

    private CharacterMessage getRegeneratePrompt(ChatContext chatContext, String questionUuid) {
        if (chatContext.conversation() != null) {
            return characterMessageService.getOwnedQuestionInConversation(
                    questionUuid, chatContext.conversation().getId(), chatContext.user().getId());
        }
        return getPromptMsgByQuestionUuid(
                questionUuid, chatContext.user().getId(), chatContext.character().getId());
    }

    private void setConversationOwnership(CharacterMessage message, ChatContext chatContext) {
        if (chatContext.conversation() == null) {
            return;
        }
        message.setConversationId(chatContext.conversation().getId());
        message.setConversationUuid(chatContext.conversation().getUuid());
    }

    private void createRef(List<RetrieverWrapper> wrappers, User user, Long msgId) {
        if (CollectionUtils.isEmpty(wrappers)) {
            return;
        }
        for (RetrieverWrapper wrapper : wrappers) {
            if (RetrieveContentFrom.KNOWLEDGE_BASE.equals(wrapper.getContentFrom())) {
                for (ContentRetriever sourceRetriever : DeduplicatingContentRetriever.unwrapSourceRetrievers(wrapper.getRetriever())) {
                    if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever embeddingRetriever) {
                        characterMessageService.createEmbeddingRefs(user, msgId, embeddingRetriever.getRetrievedEmbeddingToScore());
                    } else if (sourceRetriever instanceof GraphStoreContentRetriever graphRetriever) {
                        characterMessageService.createGraphRefs(user, msgId, graphRetriever.getGraphRef());
                    } else if (sourceRetriever instanceof Bm25ContentRetriever bm25Retriever) {
                        characterMessageService.createBm25Refs(user, msgId,
                                bm25Retriever.getRetrievedTerms(), bm25Retriever.getRetrievedHits());
                    }
                }
            } else if (RetrieveContentFrom.CHARACTER_MEMORY.equals(wrapper.getContentFrom())) {
                for (ContentRetriever sourceRetriever
                        : DeduplicatingContentRetriever.unwrapSourceRetrievers(wrapper.getRetriever())) {
                    if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever memoryRetriever) {
                        characterMessageService.createMemoryRefs(user, msgId,
                                memoryRetriever.getRetrievedEmbeddingToScore(), MemoryType.SEMANTIC);
                    }
                }
            } else if (RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC.equals(wrapper.getContentFrom())) {
                for (ContentRetriever sourceRetriever
                        : DeduplicatingContentRetriever.unwrapSourceRetrievers(wrapper.getRetriever())) {
                    if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever memoryRetriever) {
                        characterMessageService.createMemoryRefs(user, msgId,
                                memoryRetriever.getRetrievedEmbeddingToScore(), MemoryType.EPISODIC);
                    }
                }
            }
        }
    }

    /**
     * 获取响应内容类型
     *
     * @param character 对话
     * @param askReq    请求参数
     * @return 响应内容类型
     */
    private int getAnswerContentType(Character character, AskReq askReq) {
        int answerContentType = character.getAnswerContentType();
        //If response content type is auto and user input is audio, set response content type to audio
        //如果设置了响应内容类型为自动，并且用户输入是音频，则响应内容类型设置为音频
        // [语音功能已停用] 如需启用语音输入(ASR)自动切换音频输出，请取消下面 if 块的注释
        /*
        if (answerContentType == ZhiMeshConstant.CharacterConstant.ANSWER_CONTENT_TYPE_AUTO && StringUtils.isNotBlank(askReq.getAudioUuid())) {
            answerContentType = ZhiMeshConstant.CharacterConstant.ANSWER_CONTENT_TYPE_AUDIO;
        }
        */
        return answerContentType;
    }
}
