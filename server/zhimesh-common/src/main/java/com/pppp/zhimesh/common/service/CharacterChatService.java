package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.entity.AgentPendingCheckpoint;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterMessage;
import com.pppp.zhimesh.common.entity.CharacterMessageToolCall;
import com.pppp.zhimesh.common.entity.LLMCallRecord;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.Workflow;
import com.pppp.zhimesh.common.enums.ChatMessageRoleEnum;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.file.FileOperatorContext;
import com.pppp.zhimesh.common.file.LocalFileUtil;
import com.pppp.zhimesh.common.helper.AsrModelContext;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.QuotaHelper;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalGrant;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalRequiredDecorator;
import com.pppp.zhimesh.common.languagemodel.tool.CharacterToolPolicy;
import com.pppp.zhimesh.common.languagemodel.tool.ChatMessageSnapshotCodec;
import com.pppp.zhimesh.common.languagemodel.tool.AskUserTool;
import com.pppp.zhimesh.common.languagemodel.tool.RequestHumanApprovalTool;
import com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool;
import com.pppp.zhimesh.common.languagemodel.tool.SearchKnowledgeTool;
import com.pppp.zhimesh.common.languagemodel.tool.SuspensionSignal;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.languagemodel.tool.ToolRagContext;
import com.pppp.zhimesh.common.mapper.CharacterMessageToolCallMapper;
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
import com.pppp.zhimesh.common.workflow.WfNodeInputConfig;
import com.pppp.zhimesh.common.workflow.WorkflowStarter;
import com.pppp.zhimesh.common.workflow.def.WfNodeIO;
import com.pppp.zhimesh.common.workflow.def.WfNodeIOText;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
import java.util.stream.Collectors;

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

    @Resource
    private CharacterMessageToolCallMapper characterMessageToolCallMapper;

    @Resource
    private WorkflowService workflowService;

    @Resource
    private WorkflowNodeService workflowNodeService;

    @Resource
    private WorkflowStarter workflowStarter;

    @Resource
    private PendingCheckpointService pendingCheckpointService;

    /**
     * 挂起恢复轮注入给“快照中无配对结果的其余协作请求”的占位文本（同轮第二个及之后的
     * ask_user 请求：挂起时未执行、未产生结果，恢复轮统一补占位，引导模型单独重发）
     * <p>
     * Placeholder text injected at resume for the snapshot's collaborative
     * requests without a paired result (the second and later ask_user requests
     * of the suspending round: neither executed nor given a result at
     * suspension); the resume round injects this uniformly, guiding the model
     * to re-ask alone.
     */
    private static final String IGNORED_PEER_RESULT_TEXT = "已被忽略，请单独重新发起";

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
                questionMeta, answerMeta, null, llmService, result.getMemoryWindowMaxTokens(), null);

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
        // 2.5 挂起恢复前置检查：会话存在 ACTIVE 挂起检查点时本条消息按恢复轮处理。
        // regenerate 重问（用户对挂起问题换问法）或检查点角色与当前会话角色不符
        // （角色被切换/重建）→ 作废检查点（SUPERSEDED）按正常轮继续；正常恢复保留
        // 检查点待第 6.5 步装配
        // 2.5 Suspension-resume pre-check: when the conversation holds an ACTIVE
        // pending checkpoint this message is treated as the resumed round. A
        // regenerate re-ask (the user rephrasing the suspended question) or a
        // checkpoint character mismatching the current conversation character
        // (character switched/rebuilt) invalidates the checkpoint (SUPERSEDED)
        // and continues as a normal round; an ordinary resume keeps the
        // checkpoint for assembly at step 6.5
        AgentPendingCheckpoint resumeCheckpoint = null;
        if (null != chatContext.conversation()) {
            AgentPendingCheckpoint activeCheckpoint =
                    pendingCheckpointService.findActive(chatContext.conversation().getId());
            if (null != activeCheckpoint) {
                boolean regenerateReAsk = StringUtils.isNotBlank(askReq.getRegenerateQuestionUuid());
                boolean characterMismatch = !Objects.equals(activeCheckpoint.getCharacterId(), character.getId());
                if (regenerateReAsk || characterMismatch) {
                    pendingCheckpointService.markSuperseded(activeCheckpoint.getId());
                    log.info("Pending checkpoint superseded before resume, checkpointId:{}, conversationId:{}, regenerateReAsk:{}, characterMismatch:{}",
                            activeCheckpoint.getId(), chatContext.conversation().getId(), regenerateReAsk, characterMismatch);
                } else {
                    resumeCheckpoint = activeCheckpoint;
                }
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
        // 6.5 挂起恢复装配（存在 ACTIVE 检查点时）：①幂等消费检查点先行（消费 CAS 的
        // 赢者独占恢复权，并发消息输掉后按正常轮走、不碰短期记忆）②赢者把用户答复
        // 原文按 understandContextEnable 门控 append 进短期记忆（跨挂起的记忆记账
        // 闭环点，失败降级不阻断恢复）③解码快照（损坏→作废+正常轮）④构造配对结果
        // （pendingRequestId←答复原文；无配对结果的协作请求←忽略占位）。任一失败都
        // 降级为正常轮，绝不因检查点问题拒绝用户消息
        // 6.5 Suspension-resume assembly (when an ACTIVE checkpoint exists):
        // (1) the idempotent consume goes first (its CAS winner owns the
        // resume; a concurrent loser takes the normal round without touching
        // short-term memory); (2) the winner appends the user's raw answer
        // into short-term memory unconditionally (context is always on — the
        // cross-suspension memory-bookkeeping closure point; an append
        // failure degrades without blocking the resume); (3) decode the
        // snapshot (corrupt → supersede + normal round); (4) build the paired
        // results (pendingRequestId ← the raw answer; collaborative requests
        // without a paired result ← the ignored placeholder). Any failure
        // degrades to a normal round — a checkpoint problem must never reject
        // the user's message
        ResumeAssembly resumeAssembly = null;
        if (null != resumeCheckpoint) {
            resumeAssembly = tryResumeFromCheckpoint(chatContext, character, askReq, resumeCheckpoint, llmService);
        }
        boolean resumed = null != resumeAssembly;

        // 7.如果关联了知识库，筛选出有效的知识库以待后续查询（恢复轮同样执行——恢复轮
        // 重建的工具集合按当前配置取可用知识库；“正在检索知识库”前端状态改由检索
        // 内部在门控决策放行后才发送，不再无条件预先发送）
        // 7. Filter the character's enabled KBs (runs on resumed rounds too —
        // the rebuilt tool set resolves KB availability per current config;
        // the "searching knowledge" frontend state is now emitted from inside
        // retrieval after the gate decision admits it, not unconditionally)
        List<KbInfoResp> filteredKb = new ArrayList<>();
        filteredKb = characterService.filterEnableKb(user, character);
        // 8.检索知识库和长期记忆；9.拼装增强后的Prompt（恢复轮跳过：消息链来自
        // 检查点快照 + 用户答复配对结果，用户答复不进入预检索与增强拼装，避免对
        // 快照链之外多跑一轮 RAG）
        // 8. Pre-retrieve KB and long-term memory; 9. build the augmented
        // prompt (skipped on a resumed round: the message chain comes from the
        // checkpoint snapshot plus the user-answer pairing, so the answer
        // neither enters pre-retrieval nor prompt augmentation — no extra RAG
        // round outside the snapshot chain)
        List<RetrieverWrapper> retrieverWrappers = resumed
                ? new ArrayList<>()
                : CharacterChatHelper.retrieve(
                        character.getId(), filteredKb, llmService, embeddingModel, askReq.getPrompt(),
                        // 上下文恒启用（2026-09-24 产品决策：understandContextEnable 开关已下线，
                        // 列保留不读），预检索恒携带短期记忆ID（无会话时为 null，语义不变）
                        // Context is always on (2026-09-24 product decision: the
                        // understandContextEnable toggle is retired, the column is
                        // kept but never read), so pre-retrieval always carries the
                        // short-term memory id (null without a conversation, unchanged)
                        chatContext.shortTermMemoryId(), false, sseUuid);

        int answerContentType = getAnswerContentType(character, askReq);
        // [语音功能已停用] 如需启用语音输出(TTS)，请取消下面一行的注释
        // boolean answerToAudio = TtsUtil.needTts(llmService.getTtsSetting(), answerContentType);
        boolean answerToAudio = false; // 语音输出已停用：始终不追加口语化提示
        if (!resumed) {
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
        // 11.装配System Message、短期记忆 + MCP等（恢复轮同样走正常装配——按当前配置
        // 新建 MCP 客户端/system prompt/工具集合；仅消息链与迭代深度被检查点覆盖）
        // 11. Assemble System Message, short-term memory + MCP (resumed rounds
        // take the normal assembly too — fresh MCP clients / system prompt /
        // tool set per current config; only the message chain and iteration
        // depth are overridden by the checkpoint)
        ChatModelRequest chatRequestParams = CharacterChatHelper.buildChatRequestParams(
                character, chatContext.shortTermMemoryId(),
                askReq.getProcessedPrompt() != null ? askReq.getProcessedPrompt() : askReq.getPrompt(),
                user, llmService, true, Boolean.TRUE.equals(character.getIsEnableWebSearch()), askReq.getImageUrls());
        chatRequestParams.setShortTermMemoryUserMessage(askReq.getPrompt());
        if (resumed) {
            chatRequestParams.setResumedMessages(resumeAssembly.resumedMessages());
            chatRequestParams.setResumedToolCallDepth(null != resumeCheckpoint.getToolCallDepth()
                    ? resumeCheckpoint.getToolCallDepth() : 0);
        }
        // 11.5 Agentic 分支：生效判定 = 兜底开关 zhimesh.agent.default-agentic-enabled &&
        // 角色 is_agentic 列值（开关关 = 全体角色回到非 agentic 现状，迁移 044 存量全开
        // 的配置回退路径）。注册集合 = 默认集（search_knowledge / run_workflow /
        // ask_user）∩ tool_policy 策略 ∩ 可用性——ask_user 无外部依赖恒可用，因此上下文
        // 构造条件收敛为“筛选后的注册集非空”（策略把三件内置工具全部拒绝时才回到非
        // agentic 行为：不构造上下文、不注册工具）。挂起接线：上下文挂 suspensionSink
        // 回调（检查点落库闭包），恢复轮继承检查点的已耗挂起次数。混合检索——现有预检索
        // （scope-gate 判定相关时的自动首检索）完全不动，工具是增量能力；agentic 未生效
        // 时不构造任何对象、不触发工作流可见性查询，行为与存量逐字节等价
        // 11.5 Agentic branch: effective = fallback switch
        // zhimesh.agent.default-agentic-enabled AND the character's is_agentic
        // column (switch off = every character back to non-agentic legacy, the
        // rollback path for migration 044's all-on stock). Registration set =
        // default set (search_knowledge / run_workflow / ask_user) ∩
        // tool_policy ∩ availability — ask_user has no external dependency and
        // is always available, so the context-building condition collapses to
        // "the filtered registration set is non-empty" (only a policy denying
        // all three builtin tools falls back to non-agentic behavior: no
        // context, no tools). Suspension wiring: the context carries a
        // suspensionSink callback (the checkpoint-persistence closure); a
        // resumed round inherits the checkpoint's consumed suspension count.
        // Hybrid retrieval — the existing pre-retrieval (the auto first search
        // when the scope gate deems it relevant) is untouched, tools are
        // additive; when agentic is not effective nothing is constructed, no
        // workflow-visibility query fires, byte-for-byte legacy behavior
        boolean agenticEffective = resolveAgentSettings().isDefaultAgenticEnabled()
                && Boolean.TRUE.equals(character.getIsAgentic());
        CharacterToolPolicy toolPolicy = CharacterToolPolicy.fromCharacter(character);
        List<RunWorkflowTool.WorkflowOption> runnableWorkflows =
                agenticEffective ? listRunnableWorkflowOptions(user) : List.of();
        List<ToolExecutor> builtinTools = agenticEffective
                ? buildBuiltinTools(filteredKb, runnableWorkflows, toolPolicy)
                : List.of();
        ToolContext toolContext = CollectionUtils.isNotEmpty(builtinTools)
                ? buildAgenticToolContext(character, user, filteredKb, llmService, chatContext.shortTermMemoryId())
                : null;
        if (null != toolContext) {
            if (resumed && null != resumeCheckpoint.getSuspensionCount()) {
                // 恢复轮继承已耗挂起次数：max-suspensions 预算跨挂起累计
                // A resumed round inherits the consumed suspension count: the
                // max-suspensions budget accumulates across suspensions
                toolContext.setSuspensionCount(resumeCheckpoint.getSuspensionCount());
            }
            if (resumed && StringUtils.isNotBlank(resumeCheckpoint.getApprovalGrant())) {
                // 结构化拒绝标记（T6 拒绝按钮契约）：恢复答复以固定前缀开头 = 用户明确
                // 拒绝，跳过凭证装配（fail-closed 硬保证——模型违背拒绝重调同工具同参数
                // 也无凭证可匹配，照常挂起转审批）；自由文本拒绝不带前缀，维持设计明文
                // 的「模型服从」口径
                // The structured rejection marker (the T6 reject-button
                // contract): a resume answer starting with the fixed prefix is
                // an explicit user refusal, so the grant is NOT armed (a hard
                // fail-closed guarantee — a re-invocation defying the refusal
                // finds no grant to match and suspends for approval again); a
                // free-text rejection carries no prefix and keeps the
                // design-documented model-obedience posture
                if (StringUtils.startsWith(askReq.getPrompt(), ApprovalRequiredDecorator.REJECTION_MARKER_PREFIX)) {
                    log.info("Resume answer carries the approval rejection marker, approval grant not armed, checkpointId:{}",
                            resumeCheckpoint.getId());
                } else {
                    // 恢复轮读回审批批准凭证（仅 MCP_APPROVAL 检查点携带）：装进请求级上下文，
                    // 需审批 MCP 装饰器据此放行「同工具同参数」的真实调用；凭证随上下文存活
                    // = 仅本恢复链有效（新一轮普通请求装配全新上下文、无凭证，须重新审批）。
                    // 损坏 JSON fail-safe 归 null = 未批准（装饰器照常拦截），绝不因凭证问题
                    // 拒绝恢复
                    // The resumed round reads the approval grant back (carried by
                    // MCP_APPROVAL checkpoints only) into the request-scoped
                    // context, on which the approval-required MCP decorator lets
                    // the "same tool, same arguments" real invocation through; the
                    // grant lives as long as the context = valid only within this
                    // resume chain (a fresh ordinary request assembles a fresh
                    // context with no grant and must re-approve). Corrupt JSON
                    // fails safe to null = not approved (the decorator keeps
                    // gating); a grant problem never rejects the resume
                    toolContext.setApprovalGrant(ApprovalGrant.fromJson(resumeCheckpoint.getApprovalGrant()));
                }
            }
            wireSuspensionSink(toolContext, chatContext, character, user);
            chatRequestParams.setBuiltinTools(builtinTools);
            sseAskParam.setToolContext(toolContext);
            log.info("Agentic mode enabled, characterId:{}, kbCount:{}, runnableWorkflowCount:{}, builtinToolNames:{}, resumed:{}",
                    character.getId(), filteredKb.size(), runnableWorkflows.size(),
                    builtinTools.stream().map(tool -> tool.spec().name()).toList(), resumed);
        }
        // 审批门装配：tool_policy 的 approvalRequiredMcpTools 写入请求参数，由
        // AbstractLLMService.discoverRequestTools 对命中的 MCP 执行器包审批装饰器。仅在
        // agentic 生效时接线（兜底开关关闭 = 回到无审批门的现状，验收标准 1 的逐字节
        // 等价）；集合为空不设置（无审批门，行为不变）
        // Approval-gate assembly: the tool_policy's approvalRequiredMcpTools
        // ride the request params, and AbstractLLMService.discoverRequestTools
        // wraps the hit MCP executors with the approval decorator. Wired only
        // while agentic is effective (the fallback switch off = back to the
        // no-gate status quo, the byte-equality of acceptance criterion 1);
        // an empty set leaves the field unset (no gate, unchanged behavior)
        if (agenticEffective && CollectionUtils.isNotEmpty(toolPolicy.getApprovalRequiredMcpTools())) {
            chatRequestParams.setApprovalRequiredTools(toolPolicy.getApprovalRequiredMcpTools());
        }
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
                // 工具检索证据（refCollector）与预检索证据合并去重后的集合：
                // is_ref_* 标志计算与落库共用同一集合，工具命中的通道同样点亮标志
                List<RetrieverWrapper> effectiveRetrievers = mergeToolCollectedRefs(retrieverWrappers, toolContext);
                boolean isRefEmbedding = false;
                boolean isRefGraph = false;
                boolean isRefMemoryEmbedding = false;
                boolean isRefBm25 = false;
                for (RetrieverWrapper wrapper : effectiveRetrievers) {
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
                // 仅当确有工具调用时随 meta 事件下发轨迹，非 Agentic 路径载荷形状不变
                if (null != toolContext && CollectionUtils.isNotEmpty(toolContext.getToolTraces())) {
                    answerMeta.setToolCalls(toolContext.getToolTraces());
                }
                // 挂起轮：随 meta 事件下发挂起载荷（类型/问题/选项/检查点 uuid；审批类
                // 再带 action/summary/riskLevel），前端据此渲染问题/审批卡片；非挂起轮
                // 保持 null 维持旧载荷形状
                // A suspending round: ship the suspension payload (kind/question/
                // options/checkpoint uuid; the approval kinds additionally carry
                // action/summary/riskLevel) on the meta event for the frontend's
                // question / approval card; null on non-suspending rounds keeps
                // the legacy payload shape
                if (null != toolContext && null != toolContext.getSuspensionSignal()) {
                    SuspensionSignal signal = toolContext.getSuspensionSignal();
                    answerMeta.setSuspension(SuspensionMeta.builder()
                            .type(null != signal.getKind() ? signal.getKind().getCode() : null)
                            .question(signal.getQuestion())
                            .options(signal.getOptions())
                            .action(signal.getAction())
                            .summary(signal.getSummary())
                            .riskLevel(signal.getRiskLevel())
                            .checkpointUuid(signal.getCheckpointUuid())
                            .build());
                }
                self.saveAfterAiResponse(chatContext, askReq, effectiveRetrievers, response,
                        questionMeta, answerMeta, audioInfo, llmService,
                        chatRequestParams.getMemoryWindowMaxTokens(), toolContext);
                sseManager.sendComplete(user.getId(), sseUuid, questionMeta, answerMeta, audioInfo,
                        chatContext.conversation() == null ? null : chatContext.conversation().getUuid());
            });
        } catch (Exception e) {
            turnLease.close();
            log.error("Chat pipeline failed, userId:{}, sseUuid:{}", user.getId(), sseUuid, e);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, e.getMessage());
        }
    }

    /**
     * 构造 Agentic 请求级工具上下文。调用条件（注册集非空 = 默认集 ∩ 策略 ∩ 可用性）由
     * 调用方判定——ask_user 恒可用后，唯一回到非 agentic 行为的情形是策略拒绝全部内置
     * 工具。检索依赖（filteredKb/llmService/embeddingModel）封装进 ToolRagContext，
     * filteredKb 已过 KnowledgeBaseAccessService 鉴权，是工具检索的唯一合法范围；
     * filteredKb 为空时 ragContext 置 null（不注册 search_knowledge，其对 null
     * ragContext 的短路兼容保持不变）。
     * <p>
     * Build the request-scoped agentic tool context. The call condition
     * (non-empty registration set = default set ∩ policy ∩ availability) is
     * decided by the caller — with ask_user always available, the only way back
     * to non-agentic behavior is a policy denying every builtin tool. Retrieval
     * dependencies (filteredKb/llmService/embeddingModel) are wrapped into
     * ToolRagContext; filteredKb is already authorization-filtered by
     * KnowledgeBaseAccessService and is the only legal retrieval scope for
     * tools. When filteredKb is empty, ragContext stays null (search_knowledge
     * is not registered; its null-ragContext short-circuit compatibility is
     * unchanged).
     *
     * @param character 角色 / Character
     * @param user      当前用户 / Current user
     * @param filteredKb 鉴权后的可用知识库 / Authorization-filtered visible KBs
     * @param llmService 实际使用的 LLM 服务 / Resolved LLM service
     * @param shortTermMemoryId 短期记忆ID / Short-term memory id
     * @return 工具上下文 / Tool context
     */
    private ToolContext buildAgenticToolContext(Character character, User user, List<KbInfoResp> filteredKb,
                                                AbstractLLMService llmService, String shortTermMemoryId) {
        boolean hasKnowledgeBases = CollectionUtils.isNotEmpty(filteredKb);
        // 记忆接线口径与预检索保持一致：上下文恒启用（2026-09-24 产品决策：
        // understandContextEnable 开关已下线，列保留不读），恒携带短期记忆ID
        // Memory wiring matches pre-retrieval: context is always on (2026-09-24
        // product decision: the understandContextEnable toggle is retired, the
        // column is kept but never read), the short-term memory id is always
        // carried
        String toolMemoryId = shortTermMemoryId;
        ToolContext.ToolContextBuilder builder = ToolContext.builder()
                .user(user)
                .characterId(character.getId())
                .memoryId(toolMemoryId)
                .toolTraces(new ArrayList<>())
                .refCollector(new ArrayList<>());
        if (hasKnowledgeBases) {
            builder.ragContext(ToolRagContext.builder()
                    .filteredKb(filteredKb)
                    .llmService(llmService)
                    .embeddingModel(embeddingModel)
                    .build());
        }
        return builder.build();
    }

    /** run_workflow 可见清单的单次查询上限（按更新时间倒序取最近 N 条，防膨胀） */
    private static final int RUNNABLE_WORKFLOW_QUERY_LIMIT = 100;

    /**
     * 按请求解析当前用户可调用的工作流清单：一次工作流列表查询（mine+public 同口径）+
     * 一次起始节点 in 查询，把每个工作流映射为 title+uuid+起始节点首个文本输入定义的
     * WorkflowOption；无可调用工作流时返回空列表
     * <p>
     * Resolve the user's runnable workflow catalog per request: one workflow
     * list query (mine+public scope) plus one start-node in-query, mapping each
     * workflow to a WorkflowOption of title+uuid+the start node's first TEXT
     * input definition; empty when nothing is runnable.
     */
    private List<RunWorkflowTool.WorkflowOption> listRunnableWorkflowOptions(User user) {
        List<Workflow> workflows = workflowService.listRunnableForUser(user, RUNNABLE_WORKFLOW_QUERY_LIMIT);
        if (CollectionUtils.isEmpty(workflows)) {
            return List.of();
        }
        Map<Long, WfNodeInputConfig> startInputConfigs = workflowNodeService.getStartNodeInputConfigs(
                workflows.stream().map(Workflow::getId).toList());
        return workflows.stream()
                .map(workflow -> toRunnableWorkflowOption(workflow, startInputConfigs.get(workflow.getId())))
                .toList();
    }

    /**
     * 工作流实体 + 起始节点输入定义 → 工具可见清单条目：取首个文本类型输入参数的
     * name/maxLength（超长截断与投递依据），并携带备注摘要与全部输入参数清单（展示名/
     * 类型/必填）——run_workflow 的目录展示与门卫前置均以此为准；无文本输入定义时
     * inputParamName 为 null
     * <p>
     * Workflow entity + start-node input definitions → one catalog entry:
     * takes the first TEXT-typed input's name/maxLength (the truncation and
     * delivery basis) and carries the remark digest plus the full input-param
     * manifest (display name/type/required) — the basis for run_workflow's
     * catalog display and input preflight; inputParamName is null when no TEXT
     * input is defined.
     */
    private static RunWorkflowTool.WorkflowOption toRunnableWorkflowOption(Workflow workflow,
                                                                           WfNodeInputConfig startInputConfig) {
        String inputParamName = null;
        Integer inputMaxLength = null;
        List<RunWorkflowTool.WorkflowParamInfo> params = new ArrayList<>();
        if (null != startInputConfig && CollectionUtils.isNotEmpty(startInputConfig.getUserInputs())) {
            for (WfNodeIO inputDef : startInputConfig.getUserInputs()) {
                params.add(new RunWorkflowTool.WorkflowParamInfo(inputDef.getName(),
                        StringUtils.defaultIfBlank(inputDef.getTitle(), inputDef.getName()),
                        inputDef.getType(), Boolean.TRUE.equals(inputDef.getRequired())));
                if (null == inputParamName && WfIODataTypeEnum.TEXT.getValue().equals(inputDef.getType())) {
                    inputParamName = inputDef.getName();
                    if (inputDef instanceof WfNodeIOText textDef && null != textDef.getMaxLength()) {
                        inputMaxLength = textDef.getMaxLength();
                    }
                }
            }
        }
        return new RunWorkflowTool.WorkflowOption(workflow.getTitle(), workflow.getUuid(),
                inputParamName, inputMaxLength, workflow.getRemark(), params);
    }

    /**
     * 按可用性与角色工具策略组装内置工具：filteredKb 非空 → search_knowledge；
     * 可调用工作流非空 → run_workflow（内部超时取 tool-timeout-ms - 5000，先于外层
     * guardrail 返回）；ask_user 与 request_human_approval 无外部依赖恒注册（挂起内核
     * 就绪，经 ToolContext.suspensionSink 落检查点；审批工具挂起事件为 approval_request）。
     * tool_policy 的 builtinDenylist 命中的工具不注册——注册集合 = 默认集 ∩ policy ∩
     * 可用性（request_human_approval 与 ask_user 同口径：默认注册、策略可单独摘除）
     * <p>
     * Assemble builtin tools by availability and the character tool policy:
     * search_knowledge when filteredKb is non-empty; run_workflow when runnable
     * workflows exist (its internal timeout is tool-timeout-ms - 5000, returning
     * ahead of the outer guardrail); ask_user and request_human_approval have
     * no external dependency and are always registered (the suspension kernel
     * is in place, persisting via ToolContext.suspensionSink; the approval
     * tool's suspension event is approval_request). Tools hit by the tool_policy
     * builtinDenylist are not registered — the registration set = default set ∩
     * policy ∩ availability (request_human_approval follows ask_user's
     * convention: registered by default, individually removable via policy).
     */
    private List<ToolExecutor> buildBuiltinTools(List<KbInfoResp> filteredKb,
                                                 List<RunWorkflowTool.WorkflowOption> runnableWorkflows,
                                                 CharacterToolPolicy toolPolicy) {
        List<ToolExecutor> builtinTools = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(filteredKb) && toolPolicy.isBuiltinAllowed(SearchKnowledgeTool.NAME)) {
            builtinTools.add(new SearchKnowledgeTool());
        }
        if (CollectionUtils.isNotEmpty(runnableWorkflows) && toolPolicy.isBuiltinAllowed(RunWorkflowTool.NAME)) {
            builtinTools.add(new RunWorkflowTool(workflowStarter, runnableWorkflows,
                    resolveRunWorkflowInternalTimeoutMs()));
        }
        if (toolPolicy.isBuiltinAllowed(AskUserTool.NAME)) {
            builtinTools.add(new AskUserTool());
        }
        if (toolPolicy.isBuiltinAllowed(RequestHumanApprovalTool.NAME)) {
            builtinTools.add(new RequestHumanApprovalTool());
        }
        return builtinTools;
    }

    /**
     * 挂起恢复轮的装配结果：resumedMessages = 检查点快照链 + 配对结果消息（快照中
     * 末条带工具请求的 AiMessage 的每个请求都有结果：pendingRequestId←用户答复原文、
     * 无配对结果的协作请求←忽略占位），由 AbstractLLMService.createChatRequest 直接
     * 作为本次请求的 messages
     * <p>
     * Assembly outcome of a resumed round: resumedMessages = the checkpoint
     * snapshot chain plus the paired result messages (every request of the
     * snapshot's last AiMessage carrying tool requests gets a result:
     * pendingRequestId ← the user's raw answer; collaborative requests without
     * a paired result ← the ignored placeholder), used directly as the
     * request's messages by AbstractLLMService.createChatRequest.
     */
    private record ResumeAssembly(List<ChatMessage> resumedMessages) {
    }

    /**
     * 挂起恢复装配（第 6.5 步实现），任一失败返回 null 按正常轮继续：
     * ①幂等消费检查点先行——消费 CAS 的赢者独占恢复权，并发到达的第二条消息在此
     * 输掉后直接按正常轮走、不向短期记忆写入任何内容（若 append 先于消费，输者的
     * 原文会永久插在赢者两轮之间，且随后撞轮次锁报错，无法自愈）；②消费赢者把答复
     * 原文无条件 append 进短期记忆（上下文恒启用）——跨挂起的记忆记账闭环点
     * （挂起轮的 AiMessage 已由 saveAfterAiResponse 记账，答复在此补记；append 失败
     * 不阻断恢复：答复已进快照配对链，本轮模型可见，仅后续轮次记忆缺这一条，warn
     * 降级记账；正常轮装配记忆窗口时会去重末尾 UserMessage，不会双写）；③解码消息
     * 快照（SnapshotDecodeException → 作废检查点 + 正常轮 fail-safe）；④构造配对
     * 结果消息。工具迭代深度不在此处理——由检查点经 ChatModelRequest.resumedToolCallDepth
     * 继承
     * <p>
     * Suspension-resume assembly (step 6.5); any failure returns null and the
     * message proceeds as a normal round: (1) the idempotent consume goes
     * first — its CAS winner owns the resume exclusively, so a concurrently
     * arriving second message loses here and takes the normal round without
     * writing anything into short-term memory (with an append-first order the
     * loser's raw text would sit forever between the winner's turns and then
     * die on the turn-lease error, with no self-healing); (2) the consume
     * winner appends the raw answer into short-term memory unconditionally
     * (context is always on) — the cross-suspension memory-bookkeeping
     * closure point (the suspending round's AiMessage was already recorded by
     * saveAfterAiResponse, the answer is recorded here; an append failure
     * never blocks the resume: the answer already rides in the snapshot
     * pairing chain, so this round's model sees it and only later turns'
     * memory misses the entry — warn + degraded bookkeeping; the normal
     * round's memory-window assembly dedups a trailing UserMessage, so a
     * later normal fallback never double-writes); (3) decode the message
     * snapshot (SnapshotDecodeException → supersede + normal-round
     * fail-safe); (4) build the paired result messages. The tool-iteration
     * depth is not handled here — it is inherited from the checkpoint via
     * ChatModelRequest.resumedToolCallDepth.
     */
    private ResumeAssembly tryResumeFromCheckpoint(ChatContext chatContext, Character character, AskReq askReq,
                                                   AgentPendingCheckpoint checkpoint, AbstractLLMService llmService) {
        String userAnswer = askReq.getPrompt();
        if (!pendingCheckpointService.consume(checkpoint.getId())) {
            log.info("Pending checkpoint no longer ACTIVE (consumed/expired/superseded concurrently), falling back to a normal round, checkpointId:{}",
                    checkpoint.getId());
            return null;
        }
        try {
            // 上下文恒启用（2026-09-24 产品决策：understandContextEnable 开关已下线，
            // 列保留不读），恢复轮无条件补记用户答复；事务/降级语义不变（append 失败
            // 仅 warn，不阻断恢复）
            // Context is always on (2026-09-24 product decision: the
            // understandContextEnable toggle is retired, the column is kept but
            // never read), so the resume round appends the user's answer
            // unconditionally; transaction/degradation semantics are unchanged
            // (an append failure only warns and never blocks the resume)
            Integer maxInputTokens = llmService.getAiModel().getMaxInputTokens();
            int maxTokens = null != maxInputTokens && maxInputTokens > 0
                    ? maxInputTokens : LLM_MAX_INPUT_TOKENS_DEFAULT;
            ShortTermMemoryWindow.append(
                    shortTermMemoryService,
                    chatContext.shortTermMemoryId(),
                    maxTokens,
                    llmService.resolveTokenCountEstimator(),
                    UserMessage.from(userAnswer));
        } catch (Exception e) {
            log.warn("Failed to append the resume answer into short-term memory, resuming anyway (the answer rides in the snapshot pairing chain), memoryId:{}",
                    chatContext.shortTermMemoryId(), e);
        }
        List<ChatMessage> snapshot;
        try {
            snapshot = ChatMessageSnapshotCodec.decode(checkpoint.getMessagesSnapshot());
        } catch (ChatMessageSnapshotCodec.SnapshotDecodeException e) {
            log.warn("Pending checkpoint snapshot undecodable, superseding and falling back to a normal round, checkpointId:{}",
                    checkpoint.getId(), e);
            pendingCheckpointService.markSuperseded(checkpoint.getId());
            return null;
        }
        List<ChatMessage> resumedMessages = buildResumeMessages(snapshot, checkpoint.getPendingRequestId(), userAnswer,
                isApprovalResume(checkpoint));
        if (null == resumedMessages) {
            log.warn("Pending tool request not found in the snapshot, superseding and falling back to a normal round, checkpointId:{}, pendingRequestId:{}",
                    checkpoint.getId(), checkpoint.getPendingRequestId());
            pendingCheckpointService.markSuperseded(checkpoint.getId());
            return null;
        }
        log.info("Chat resumed from pending checkpoint, checkpointId:{}, toolCallDepth:{}, suspensionCount:{}, snapshotMessages:{}",
                checkpoint.getId(), checkpoint.getToolCallDepth(), checkpoint.getSuspensionCount(), resumedMessages.size());
        return new ResumeAssembly(resumedMessages);
    }

    /**
     * 由检查点快照构造恢复轮消息链：定位快照中最后一条带工具请求的 AiMessage（挂起
     * 轮），其每个请求都必须有配对结果——快照中已有结果的沿用；pendingRequestId 对应
     * 请求的结果=用户答复原文（按工具结果同口径截断防超长）；其余无配对结果的请求
     * （挂起时被跳过的同轮协作请求）注入忽略占位。找不到带工具请求的 AiMessage 或
     * 匹配不到 pendingRequestId 时返回 null（调用方作废检查点按正常轮处理）
     * <p>
     * Build the resumed round's message chain from the checkpoint snapshot:
     * locate the last AiMessage of the snapshot carrying tool requests (the
     * suspending round); every one of its requests must have a paired result —
     * existing snapshot results are reused; the pendingRequestId request's
     * result is the user's raw answer (truncated with the tool-result
     * convention against oversized pastes); the remaining unpaired requests
     * (the same-round collaborative requests skipped at suspension) get the
     * ignored placeholder. Returns null when no AiMessage with tool requests
     * exists or the pendingRequestId cannot be matched (the caller supersedes
     * the checkpoint and takes the normal round).
     */
    private List<ChatMessage> buildResumeMessages(List<ChatMessage> snapshot, String pendingRequestId, String userAnswer,
                                                  boolean approvalResume) {
        AiMessage suspensionRound = null;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i) instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                suspensionRound = aiMessage;
                break;
            }
        }
        if (null == suspensionRound) {
            return null;
        }
        Set<String> pairedIds = new HashSet<>();
        for (ChatMessage message : snapshot) {
            if (message instanceof ToolExecutionResultMessage resultMessage) {
                pairedIds.add(resultMessage.id());
            }
        }
        boolean pendingFound = false;
        List<ChatMessage> resumedMessages = new ArrayList<>(snapshot);
        for (ToolExecutionRequest request : suspensionRound.toolExecutionRequests()) {
            if (pairedIds.contains(request.id())) {
                continue;
            }
            boolean isPendingRequest = !pendingFound
                    && StringUtils.isNotBlank(pendingRequestId)
                    && idMatchesPending(request.id(), pendingRequestId);
            resumedMessages.add(ToolExecutionResultMessage.from(request,
                    isPendingRequest ? resumeAnswerToolResult(userAnswer, approvalResume) : IGNORED_PEER_RESULT_TEXT));
            if (isPendingRequest) {
                pendingFound = true;
            }
        }
        return pendingFound ? resumedMessages : null;
    }

    /**
     * 恢复轮答复注入工具结果消息的最终文本：审批类挂起（APPROVAL / MCP_APPROVAL）且
     * 答复不带结构化拒绝前缀时，在截断后的答复原文外包一层机器可读引导——T8 集成剧本
     * 实测（2026-09-23）部分模型会把注入的「同意」原文当工具结果、不重调工具并虚构
     * 「已提交」；引导明确「这只是用户的审批意见、相关操作尚未实际执行」，让批准后的
     * 重调不依赖模型自觉。结构化拒绝（[APPROVAL_REJECTED] 前缀）与 ASK_USER 挂起保持
     * 原文注入：拒绝语义模型转述即可，追问的答复本身就是答案
     * <p>
     * Final tool-result text for the resumed round's user answer: for approval
     * suspensions (APPROVAL / MCP_APPROVAL) whose answer lacks the structured
     * rejection prefix, wrap the truncated raw answer in machine-readable
     * guidance — the T8 integration script (2026-09-23) observed some models
     * treating the injected bare "同意" as the tool's own result, skipping the
     * re-invocation and fabricating success; the guidance makes explicit that
     * this is only the user's approval opinion and nothing has executed yet, so
     * the post-approval re-invocation no longer leans on model obedience alone.
     * Structured rejections ([APPROVAL_REJECTED] prefix) and ASK_USER
     * suspensions keep the raw-text injection: a rejection just needs relaying,
     * and a clarifying answer is itself the answer.
     */
    private String resumeAnswerToolResult(String userAnswer, boolean approvalResume) {
        String answer = truncateResumeAnswer(userAnswer);
        if (!approvalResume || StringUtils.startsWith(userAnswer, ApprovalRequiredDecorator.REJECTION_MARKER_PREFIX)) {
            return answer;
        }
        return "用户对挂起审批的回复（原文）：「" + answer + "」。注意：这只是用户的审批意见，被审批的操作尚未实际执行；若用户同意，请重新调用相应工具完成实际执行，不要凭空声称已执行。";
    }

    /**
     * 检查点是否为审批类挂起（决定恢复答复是否包审批引导文本）
     * <p>
     * Whether the checkpoint is an approval suspension (decides whether the
     * resumed answer gets the approval guidance wrapper).
     */
    private static boolean isApprovalResume(AgentPendingCheckpoint checkpoint) {
        return PendingCheckpointKind.APPROVAL.getCode().equals(checkpoint.getKind())
                || PendingCheckpointKind.MCP_APPROVAL.getCode().equals(checkpoint.getKind());
    }

    /**
     * 挂起请求 id 与检查点 pendingRequestId 的匹配：相等，或（落库前截断到 128 的情形）
     * 检查点值是完整 id 的前缀
     * <p>
     * Match a suspended request's id against the checkpoint's pendingRequestId:
     * equal, or (for the truncated-to-128 case at persistence) the checkpoint
     * value is a prefix of the full id.
     */
    private static boolean idMatchesPending(String requestId, String pendingRequestId) {
        return StringUtils.equals(requestId, pendingRequestId)
                || (requestId.length() > pendingRequestId.length()
                        && StringUtils.startsWith(requestId, pendingRequestId));
    }

    /**
     * 恢复轮用户答复的长度防护：与 AbstractLLMService 工具结果截断同口径
     * （zhimesh.agent.tool-result-max-chars，截断时末尾追加标记）。答复以工具结果
     * 消息形态注入快照链，不经常规轮 fixedMessageTokens 的预算度量，超长原文会把
     * 恢复轮请求顶爆模型输入上限——届时检查点已消费，挂起链无法重来
     * <p>
     * Length guard for the resumed round's user answer: the same convention
     * as AbstractLLMService's tool-result truncation
     * (zhimesh.agent.tool-result-max-chars, marker appended when truncated).
     * The answer is injected into the snapshot chain as a tool-result message
     * and bypasses the ordinary round's fixedMessageTokens budgeting, so an
     * oversized paste would blow the resumed request past the model's input
     * cap — with the checkpoint already consumed, the suspension chain could
     * not be retried.
     */
    private String truncateResumeAnswer(String answer) {
        int maxChars = resolveAgentSettings().getToolResultMaxChars();
        if (null == answer || answer.length() <= maxChars) {
            return answer;
        }
        return answer.substring(0, maxChars) + "\n...[truncated]";
    }

    /**
     * 给请求级工具上下文接线挂起回调（闭包捕获会话/角色/用户，AbstractLLMService 保持
     * 无 Spring 依赖）：协作类工具挂起时由内核回调，落一条 ACTIVE 检查点并返回其 uuid。
     * 无会话上下文（conversation 为 null 的入口）不接线——ask_user 在内核里走“不支持
     * 挂起”的内联回答分支，行为安全
     * <p>
     * Wire the suspension callback onto the request-scoped tool context (the
     * closure captures conversation/character/user, keeping AbstractLLMService
     * free of Spring dependencies): when a collaborative tool suspends, the
     * kernel invokes it to persist one ACTIVE checkpoint and returns its uuid.
     * Without a conversation (conversation-null entries) nothing is wired —
     * ask_user then takes the kernel's "suspension unsupported" inline-answer
     * branch, which is safe.
     */
    private void wireSuspensionSink(ToolContext toolContext, ChatContext chatContext, Character character, User user) {
        if (null == chatContext.conversation()) {
            return;
        }
        Long conversationId = chatContext.conversation().getId();
        toolContext.setSuspensionSink((signal, messagesSnapshot, toolCallDepth, suspensionCount) ->
                pendingCheckpointService.create(
                        conversationId,
                        character.getId(),
                        user.getId(),
                        signal.getKind(),
                        signal.getToolName(),
                        signal.getRequestId(),
                        suspensionPayload(signal),
                        messagesSnapshot,
                        toolCallDepth,
                        suspensionCount,
                        // 审批批准凭证（仅 MCP_APPROVAL 携带：装饰器在挂起信号上构造；
                        // ASK_USER/APPROVAL 为 null，列留空）
                        // The approval grant (carried by MCP_APPROVAL only,
                        // built by the decorator onto the suspension signal;
                        // null — column empty — for ASK_USER/APPROVAL)
                        signal.getApprovalGrant()).getUuid());
    }

    /**
     * 检查点 payload（按挂起类型分形，键名与 AgentPendingCheckpoint.payload 列注释钉死
     * 的契约一致）：ASK_USER = question（必带）+ options（非空才带）；APPROVAL /
     * MCP_APPROVAL = action / summary（非空才带）+ risk_level（非空才带；MCP 拦截挂起
     * 无声明等级时省略）。question 文本不落审批 payload（消息行 remark 已承载，可由
     * action/summary/risk_level 重建）
     * <p>
     * Checkpoint payload (kind-shaped; the key names follow the contract
     * pinned by the AgentPendingCheckpoint.payload column comment): ASK_USER =
     * question (always) + options (only when non-empty); APPROVAL /
     * MCP_APPROVAL = action / summary (only when non-blank) + risk_level (only
     * when non-blank; omitted for MCP interceptions with no declared level).
     * The question text does not ride the approval payload (the message row's
     * remark already carries it; it is rebuildable from action/summary/
     * risk_level).
     */
    private Map<String, Object> suspensionPayload(SuspensionSignal signal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (PendingCheckpointKind.APPROVAL == signal.getKind()
                || PendingCheckpointKind.MCP_APPROVAL == signal.getKind()) {
            if (StringUtils.isNotBlank(signal.getAction())) {
                payload.put("action", signal.getAction());
            }
            if (StringUtils.isNotBlank(signal.getSummary())) {
                payload.put("summary", signal.getSummary());
            }
            if (StringUtils.isNotBlank(signal.getRiskLevel())) {
                payload.put("risk_level", signal.getRiskLevel());
            }
            return payload;
        }
        payload.put("question", signal.getQuestion());
        if (CollectionUtils.isNotEmpty(signal.getOptions())) {
            payload.put("options", signal.getOptions());
        }
        return payload;
    }

    /**
     * run_workflow 的内部等待上限：比外层工具超时（tool-timeout-ms）提前 5s 返回，留出
     * 余量先于外层 guardrail 结束，避免池线程等待被外层 cancel(true) 打断后"仍在执行中"
     * 摘要退化为工具失败；下限 1s——内部必须始终先于外层触发，绝不能被下限抬到外层之后
     * （否则 10s 下限在 tool-timeout-ms &lt; 15s 时反而让外层先 fire）。配置不可用时按
     * 默认 tool-timeout-ms=60s 推导
     * <p>
     * Internal wait cap for run_workflow: 5s ahead of the outer tool timeout
     * (tool-timeout-ms) so it returns before the outer guardrail fires and the
     * pool thread's wait is never interrupted by the outer cancel(true), which
     * would degrade the "still running" summary into a tool failure; floor 1s —
     * the internal cap must ALWAYS fire before the outer guardrail and must never
     * be lifted past it by a floor (a 10s floor did exactly that whenever
     * tool-timeout-ms &lt; 15s). Falls back to the default tool-timeout-ms=60s
     * when properties are absent.
     */
    private long resolveRunWorkflowInternalTimeoutMs() {
        ZhiMeshProperties.Agent agentSettings = null != adiProperties && null != adiProperties.getAgent()
                ? adiProperties.getAgent() : new ZhiMeshProperties.Agent();
        return Math.max(1_000L, agentSettings.getToolTimeoutMs() - 5_000L);
    }

    /**
     * 读取 Agent 工具循环配置；注入的配置不可用时回退到默认值（对齐
     * AbstractLLMService#resolveAgentSettings 的防御口径——adiProperties 在
     * 部分单测里为 null，defaultAgenticEnabled 等默认值必须照常生效）
     * <p>
     * Resolve the agent tool-loop settings; falls back to defaults when the
     * injected properties are unavailable (mirroring the defensive posture of
     * AbstractLLMService#resolveAgentSettings — adiProperties is null in some
     * unit tests, and defaults like defaultAgenticEnabled must still apply).
     */
    private ZhiMeshProperties.Agent resolveAgentSettings() {
        return null != adiProperties && null != adiProperties.getAgent()
                ? adiProperties.getAgent() : new ZhiMeshProperties.Agent();
    }

    @Transactional
    public void saveAfterAiResponse(ChatContext chatContext, AskReq askReq, List<RetrieverWrapper> retrievers,
                                    LLMResponseContent response, PromptMeta questionMeta, AnswerMeta answerMeta,
                                    AudioInfo audioInfo, AbstractLLMService llmService,
                                    Integer memoryWindowMaxTokens, ToolContext toolContext) {
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

        // Agentic 工具调用轨迹落库（retrievers 已是预检索+工具检索合并去重后的集合）
        saveToolCallTraces(toolContext, aiAnswer.getId());

        calcTodayCost(user, character, questionMeta, answerMeta, aiModel.getIsFree());

        //Short-term memory — 上下文恒启用（2026-09-24 产品决策：understandContextEnable
        //开关已下线，列保留不读），无条件 append Ai 轮记忆
        //Short-term memory — context is always on (2026-09-24 product decision:
        //the understandContextEnable toggle is retired, the column is kept but
        //never read), the Ai turn is appended unconditionally
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
     * 把工具检索命中的证据（ToolContext.refCollector）合并进预检索证据集合，返回
     * is_ref_* 标志计算与落库共用的合并视图。
     * <p>
     * 去重键 =（通道 contentFrom，片段标识）：
     * <ul>
     * <li>向量通道（知识库 embedding / 记忆 embedding）：embeddingId —— 同一片段既被预检索
     * 又被工具检索时只落一次。实现方式是按通道收集已见 id 集合，对工具 wrapper 的源检索器
     * 调用 retainRetrievedEmbeddings 就地剔除已见 id，剩余的全新 id 再并入集合（同一回答内
     * 多次工具调用之间的重复也按此顺序去重）</li>
     * <li>BM25 通道：chunkUuid —— 同一规则，retainRetrievedHits 就地剔除已见 chunk</li>
     * <li>图谱通道：无稳定片段标识（落库是 vertices/edges 快照），按原样追加，快照级重复可接受</li>
     * </ul>
     * 合并仅发生在响应完成后一次，就地修改的只是工具 wrapper 内部的检索器记账（工具结果文本
     * 早已发往模型，不受影响）；无工具上下文或 refCollector 为空时原样返回预检索集合。
     * <p>
     * Merge tool-retrieved evidence (ToolContext.refCollector) into the
     * pre-retrieval set, producing the combined view shared by the is_ref_*
     * flag computation and persistence.
     * <p>
     * Dedup key = (channel contentFrom, fragment identifier):
     * <ul>
     * <li>vector channels (KB embedding / memory embedding): embeddingId — a
     * fragment hit by both pre-retrieval and tool retrieval is persisted once.
     * Seen ids are collected per channel, then each tool wrapper's source
     * retriever has already-seen ids stripped in place via
     * retainRetrievedEmbeddings; the remaining fresh ids join the set (repeats
     * across multiple tool calls within one answer are deduped the same way,
     * in order)</li>
     * <li>BM25 channel: chunkUuid — same rule via retainRetrievedHits</li>
     * <li>graph channel: no stable fragment id (persistence is a
     * vertices/edges snapshot); appended as-is, snapshot-level repetition is
     * acceptable</li>
     * </ul>
     * The merge runs exactly once after the response completes; the in-place
     * mutation only touches the tool wrapper's internal retriever bookkeeping
     * (the tool result text has already been sent to the model). Without a
     * tool context or with an empty refCollector the pre-retrieval set is
     * returned unchanged.
     */
    private List<RetrieverWrapper> mergeToolCollectedRefs(List<RetrieverWrapper> preflightWrappers,
                                                          ToolContext toolContext) {
        if (null == toolContext || CollectionUtils.isEmpty(toolContext.getRefCollector())) {
            return preflightWrappers;
        }
        Map<String, Set<String>> channelToSeenIds = new HashMap<>();
        collectSeenEvidenceIds(preflightWrappers, channelToSeenIds);
        for (RetrieverWrapper toolWrapper : toolContext.getRefCollector()) {
            Set<String> seenIds = channelToSeenIds.computeIfAbsent(
                    StringUtils.defaultString(toolWrapper.getContentFrom()), key -> new HashSet<>());
            for (ContentRetriever sourceRetriever
                    : DeduplicatingContentRetriever.unwrapSourceRetrievers(toolWrapper.getRetriever())) {
                if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever embeddingSource) {
                    Map<String, Double> hits = embeddingSource.getRetrievedEmbeddingToScore();
                    if (null == hits || hits.isEmpty()) {
                        continue;
                    }
                    Set<String> freshIds = hits.keySet().stream()
                            .filter(embeddingId -> !seenIds.contains(embeddingId))
                            .collect(Collectors.toSet());
                    embeddingSource.retainRetrievedEmbeddings(freshIds);
                    seenIds.addAll(freshIds);
                } else if (sourceRetriever instanceof Bm25ContentRetriever bm25Source) {
                    List<Bm25ContentRetriever.Bm25RetrievedHit> hits = bm25Source.getRetrievedHits();
                    if (CollectionUtils.isEmpty(hits)) {
                        continue;
                    }
                    Set<String> freshChunkUuids = hits.stream()
                            .map(Bm25ContentRetriever.Bm25RetrievedHit::chunkUuid)
                            .filter(chunkUuid -> !seenIds.contains(chunkUuid))
                            .collect(Collectors.toSet());
                    bm25Source.retainRetrievedHits(freshChunkUuids);
                    seenIds.addAll(freshChunkUuids);
                }
            }
        }
        List<RetrieverWrapper> merged = new ArrayList<>(preflightWrappers);
        merged.addAll(toolContext.getRefCollector());
        return merged;
    }

    /**
     * 收集既有 wrapper 集合已产出的证据标识，按通道（contentFrom）分组：
     * 向量通道取 embeddingId，BM25 通道取 chunkUuid
     * <p>
     * Collect evidence identifiers already produced by the existing wrapper
     * set, grouped by channel (contentFrom): embeddingId for vector channels,
     * chunkUuid for the BM25 channel.
     */
    private void collectSeenEvidenceIds(List<RetrieverWrapper> wrappers, Map<String, Set<String>> channelToSeenIds) {
        if (CollectionUtils.isEmpty(wrappers)) {
            return;
        }
        for (RetrieverWrapper wrapper : wrappers) {
            Set<String> seenIds = channelToSeenIds.computeIfAbsent(
                    StringUtils.defaultString(wrapper.getContentFrom()), key -> new HashSet<>());
            for (ContentRetriever sourceRetriever
                    : DeduplicatingContentRetriever.unwrapSourceRetrievers(wrapper.getRetriever())) {
                if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever embeddingSource) {
                    Map<String, Double> hits = embeddingSource.getRetrievedEmbeddingToScore();
                    if (null != hits) {
                        seenIds.addAll(hits.keySet());
                    }
                } else if (sourceRetriever instanceof Bm25ContentRetriever bm25Source) {
                    List<Bm25ContentRetriever.Bm25RetrievedHit> hits = bm25Source.getRetrievedHits();
                    if (null != hits) {
                        hits.stream().map(Bm25ContentRetriever.Bm25RetrievedHit::chunkUuid).forEach(seenIds::add);
                    }
                }
            }
        }
    }

    /**
     * 把请求级工具调用轨迹批量落库到 adi_character_message_tool_call；
     * seq 沿用轨迹内已有的请求内序号，保持调用顺序
     * <p>
     * Persist the request-scoped tool-call traces into
     * adi_character_message_tool_call; seq reuses the in-request sequence
     * number carried by each trace, preserving call order.
     */
    private void saveToolCallTraces(ToolContext toolContext, Long messageId) {
        if (null == toolContext || CollectionUtils.isEmpty(toolContext.getToolTraces())) {
            return;
        }
        for (ToolCallTrace trace : toolContext.getToolTraces()) {
            CharacterMessageToolCall record = new CharacterMessageToolCall();
            record.setMessageId(messageId);
            record.setToolName(trace.getToolName());
            record.setArgs(trace.getArgs());
            record.setResultSummary(trace.getResultSummary());
            record.setDurationMs(trace.getDurationMs());
            record.setSuccess(trace.isSuccess());
            record.setSeq(trace.getSeq());
            characterMessageToolCallMapper.insert(record);
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
