package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.AgentPendingCheckpoint;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterMessage;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.enums.PendingCheckpointStatus;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalGrant;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalRequiredDecorator;
import com.pppp.zhimesh.common.languagemodel.tool.ChatMessageSnapshotCodec;
import com.pppp.zhimesh.common.languagemodel.tool.SuspensionSignal;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.rag.intent.MemoryRetrievalPolicy;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.AnswerMeta;
import com.pppp.zhimesh.common.vo.ChatContext;
import com.pppp.zhimesh.common.vo.PromptMeta;
import com.pppp.zhimesh.common.vo.SseAskParam;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂起恢复轮（CharacterChatService 第 2.5/6.5 步）的行为验证：检查点幂等消费先行
 * （CAS 赢者独占恢复权，输者不碰短期记忆）、赢者把用户答复无条件 append 进短期记忆
 * （上下文恒启用，2026-09-24 产品决策：开关已下线、列保留不读；跨挂起记账闭环，
 * 失败降级不阻断恢复）与预算继承（toolCallDepth/suspensionCount）、配对结果装配
 * （pendingRequestId←答复原文按工具结果同口径截断、无配对协作请求←忽略占位）、
 * regenerate 重问/角色不符/快照损坏/消费竞争的 fail-safe 降级（作废或直接按正常轮，
 * 绝不拒绝用户消息），以及完成回调的挂起载荷装饰。
 * <p>
 * Behavioral verification of the resumed round (CharacterChatService steps
 * 2.5/6.5): the checkpoint's idempotent consume goes first (the CAS winner
 * owns the resume; a loser never touches short-term memory); the winner
 * appends the user's answer into short-term memory unconditionally
 * (context is always on — 2026-09-24 product decision: the toggle is retired,
 * the column kept but never read; the cross-suspension bookkeeping closure,
 * degrading without blocking the resume on failure) with budget inheritance
 * (toolCallDepth/suspensionCount); the paired results are assembled
 * (pendingRequestId ← the raw answer truncated with the tool-result
 * convention; unpaired collaborative requests ← the ignored placeholder);
 * the fail-safe degradations (regenerate re-ask / character mismatch /
 * corrupt snapshot / consume race supersede or simply take the normal round,
 * never rejecting the user's message); and the completion callback's
 * suspension-payload decoration.
 */
class CharacterChatServiceResumeTest {

    private static final String SSE_UUID = "sse-resume-test";
    private static final String MODEL_PLATFORM = "test-platform";
    private static final String MODEL_NAME = "test-model";
    private static final Long CONVERSATION_ID = 301L;

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private List<AbstractLLMService> previousLlmServices;

    private CharacterChatService chatService;
    private ChatContextResolver chatContextResolver;
    private CharacterService characterService;
    private SseManager sseManager;
    private CharacterMessageService characterMessageService;
    private CharacterChatService self;
    private AbstractLLMService llmService;
    private PendingCheckpointService pendingCheckpointService;
    private ShortTermMemoryService shortTermMemoryService;

    private User user;
    private Character character;
    private Conversation conversation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        context = mock(ApplicationContext.class);
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());
        MemoryRetrievalPolicy memoryPolicy = mock(MemoryRetrievalPolicy.class);
        when(memoryPolicy.isTrivialTurn(anyString())).thenReturn(true);
        when(context.getBean(MemoryRetrievalPolicy.class)).thenReturn(memoryPolicy);
        when(context.getBean(ZhiMeshProperties.class)).thenReturn(new ZhiMeshProperties());
        ModelHealthService healthService = mock(ModelHealthService.class);
        when(healthService.isHealthy(anyString(), anyString())).thenReturn(true);
        when(context.getBean(ModelHealthService.class)).thenReturn(healthService);
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);

        llmService = mock(AbstractLLMService.class);
        ModelPlatform platform = new ModelPlatform();
        platform.setName(MODEL_PLATFORM);
        when(llmService.getPlatform()).thenReturn(platform);
        AiModel aiModel = new AiModel();
        aiModel.setId(1L);
        aiModel.setName(MODEL_NAME);
        aiModel.setPlatform(MODEL_PLATFORM);
        aiModel.setIsEnable(true);
        aiModel.setIsFree(true);
        aiModel.setIsReasoner(false);
        aiModel.setMaxInputTokens(8192);
        when(llmService.getAiModel()).thenReturn(aiModel);
        when(llmService.resolveTokenCountEstimator()).thenReturn(trivialEstimator());
        previousLlmServices = List.copyOf(LLMContext.getAllServices());
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
        LLMContext.addLLMService(llmService);

        chatService = new CharacterChatService();
        chatContextResolver = mock(ChatContextResolver.class);
        characterService = mock(CharacterService.class);
        sseManager = mock(SseManager.class);
        characterMessageService = mock(CharacterMessageService.class);
        self = mock(CharacterChatService.class);
        pendingCheckpointService = mock(PendingCheckpointService.class);
        shortTermMemoryService = mock(ShortTermMemoryService.class);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        ReflectionTestUtils.setField(chatService, "chatContextResolver", chatContextResolver);
        ReflectionTestUtils.setField(chatService, "characterService", characterService);
        ReflectionTestUtils.setField(chatService, "sseManager", sseManager);
        ReflectionTestUtils.setField(chatService, "characterMessageService", characterMessageService);
        ReflectionTestUtils.setField(chatService, "self", self);
        ReflectionTestUtils.setField(chatService, "pendingCheckpointService", pendingCheckpointService);
        ReflectionTestUtils.setField(chatService, "shortTermMemoryService", shortTermMemoryService);
        ReflectionTestUtils.setField(chatService, "embeddingModel", mock(EmbeddingModel.class));
        ReflectionTestUtils.setField(chatService, "workflowService", mock(WorkflowService.class));
        ReflectionTestUtils.setField(chatService, "workflowNodeService", mock(WorkflowNodeService.class));
        ReflectionTestUtils.setField(chatService, "workflowStarter",
                mock(com.pppp.zhimesh.common.workflow.WorkflowStarter.class));
        ReflectionTestUtils.setField(chatService, "quotaHelper", mock(com.pppp.zhimesh.common.helper.QuotaHelper.class));
        ReflectionTestUtils.setField(chatService, "shortTermMemoryTurnCoordinator",
                new ShortTermMemoryTurnCoordinator(redisTemplate, new ZhiMeshProperties(),
                        mock(ScheduledExecutorService.class)));

        user = new User();
        user.setId(7L);
        user.setUuid("user-uuid-7");
        user.setLocale("zh-CN");

        character = new Character();
        character.setId(55L);
        character.setUuid("char-uuid");
        character.setAiSystemMessage("You are a helpful assistant.");
        character.setAnswerContentType(0);
        character.setIsAgentic(Boolean.TRUE);

        conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUuid("conv-uuid-301");
        conversation.setUserId(7L);
        conversation.setCharacterId(55L);

        KbInfoResp kb = new KbInfoResp();
        kb.setUuid("kb-uuid-a");
        kb.setTitle("KB-A");
        when(characterService.filterEnableKb(user, character)).thenReturn(new ArrayList<>(List.of(kb)));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
        ReflectionTestUtils.setField(LLMContext.class, "LLM_SERVICES", previousLlmServices);
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
    }

    @Test
    void resumeConsumesCheckpointFirstThenAppendsAnswerToMemoryWithInheritedBudgets() {
        character.setUnderstandContextEnable(true);
        AgentPendingCheckpoint checkpoint = activeCheckpoint(901L, 55L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(901L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("财务部");

        // 消费先行（CAS 赢者独占恢复权），赢者再 append 答复原文进短期记忆
        // （跨挂起记账闭环点）——输者在此前已按正常轮离开，不碰短期记忆
        // The consume goes first (the CAS winner owns the resume); the winner
        // then appends the raw answer into short-term memory (the
        // cross-suspension bookkeeping point) — a loser already left for the
        // normal round before this, touching no memory
        InOrder ordering = inOrder(pendingCheckpointService, shortTermMemoryService);
        ordering.verify(pendingCheckpointService).consume(901L);
        ArgumentCaptor<List<ChatMessage>> memoryCaptor = ArgumentCaptor.forClass((Class) List.class);
        ordering.verify(shortTermMemoryService).updateMessages(eq("mem-resume"), memoryCaptor.capture());
        assertThat(memoryCaptor.getValue()).isNotEmpty();
        ChatMessage lastMemory = memoryCaptor.getValue().get(memoryCaptor.getValue().size() - 1);
        assertThat(lastMemory).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) lastMemory).singleText()).isEqualTo("财务部");

        // 恢复请求：消息链=快照+配对结果（pendingRequestId←答复原文），继承迭代深度
        // The resumed request: messages = snapshot + paired results
        // (pendingRequestId ← the raw answer), inheriting the iteration depth
        List<ChatMessage> resumedMessages = captured.param.getHttpRequestParams().getResumedMessages();
        assertThat(resumedMessages).isNotNull();
        ToolExecutionResultMessage pendingResult = (ToolExecutionResultMessage)
                resumedMessages.get(resumedMessages.size() - 1);
        assertThat(pendingResult.id()).isEqualTo("id-ask-1");
        assertThat(pendingResult.text()).isEqualTo("财务部");
        assertThat(captured.param.getHttpRequestParams().getResumedToolCallDepth()).isEqualTo(3);

        // 工具上下文：继承已耗挂起次数 + 挂起回调已接线（恢复轮可再次挂起）
        // Tool context: the consumed suspension count is inherited and the
        // suspension sink is wired (the resumed round can suspend again)
        ToolContext toolContext = captured.param.getToolContext();
        assertThat(toolContext.getSuspensionCount()).isEqualTo(1);
        assertThat(toolContext.getSuspensionSink()).isNotNull();
        // ASK_USER 检查点不带凭证：恢复轮上下文无 grant（凭证仅 MCP_APPROVAL 携带）
        // An ASK_USER checkpoint carries no grant: the resumed context holds no
        // grant (grants ride MCP_APPROVAL only)
        assertThat(toolContext.getApprovalGrant()).isNull();
    }

    @Test
    void resumeAppendsMemoryUnconditionallyEvenWithLegacyFlagOff() {
        // 上下文恒启用（2026-09-24 产品决策：understandContextEnable 开关已下线，列保留
        // 不读）：即便存量行仍为 flag=false 也无条件 append 记忆，恢复照常
        // Context is always on (2026-09-24 product decision: the
        // understandContextEnable toggle is retired, the column kept but never
        // read): the append happens unconditionally even when a legacy row
        // still carries flag=false, and the resume still proceeds
        character.setUnderstandContextEnable(false);
        AgentPendingCheckpoint checkpoint = activeCheckpoint(902L, 55L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(902L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("财务部");

        ArgumentCaptor<List<ChatMessage>> memoryCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shortTermMemoryService).updateMessages(eq("mem-resume"), memoryCaptor.capture());
        assertThat(memoryCaptor.getValue()).isNotEmpty();
        ChatMessage lastMemory = memoryCaptor.getValue().get(memoryCaptor.getValue().size() - 1);
        assertThat(lastMemory).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) lastMemory).singleText()).isEqualTo("财务部");
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNotNull();
        assertThat(captured.param.getHttpRequestParams().getResumedMessages().size()).isEqualTo(4);
    }

    @Test
    void consumeRaceDegradesToNormalRoundWithoutSupersedingOrMemoryWrite() {
        // 消费竞争失败（并发已消费）：按正常轮继续；输者不 append 短期记忆（原文由
        // 正常轮常规路径记账，且不会插在赢者两轮之间）；不作废
        // Losing the consume race (consumed concurrently): continue as a normal
        // round; the loser appends nothing into short-term memory (its text is
        // recorded by the normal round's ordinary path, and never interleaves
        // between the winner's turns); no supersede
        character.setUnderstandContextEnable(true);
        AgentPendingCheckpoint checkpoint = activeCheckpoint(903L, 55L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(903L)).thenReturn(false);

        SseAskParamCapture captured = runExecuteChat("财务部");

        verify(shortTermMemoryService, never()).updateMessages(any(), any());
        verify(pendingCheckpointService, never()).markSuperseded(any());
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNull();
        assertThat(captured.param.getHttpRequestParams().getResumedToolCallDepth()).isNull();
    }

    @Test
    void memoryAppendFailureAfterConsumeDoesNotBlockTheResume() {
        // 消费赢者的记忆 append 失败（Redis 抖动等）：恢复照常进行——答复已进快照
        // 配对链，本轮模型可见，仅后续轮次记忆缺这一条（warn 降级记账）
        // The consume winner's memory append fails (Redis hiccup etc.): the
        // resume still proceeds — the answer already rides in the snapshot
        // pairing chain so this round's model sees it; only later turns'
        // memory misses the entry (warn + degraded bookkeeping)
        character.setUnderstandContextEnable(true);
        AgentPendingCheckpoint checkpoint = activeCheckpoint(909L, 55L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(909L)).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(shortTermMemoryService)
                .updateMessages(eq("mem-resume"), any());

        SseAskParamCapture captured = runExecuteChat("财务部");

        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNotNull();
        ToolExecutionResultMessage pendingResult = (ToolExecutionResultMessage)
                captured.param.getHttpRequestParams().getResumedMessages().stream()
                        .filter(message -> message instanceof ToolExecutionResultMessage)
                        .filter(message -> "id-ask-1".equals(((ToolExecutionResultMessage) message).id()))
                        .findFirst().orElseThrow();
        assertThat(pendingResult.text()).isEqualTo("财务部");
        verify(pendingCheckpointService, never()).markSuperseded(any());
    }

    @Test
    void oversizedResumeAnswerIsTruncatedWithToolResultConvention() {
        // 超长答复粘贴：按工具结果同口径截断（toolResultMaxChars 默认 4000，截断加
        // 末尾标记）——答复以工具结果形态注入快照链，不经常规 fixedMessageTokens
        // 度量，不截断会把恢复轮请求顶爆模型输入上限
        // An oversized pasted answer: truncated with the tool-result convention
        // (toolResultMaxChars default 4000, marker appended) — the answer is
        // injected into the snapshot chain as a tool result, bypassing the
        // ordinary fixedMessageTokens budgeting; untruncated it would blow the
        // resumed request past the model's input cap
        AgentPendingCheckpoint checkpoint = activeCheckpoint(910L, 55L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(910L)).thenReturn(true);

        String oversizedAnswer = "长".repeat(4500);
        SseAskParamCapture captured = runExecuteChat(oversizedAnswer);

        ToolExecutionResultMessage pendingResult = (ToolExecutionResultMessage)
                captured.param.getHttpRequestParams().getResumedMessages().stream()
                        .filter(message -> message instanceof ToolExecutionResultMessage)
                        .filter(message -> "id-ask-1".equals(((ToolExecutionResultMessage) message).id()))
                        .findFirst().orElseThrow();
        assertThat(pendingResult.text()).isEqualTo("长".repeat(4000) + "\n...[truncated]");
    }

    @Test
    void corruptSnapshotSupersedesCheckpointAndFallsBackToNormalRound() {
        character.setUnderstandContextEnable(true);
        AgentPendingCheckpoint checkpoint = activeCheckpoint(904L, 55L, "id-ask-1", "{corrupt-json");
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(904L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("财务部");

        verify(pendingCheckpointService).markSuperseded(904L);
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNull();
    }

    @Test
    void pendingRequestMissingFromSnapshotSupersedesCheckpointAndFallsBack() {
        AgentPendingCheckpoint checkpoint = activeCheckpoint(905L, 55L, "id-nope",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(905L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("财务部");

        verify(pendingCheckpointService).markSuperseded(905L);
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNull();
    }

    @Test
    void regenerateReAskSupersedesCheckpointWithoutResuming() {
        AgentPendingCheckpoint checkpoint = activeCheckpoint(906L, 55L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        CharacterMessage originalQuestion = new CharacterMessage();
        originalQuestion.setRemark("换个问法重问");
        when(characterMessageService.getOwnedQuestionInConversation("q-1", CONVERSATION_ID, 7L))
                .thenReturn(originalQuestion);

        AskReq askReq = askReq();
        askReq.setRegenerateQuestionUuid("q-1");
        runExecuteChat(askReq);

        verify(pendingCheckpointService).markSuperseded(906L);
        verify(pendingCheckpointService, never()).consume(any());
        verify(shortTermMemoryService, never()).updateMessages(any(), any());
    }

    @Test
    void characterMismatchSupersedesCheckpointWithoutResuming() {
        // 检查点角色与当前会话角色不符（角色被切换/重建）：作废并按正常轮
        // Checkpoint character mismatching the conversation character
        // (switched/rebuilt): supersede and take the normal round
        AgentPendingCheckpoint checkpoint = activeCheckpoint(907L, 999L, "id-ask-1",
                suspensionSnapshot("id-w", "id-ask-1"));
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);

        SseAskParamCapture captured = runExecuteChat("财务部");

        verify(pendingCheckpointService).markSuperseded(907L);
        verify(pendingCheckpointService, never()).consume(any());
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNull();
    }

    @Test
    void unpairedCollaborativePeersGetIgnoredPlaceholderOnResume() {
        // 挂起轮的第二个协作请求（id-a2）无配对结果：恢复轮注入忽略占位；
        // pendingRequestId(id-ask-1)←答复原文；已执行同伴(id-w)沿用快照结果
        // The suspending round's second collaborative request (id-a2) has no
        // paired result: the resume injects the ignored placeholder;
        // pendingRequestId (id-ask-1) ← the raw answer; the executed peer
        // (id-w) keeps its snapshot result
        AgentPendingCheckpoint checkpoint = activeCheckpoint(908L, 55L, "id-ask-1",
                multiCollaborativeSnapshot());
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(908L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("财务部");

        List<ToolExecutionResultMessage> results = captured.param.getHttpRequestParams().getResumedMessages().stream()
                .filter(message -> message instanceof ToolExecutionResultMessage)
                .map(message -> (ToolExecutionResultMessage) message)
                .toList();
        assertThat(results)
                .extracting(ToolExecutionResultMessage::id, ToolExecutionResultMessage::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("id-w", "sunny"),
                        org.assertj.core.groups.Tuple.tuple("id-a2", "已被忽略，请单独重新发起"),
                        org.assertj.core.groups.Tuple.tuple("id-ask-1", "财务部"));
    }

    @Test
    void completionCallbackDecoratesSuspensionMetaFromToolContextSignal() {
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(null);
        SseAskParamCapture captured = runExecuteChat("普通消息");

        ToolContext toolContext = captured.param.getToolContext();
        // 未挂起的普通轮：meta 不带挂起载荷（旧载荷形状不变）
        // An ordinary non-suspending round: the meta carries no suspension
        // payload (legacy payload shape)
        AnswerMeta plainMeta = AnswerMeta.builder().build();
        captured.callback.accept(new LLMResponseContent(null, "answer", null),
                new PromptMeta(1, "q-uuid"), plainMeta);
        assertThat(plainMeta.getSuspension()).isNull();

        // 挂起信号置位（含检查点 uuid 回填）：完成回调装饰挂起载荷
        // With the suspension signal raised (checkpoint uuid backfilled): the
        // completion callback decorates the suspension payload
        toolContext.setSuspensionSignal(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.ASK_USER)
                .toolName("ask_user")
                .requestId("id-ask-1")
                .question("你负责哪个部门？")
                .options(List.of("财务", "法务"))
                .checkpointUuid("ckpt-uuid-r")
                .build());
        AnswerMeta suspendedMeta = AnswerMeta.builder().build();
        captured.callback.accept(new LLMResponseContent(null, "你负责哪个部门？", null),
                new PromptMeta(1, "q-uuid"), suspendedMeta);
        assertThat(suspendedMeta.getSuspension()).isNotNull();
        assertThat(suspendedMeta.getSuspension().getType()).isEqualTo("ASK_USER");
        assertThat(suspendedMeta.getSuspension().getQuestion()).isEqualTo("你负责哪个部门？");
        assertThat(suspendedMeta.getSuspension().getOptions()).containsExactly("财务", "法务");
        assertThat(suspendedMeta.getSuspension().getCheckpointUuid()).isEqualTo("ckpt-uuid-r");

        // 审批挂起（MCP_APPROVAL）：meta 挂起载荷追加 action/summary/riskLevel 三个可空
        // 字段（T6 审批卡渲染依据）；ASK_USER 形态这些字段为 null
        // An approval suspension (MCP_APPROVAL): the meta suspension payload
        // adds the nullable action/summary/riskLevel triple (T6's approval-card
        // rendering basis); in the ASK_USER shape they stay null
        toolContext.setSuspensionSignal(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.MCP_APPROVAL)
                .toolName("submit_expense_report")
                .requestId("id-mcp-1")
                .question("该操作需要人工审批")
                .action("调用工具 submit_expense_report")
                .summary("{\"title\":\"部门聚餐报销\",\"amount\":3200}")
                .riskLevel("HIGH")
                .checkpointUuid("ckpt-uuid-ap")
                .build());
        AnswerMeta approvalMeta = AnswerMeta.builder().build();
        captured.callback.accept(new LLMResponseContent(null, "该操作需要人工审批", null),
                new PromptMeta(1, "q-uuid"), approvalMeta);
        assertThat(approvalMeta.getSuspension().getType()).isEqualTo("MCP_APPROVAL");
        assertThat(approvalMeta.getSuspension().getAction()).isEqualTo("调用工具 submit_expense_report");
        assertThat(approvalMeta.getSuspension().getSummary())
                .isEqualTo("{\"title\":\"部门聚餐报销\",\"amount\":3200}");
        assertThat(approvalMeta.getSuspension().getRiskLevel()).isEqualTo("HIGH");
        assertThat(suspendedMeta.getSuspension().getAction()).isNull();
        assertThat(suspendedMeta.getSuspension().getRiskLevel()).isNull();
    }

    @Test
    void resumeAssemblesApprovalGrantIntoToolContextForMcpApprovalCheckpoint() {
        // MCP_APPROVAL 检查点的恢复轮：approval_grant JSON 读回为 ApprovalGrant 装进
        // 请求级上下文（凭证仅本恢复链有效），需审批装饰器据此放行同工具同参数重调；
        // 恢复消息链照常装配（凭证装配不破坏既有恢复路径）
        // The resumed round of an MCP_APPROVAL checkpoint: the approval_grant
        // JSON is read back into an ApprovalGrant on the request-scoped context
        // (valid only within this resume chain), the basis for the decorator's
        // same-tool-same-arguments pass-through; the resumed message chain is
        // assembled as usual (the grant wiring never breaks the resume path)
        ApprovalGrant grant = ApprovalGrant.forArguments("submit_expense_report",
                "{\"title\":\"部门聚餐报销\",\"amount\":3200}");
        AgentPendingCheckpoint checkpoint = mcpApprovalCheckpoint(911L, grant.toJson());
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(911L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("同意");

        ToolContext toolContext = captured.param.getToolContext();
        assertThat(toolContext).isNotNull();
        assertThat(toolContext.getApprovalGrant()).isNotNull();
        assertThat(toolContext.getApprovalGrant().matches("submit_expense_report",
                "{\"title\":\"部门聚餐报销\",\"amount\":3200}")).isTrue();
        assertThat(toolContext.getApprovalGrant().matches("submit_expense_report",
                "{\"title\":\"部门聚餐报销\",\"amount\":32000}")).isFalse();
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNotNull();
        ToolExecutionResultMessage approved = (ToolExecutionResultMessage)
                captured.param.getHttpRequestParams().getResumedMessages().stream()
                        .filter(message -> message instanceof ToolExecutionResultMessage)
                        .filter(message -> "id-mcp-1".equals(((ToolExecutionResultMessage) message).id()))
                        .findFirst().orElseThrow();
        // 审批类恢复答复包机器可读引导（T8 bug#2：裸「同意」被当工具结果致模型不重调
        // 工具虚构成功）：原文保留 + 「尚未实际执行/请重新调用」引导在场
        // An approval resume answer carries the machine-readable guidance (T8
        // bug#2: a bare "同意" read as the tool result made the model skip the
        // re-invocation and fabricate success): raw answer kept, with the
        // "nothing has executed / re-invoke" guidance present
        assertThat(approved.text()).contains("「同意」").contains("尚未实际执行").contains("重新调用");
    }

    @Test
    void approvalResumeAnswerIsWrappedWithReInvocationGuidance() {
        // T8 剧本 bug#2 回归锁：审批类挂起（APPROVAL / MCP_APPROVAL 两形态）且答复不带
        // 拒绝前缀时，注入文本=引导包装的答复原文（防模型把「同意」当工具结果虚构成功）；
        // ASK_USER 挂起与拒绝前缀答复保持原文注入（后者由 rejection marker 测试覆盖）
        // T8-script bug#2 regression lock: an approval suspension (both APPROVAL
        // and MCP_APPROVAL shapes) whose answer lacks the rejection prefix gets
        // the guidance-wrapped raw answer (so a bare "同意" is never mistaken
        // for the tool result); ASK_USER suspensions and rejection-prefixed
        // answers keep the raw text (the latter covered by the marker test)
        AgentPendingCheckpoint approvalCheckpoint = mcpApprovalCheckpoint(914L, null);
        approvalCheckpoint.setKind(PendingCheckpointKind.APPROVAL.getCode());
        approvalCheckpoint.setPendingToolName("request_human_approval");
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(approvalCheckpoint);
        when(pendingCheckpointService.consume(914L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("同意");

        ToolExecutionResultMessage wrapped = (ToolExecutionResultMessage)
                captured.param.getHttpRequestParams().getResumedMessages().stream()
                        .filter(message -> message instanceof ToolExecutionResultMessage)
                        .filter(message -> "id-mcp-1".equals(((ToolExecutionResultMessage) message).id()))
                        .findFirst().orElseThrow();
        assertThat(wrapped.text()).contains("「同意」").contains("尚未实际执行").contains("重新调用");
    }

    @Test
    void corruptApprovalGrantFailsSafeToNullWithoutBlockingTheResume() {
        // approval_grant 列损坏：fail-safe 归 null（无凭证=未批准，装饰器照常拦截），
        // 恢复轮照常进行——凭证问题绝不拒绝用户的恢复消息
        // A corrupt approval_grant column fails safe to null (no grant = not
        // approved; the decorator keeps gating) while the resume proceeds — a
        // grant problem never rejects the user's resume message
        AgentPendingCheckpoint checkpoint = mcpApprovalCheckpoint(912L, "{\"toolName\":\"broken");
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(912L)).thenReturn(true);

        SseAskParamCapture captured = runExecuteChat("同意");

        assertThat(captured.param.getToolContext().getApprovalGrant()).isNull();
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNotNull();
    }

    @Test
    void rejectionMarkerPrefixSkipsGrantArmingWhileResumeStillProceeds() {
        // T6 拒绝按钮的文本契约：恢复答复以 [APPROVAL_REJECTED] 固定前缀开头 → 检查点
        // 虽持有效凭证 JSON 也不装配（fail-closed 硬保证：模型违背拒绝重调同工具同参数
        // 也无凭证可匹配，照常挂起转审批）；拒绝文本本身照常作为配对结果注入恢复链
        // （模型读到拒绝理由正常转述）
        // The T6 reject button's text contract: the resumed answer starts with
        // the fixed [APPROVAL_REJECTED] prefix → the grant is not armed even
        // though the checkpoint holds a valid grant JSON (a hard fail-closed
        // guarantee: a re-invocation defying the rejection finds no grant to
        // match and suspends again); the rejection text itself still rides the
        // resumed chain as the paired result (the model reads the reason and
        // relays it)
        ApprovalGrant grant = ApprovalGrant.forArguments("submit_expense_report",
                "{\"title\":\"部门聚餐报销\",\"amount\":3200}");
        AgentPendingCheckpoint checkpoint = mcpApprovalCheckpoint(913L, grant.toJson());
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(checkpoint);
        when(pendingCheckpointService.consume(913L)).thenReturn(true);

        String rejection = ApprovalRequiredDecorator.REJECTION_MARKER_PREFIX + " 金额超预算，不予批准";
        SseAskParamCapture captured = runExecuteChat(rejection);

        assertThat(captured.param.getToolContext().getApprovalGrant()).isNull();
        assertThat(captured.param.getHttpRequestParams().getResumedMessages()).isNotNull();
        ToolExecutionResultMessage rejected = (ToolExecutionResultMessage)
                captured.param.getHttpRequestParams().getResumedMessages().stream()
                        .filter(message -> message instanceof ToolExecutionResultMessage)
                        .filter(message -> "id-mcp-1".equals(((ToolExecutionResultMessage) message).id()))
                        .findFirst().orElseThrow();
        assertThat(rejected.text()).isEqualTo(rejection);
    }

    @Test
    void suspensionSinkPersistsTheSignalApprovalGrantVerbatim() {
        // 挂起回调的落库参数完整性：MCP_APPROVAL 信号的凭证原样（同引用）传给检查点
        // create 的 approvalGrant 位；APPROVAL 显式审批/ASK_USER 挂起该位为 null（列留空）
        // Persist-parameter integrity of the suspension sink: an MCP_APPROVAL
        // signal's grant reaches the checkpoint create call's approvalGrant
        // slot verbatim (same reference); explicit APPROVAL and ASK_USER
        // suspensions pass null there (column empty)
        when(pendingCheckpointService.findActive(CONVERSATION_ID)).thenReturn(null);
        SseAskParamCapture captured = runExecuteChat("普通消息");
        ToolContext toolContext = captured.param.getToolContext();

        AgentPersistCheckpointStub stub = new AgentPersistCheckpointStub();
        when(pendingCheckpointService.create(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(stub.checkpoint);

        ApprovalGrant grant = ApprovalGrant.forArguments("submit_expense_report",
                "{\"title\":\"部门聚餐报销\",\"amount\":3200}");
        toolContext.getSuspensionSink().persist(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.MCP_APPROVAL)
                .toolName("submit_expense_report")
                .requestId("id-mcp-1")
                .question("该操作需要人工审批")
                .action("调用工具 submit_expense_report")
                .summary("{\"title\":\"部门聚餐报销\",\"amount\":3200}")
                .approvalGrant(grant)
                .build(), List.of(), 1, 1);

        ArgumentCaptor<ApprovalGrant> grantCaptor = ArgumentCaptor.forClass(ApprovalGrant.class);
        verify(pendingCheckpointService).create(any(), any(), any(), eq(PendingCheckpointKind.MCP_APPROVAL),
                eq("submit_expense_report"), any(), any(), any(), any(), any(), grantCaptor.capture());
        assertThat(grantCaptor.getValue()).isSameAs(grant);

        toolContext.getSuspensionSink().persist(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.APPROVAL)
                .toolName("request_human_approval")
                .requestId("id-ap-1")
                .question("该操作需要人工审批")
                .action("提交报销单")
                .summary("金额 3200 元")
                .riskLevel("MEDIUM")
                .build(), List.of(), 1, 2);
        verify(pendingCheckpointService).create(any(), any(), any(), eq(PendingCheckpointKind.APPROVAL),
                eq("request_human_approval"), any(), any(), any(), any(), any(), isNull());
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private SseAskParamCapture runExecuteChat(String userAnswer) {
        return runExecuteChat(askReqWithPrompt(userAnswer));
    }

    private SseAskParamCapture runExecuteChat(AskReq askReq) {
        when(chatContextResolver.resolve(user, askReq))
                .thenReturn(new ChatContext(user, character, conversation, "req-uuid", "mem-resume"));
        ReflectionTestUtils.invokeMethod(chatService, "executeChat", SSE_UUID, user, askReq);
        return captureAskParam();
    }

    private AskReq askReq() {
        return askReqWithPrompt("财务部");
    }

    private AskReq askReqWithPrompt(String prompt) {
        AskReq askReq = new AskReq();
        askReq.setCharacterUuid(character.getUuid());
        askReq.setPrompt(prompt);
        askReq.setModelPlatform(MODEL_PLATFORM);
        askReq.setModelName(MODEL_NAME);
        return askReq;
    }

    @SuppressWarnings("unchecked")
    private SseAskParamCapture captureAskParam() {
        ArgumentCaptor<SseAskParam> paramCaptor = ArgumentCaptor.forClass(SseAskParam.class);
        ArgumentCaptor<TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta>> callbackCaptor =
                ArgumentCaptor.forClass((Class) TriConsumer.class);
        verify(sseManager).call(eq(llmService), paramCaptor.capture(), any(), callbackCaptor.capture());
        return new SseAskParamCapture(paramCaptor.getValue(), callbackCaptor.getValue());
    }

    /** 快照：用户问题 + [weather 同伴, ask_user 挂起请求]，weather 已有结果 / Snapshot: user turn + [weather peer, ask_user pending], weather has its result */
    private static String suspensionSnapshot(String weatherId, String pendingId) {
        ToolExecutionRequest weatherRequest = ToolExecutionRequest.builder()
                .id(weatherId).name("weather").arguments("{\"city\":\"广州\"}").build();
        ToolExecutionRequest askRequest = ToolExecutionRequest.builder()
                .id(pendingId).name("ask_user").arguments("{\"question\":\"你负责哪个部门？\"}").build();
        return ChatMessageSnapshotCodec.encode(List.of(
                UserMessage.from("帮我整理报销"),
                AiMessage.aiMessage(List.of(weatherRequest, askRequest)),
                ToolExecutionResultMessage.from(weatherRequest, "sunny")));
    }

    /** 快照：同轮两个协作请求，第二个将被忽略 / Snapshot: two collaborative requests in one round, the second gets ignored */
    private static String multiCollaborativeSnapshot() {
        ToolExecutionRequest weatherRequest = ToolExecutionRequest.builder()
                .id("id-w").name("weather").arguments("{}").build();
        ToolExecutionRequest ignoredAsk = ToolExecutionRequest.builder()
                .id("id-a2").name("ask_user").arguments("{\"question\":\"第二个？\"}").build();
        ToolExecutionRequest pendingAsk = ToolExecutionRequest.builder()
                .id("id-ask-1").name("ask_user").arguments("{\"question\":\"第一个？\"}").build();
        return ChatMessageSnapshotCodec.encode(List.of(
                UserMessage.from("帮我整理报销"),
                AiMessage.aiMessage(List.of(weatherRequest, ignoredAsk, pendingAsk)),
                ToolExecutionResultMessage.from(weatherRequest, "sunny")));
    }

    /** 快照：需审批 MCP 工具的挂起请求（无结果消息，占位由恢复轮注入）/ Snapshot: the approval-required MCP tool's pending request (no result message; the placeholder is injected at resume) */
    private static String mcpApprovalSnapshot() {
        ToolExecutionRequest mcpRequest = ToolExecutionRequest.builder()
                .id("id-mcp-1").name("submit_expense_report")
                .arguments("{\"title\":\"部门聚餐报销\",\"amount\":3200}").build();
        return ChatMessageSnapshotCodec.encode(List.of(
                UserMessage.from("帮我提交报销"),
                AiMessage.aiMessage(List.of(mcpRequest))));
    }

    /**
     * MCP_APPROVAL 检查点 fixture：在 ASK_USER 基础上换 kind/pendingToolName 并按需携带
     * approval_grant JSON
     * <p>
     * MCP_APPROVAL checkpoint fixture: the ASK_USER base with a different
     * kind/pendingToolName and an optional approval_grant JSON
     */
    private static AgentPendingCheckpoint mcpApprovalCheckpoint(Long id, String approvalGrantJson) {
        AgentPendingCheckpoint checkpoint = activeCheckpoint(id, 55L, "id-mcp-1", mcpApprovalSnapshot());
        checkpoint.setKind(PendingCheckpointKind.MCP_APPROVAL.getCode());
        checkpoint.setPendingToolName("submit_expense_report");
        checkpoint.setApprovalGrant(approvalGrantJson);
        return checkpoint;
    }

    /** 挂起回调持久化桩：create 返回带 uuid 的检查点行 / Suspension-sink persistence stub: create returns a checkpoint row with a uuid */
    private static final class AgentPersistCheckpointStub {
        final AgentPendingCheckpoint checkpoint = activeCheckpoint(9999L, 55L, "id-stub",
                suspensionSnapshot("id-w", "id-ask-1"));
    }

    private static AgentPendingCheckpoint activeCheckpoint(Long id, Long characterId, String pendingRequestId,
                                                            String snapshotJson) {
        AgentPendingCheckpoint checkpoint = new AgentPendingCheckpoint();
        checkpoint.setId(id);
        checkpoint.setUuid("ckpt-uuid-" + id);
        checkpoint.setConversationId(CONVERSATION_ID);
        checkpoint.setCharacterId(characterId);
        checkpoint.setUserId(7L);
        checkpoint.setKind(PendingCheckpointKind.ASK_USER.getCode());
        checkpoint.setPendingToolName("ask_user");
        checkpoint.setPendingRequestId(pendingRequestId);
        checkpoint.setMessagesSnapshot(snapshotJson);
        checkpoint.setToolCallDepth(3);
        checkpoint.setSuspensionCount(1);
        checkpoint.setStatus(PendingCheckpointStatus.ACTIVE.getCode());
        checkpoint.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return checkpoint;
    }

    /** 恒定小值的 token 估算器（窗口足够容纳测试消息）/ Constantly small token estimator (window fits the test messages) */
    private static TokenCountEstimator trivialEstimator() {
        return new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                return null == text ? 0 : 1;
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return 1;
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                return 2;
            }
        };
    }

    /** call() 捕获结果：请求参数 + 完成回调 / Captured call(): request params + completion callback */
    private record SseAskParamCapture(
            SseAskParam param,
            TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> callback) {
    }
}
