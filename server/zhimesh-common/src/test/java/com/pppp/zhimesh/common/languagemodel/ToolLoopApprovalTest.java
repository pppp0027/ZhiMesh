package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalGrant;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalRequiredDecorator;
import com.pppp.zhimesh.common.languagemodel.tool.RequestHumanApprovalTool;
import com.pppp.zhimesh.common.languagemodel.tool.SuspensionCheckpointSink;
import com.pppp.zhimesh.common.languagemodel.tool.SuspensionSignal;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.AnswerMeta;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.PromptMeta;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5 审批挂起的工具循环级验证（复用 T4 挂起内核）：需审批 MCP 装饰器未持凭证挂起为
 * MCP_APPROVAL（凭证随信号落检查点、SSE 事件名为 approval_request）；恢复链持匹配
 * 凭证放行真实调用（内层恰好一次、循环续跑到最终回答、无挂起）；恢复链参数被改即
 * 再次挂起；挂起次数达上限时需审批工具绝不执行（fail-closed，回退引导文本）；
 * request_human_approval 显式审批同走 approval_request 事件；事件名按 kind 两态分发
 * （approval_request vs agent_question）与审批载荷契约；以及装配期 wrapApprovalRequiredTools
 * 的包装矩阵。
 * <p>
 * Tool-loop-level verification of the T5 approval suspension (reusing the T4
 * suspension kernel): the approval-required MCP decorator without a grant
 * suspends as MCP_APPROVAL (the grant persists with the signal's checkpoint,
 * the SSE event name is approval_request); a resume chain holding a matching
 * grant lets the real call through (the inner executor runs exactly once, the
 * loop continues to the final answer, no suspension); mutated arguments on
 * the resume chain re-suspend; at the suspension cap the approval-required
 * tool never executes (fail-closed, guidance-text fallback);
 * request_human_approval's explicit approval rides the same approval_request
 * event; the event name dispatches two-state by kind (approval_request vs
 * agent_question) with the approval payload contract; plus the assembly-time
 * wrapApprovalRequiredTools wrapping matrix.
 */
class ToolLoopApprovalTest {

    private static final String SSE_UUID = "sse-approval-test";
    private static final String TOOL_NAME = "submit_expense_report";
    private static final String ARGS = "{\"title\":\"部门聚餐报销\",\"amount\":3200}";

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private SseManager sseManager;
    private SseEmitter emitter;

    private final TestLLMService llmService = new TestLLMService();

    @BeforeAll
    static void seedTtsConfig() {
        LocalCache.CONFIGS.put(ZhiMeshConstant.SysConfigKey.TTS_SETTING, "{}");
    }

    @AfterAll
    static void clearTtsConfig() {
        LocalCache.CONFIGS.remove(ZhiMeshConstant.SysConfigKey.TTS_SETTING);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        context = mock(ApplicationContext.class);
        // 真实 SseManager + 注册 mock emitter：sendApprovalRequest/sendAgentQuestion 静态
        // 路径经 SpringUtil.getBean(SseManager.class) 命中 entries，事件可被捕获
        // A real SseManager with a registered mock emitter: the static
        // sendApprovalRequest/sendAgentQuestion paths resolve through
        // SpringUtil.getBean(SseManager.class) into entries, so events are capturable
        sseManager = new SseManager();
        emitter = mock(SseEmitter.class);
        sseManager.register(SSE_UUID, emitter);
        when(context.getBean(SseManager.class)).thenReturn(sseManager);
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(context.getBean(StringRedisTemplate.class)).thenReturn(redisTemplate);
        llmService.useRedisTemplate(redisTemplate);

        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    // ==================== MCP 审批挂起与放行 / MCP approval suspension and pass-through ====================

    @Test
    void mcpToolWithoutGrantSuspendsAsMcpApprovalAndEmitsApprovalRequestEvent() throws Exception {
        // 未持凭证的需审批 MCP 调用：真实 MCP 不执行，挂起为 MCP_APPROVAL 且凭证随信号
        // 交给检查点回调；SSE 事件名为 approval_request（而非 agent_question），模型只被
        // 调一次，consumer 收到审批问句，快照无挂起请求的结果消息
        // An approval-required MCP call without a grant: the real MCP call does
        // not run; the loop suspends as MCP_APPROVAL with the grant handed to
        // the checkpoint callback via the signal; the SSE event name is
        // approval_request (not agent_question); the model is called exactly
        // once, the consumer gets the approval question, and the snapshot
        // carries no result message for the suspended request
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(toolRequest("id-mcp-1", TOOL_NAME, ARGS))),
                    new TokenUsage(100, 20)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        AtomicReference<SuspensionSignal> capturedSignal = new AtomicReference<>();
        AtomicReference<List<ChatMessage>> capturedSnapshot = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    capturedSignal.set(signal);
                    capturedSnapshot.set(snapshot);
                    return "ckpt-ap-1";
                })
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of(TOOL_NAME, new ApprovalRequiredDecorator(inner)),
                        toolContext, answer));

        // 内层真实 MCP 零调用 + 模型恰一次 + consumer 收审批问句
        // Zero inner MCP calls, exactly one model call, the consumer gets the approval question
        assertThat(inner.invocations.get()).isZero();
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(answer.get()).contains(TOOL_NAME).contains("审批");

        // 检查点信号：kind=MCP_APPROVAL、凭证覆盖本次「工具+参数」、uuid 已回填
        // The checkpoint signal: kind=MCP_APPROVAL, the grant covers this very
        // "tool + arguments", the uuid backfilled
        SuspensionSignal signal = capturedSignal.get();
        assertThat(signal.getKind()).isEqualTo(PendingCheckpointKind.MCP_APPROVAL);
        assertThat(signal.getApprovalGrant()).isNotNull();
        assertThat(signal.getApprovalGrant().matches(TOOL_NAME, ARGS)).isTrue();
        assertThat(signal.getCheckpointUuid()).isEqualTo("ckpt-ap-1");
        assertThat(signal.getQuestion()).contains(TOOL_NAME);

        // 快照无挂起请求的结果消息（占位由恢复轮注入）
        // The snapshot carries no result message for the suspended request
        // (placeholders are injected at resume time)
        assertThat(capturedSnapshot.get().stream()
                .filter(message -> message instanceof ToolExecutionResultMessage)).isEmpty();

        // 审批轨迹（口径同 ask_user：success=true、摘要=问句）
        // The approval trace (same convention as ask_user: success=true, summary=question)
        assertThat(toolContext.getToolTraces()).hasSize(1);
        ToolCallTrace trace = toolContext.getToolTraces().get(0);
        assertThat(trace.getToolName()).isEqualTo(TOOL_NAME);
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getResultSummary()).isEqualTo(signal.getQuestion());

        // 事件名按 kind 分发：MCP_APPROVAL → approval_request；绝不发 agent_question
        // The event name dispatches by kind: MCP_APPROVAL → approval_request,
        // never agent_question
        List<String> rawEvents = sentRawEvents();
        assertThat(rawEvents).anySatisfy(raw ->
                assertThat(raw).startsWith("event:" + ZhiMeshConstant.SSEEventName.APPROVAL_REQUEST + "\n"));
        assertThat(rawEvents).noneSatisfy(raw ->
                assertThat(raw).startsWith("event:" + ZhiMeshConstant.SSEEventName.AGENT_QUESTION + "\n"));
    }

    @Test
    void resumedChainWithMatchingGrantRunsRealCallOnceAndCompletes() {
        // 恢复链持匹配凭证（同工具同参数）：装饰器放行真实 MCP 调用（内层恰好一次），
        // 循环照常续跑到最终回答，不再挂起、检查点不再新增
        // The resume chain holds a matching grant (same tool, same arguments):
        // the decorator lets the real MCP call through (the inner executor runs
        // exactly once), the loop continues to the final answer, and neither a
        // new suspension nor a new checkpoint happens
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("id-mcp-2", TOOL_NAME, ARGS))),
                        new TokenUsage(100, 20)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("报销单已提交，受理号 BX-2026-0923"),
                        new TokenUsage(120, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, ARGS))
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    throw new AssertionError("a matched grant must not suspend again");
                })
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of(TOOL_NAME, new ApprovalRequiredDecorator(inner)),
                        toolContext, answer));

        assertThat(inner.invocations.get()).isEqualTo(1);
        verify(streamingModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(answer.get()).isEqualTo("报销单已提交，受理号 BX-2026-0923");
        assertThat(toolContext.getSuspensionSignal()).isNull();
        // 轨迹记录真实调用的结果（放行后的正常执行口径）
        // The trace records the real call's result (the normal-execution
        // convention after pass-through)
        assertThat(toolContext.getToolTraces()).hasSize(1);
        assertThat(toolContext.getToolTraces().get(0).getResultSummary()).isEqualTo(CountingMcpExecutor.RESULT);
    }

    @Test
    void mutatedArgumentsOnResumedChainReSuspendsForNewApproval() {
        // 凭证覆盖 3200 元的参数，模型重调改成 32000 元（提示注入改参）：哈希不匹配 →
        // 再次挂起、真实调用不执行；新信号的凭证覆盖新参数（新参数是新一次审批）
        // The grant covers the 3200-yuan arguments but the model re-invokes
        // with 32000 (the injection args mutation): the hash mismatches →
        // re-suspension, the real call never runs; the new signal's grant
        // covers the new arguments (a fresh approval for fresh arguments)
        String mutatedArgs = "{\"title\":\"部门聚餐报销\",\"amount\":32000}";
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(toolRequest("id-mcp-3", TOOL_NAME, mutatedArgs))),
                    new TokenUsage(100, 20)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        AtomicReference<SuspensionSignal> capturedSignal = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, ARGS))
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    capturedSignal.set(signal);
                    return "ckpt-ap-2";
                })
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of(TOOL_NAME, new ApprovalRequiredDecorator(inner)),
                        toolContext, answer));

        assertThat(inner.invocations.get()).isZero();
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        SuspensionSignal signal = capturedSignal.get();
        assertThat(signal).isNotNull();
        assertThat(signal.getKind()).isEqualTo(PendingCheckpointKind.MCP_APPROVAL);
        assertThat(signal.getApprovalGrant().matches(TOOL_NAME, mutatedArgs)).isTrue();
        assertThat(signal.getApprovalGrant().matches(TOOL_NAME, ARGS)).isFalse();
        assertThat(answer.get()).contains(mutatedArgs);
    }

    @Test
    void suspensionCapKeepsRealMcpCallGatedBehindGuidanceText() {
        // 挂起次数达上限（默认 3）：挂起内核在执行前就回退为引导文本——需审批 MCP 工具
        // 依然绝不执行（fail-closed），模型据引导文本照常收尾
        // At the suspension cap (default 3): the kernel falls back to the
        // guidance text before executing — the approval-required MCP tool still
        // never runs (fail-closed), and the model wraps up normally on the guidance
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("id-mcp-4", TOOL_NAME, ARGS))),
                        new TokenUsage(80, 10)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("无法完成该操作"), new TokenUsage(120, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        AtomicReference<String> answer = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionCount(3)
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    throw new AssertionError("sink must not be called at the suspension cap");
                })
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of(TOOL_NAME, new ApprovalRequiredDecorator(inner)),
                        toolContext, answer));

        assertThat(inner.invocations.get()).isZero();
        verify(streamingModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(answer.get()).isEqualTo("无法完成该操作");
        // 第二轮请求挂着引导文本结果而非真实 MCP 结果
        // The second round carries the guidance text, not a real MCP result
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel, times(2)).chat(requestCaptor.capture(), any(StreamingChatResponseHandler.class));
        assertThat(requestCaptor.getAllValues().get(1).messages().stream()
                .filter(message -> message instanceof ToolExecutionResultMessage)
                .map(message -> ((ToolExecutionResultMessage) message).text()))
                .containsExactly(AbstractLLMService.SUSPENSION_LIMIT_REACHED_TEXT);
        assertThat(toolContext.getSuspensionSignal()).isNull();
    }

    // ==================== 显式审批工具与事件分发 / Explicit approval tool and event dispatch ====================

    @Test
    void explicitApprovalToolSuspendsAsApprovalKindWithSameEventName() throws Exception {
        // request_human_approval 显式审批：kind=APPROVAL 与 MCP_APPROVAL 共用
        // approval_request 事件与审批卡；凭证为空（无后续真实调用的放行语义）
        // The request_human_approval explicit approval: kind=APPROVAL shares
        // the approval_request event and card with MCP_APPROVAL; the grant is
        // null (nothing real follows to let through)
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(toolRequest("id-ap-1", RequestHumanApprovalTool.NAME,
                            "{\"action\":\"提交报销单\",\"summary\":\"金额 3200 元，事由部门聚餐\",\"risk_level\":\"MEDIUM\"}"))),
                    new TokenUsage(90, 15)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<SuspensionSignal> capturedSignal = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    capturedSignal.set(signal);
                    return "ckpt-ap-3";
                })
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of(RequestHumanApprovalTool.NAME, new RequestHumanApprovalTool()),
                        toolContext, answer));

        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        SuspensionSignal signal = capturedSignal.get();
        assertThat(signal.getKind()).isEqualTo(PendingCheckpointKind.APPROVAL);
        assertThat(signal.getApprovalGrant()).isNull();
        assertThat(signal.getAction()).isEqualTo("提交报销单");
        assertThat(answer.get()).contains("提交报销单");

        // 显式审批同样走 approval_request 事件（两形态一张卡，kind 供前端区分）
        // The explicit approval rides the same approval_request event (one card
        // for both shapes; kind tells the frontend which is which)
        List<String> rawEvents = sentRawEvents();
        assertThat(rawEvents).anySatisfy(raw ->
                assertThat(raw).startsWith("event:" + ZhiMeshConstant.SSEEventName.APPROVAL_REQUEST + "\n"));
        assertThat(rawEvents).noneSatisfy(raw ->
                assertThat(raw).startsWith("event:" + ZhiMeshConstant.SSEEventName.AGENT_QUESTION + "\n"));
    }

    @Test
    void eventNameDispatchesTwoStateBySuspensionKind() {
        // 事件名分发判定：ASK_USER → agent_question（问题卡）；APPROVAL / MCP_APPROVAL →
        // approval_request（审批卡）；null 防御归提问态
        // The event-name dispatch basis: ASK_USER → agent_question (question
        // card); APPROVAL / MCP_APPROVAL → approval_request (approval card);
        // a defensive null falls to the question side
        assertThat(AbstractLLMService.isApprovalKind(PendingCheckpointKind.ASK_USER)).isFalse();
        assertThat(AbstractLLMService.isApprovalKind(PendingCheckpointKind.APPROVAL)).isTrue();
        assertThat(AbstractLLMService.isApprovalKind(PendingCheckpointKind.MCP_APPROVAL)).isTrue();
        assertThat(AbstractLLMService.isApprovalKind(null)).isFalse();
    }

    @Test
    void approvalSuspensionPayloadContractCoversBothApprovalKinds() {
        // 审批载荷契约（T6 前端按此渲染审批卡）：公共键 kind/toolName/question/
        // checkpointUuid；审批类追加 action/summary/riskLevel（可空省略键）；
        // ASK_USER 形态不受影响（不追加审批键）
        // The approval payload contract (T6's frontend renders the approval
        // card from it): common keys kind/toolName/question/checkpointUuid; the
        // approval kinds add action/summary/riskLevel (keys omitted when null);
        // the ASK_USER shape is unaffected (no approval keys appended)
        SuspensionSignal mcpApproval = SuspensionSignal.builder()
                .kind(PendingCheckpointKind.MCP_APPROVAL)
                .toolName(TOOL_NAME)
                .requestId("id-1")
                .question("该操作需要人工审批")
                .action("调用工具 " + TOOL_NAME)
                .summary(ARGS)
                .checkpointUuid("ckpt-1")
                .build();
        Map<String, Object> mcpPayload = ReflectionTestUtils.invokeMethod(
                llmService, "buildSuspensionEventPayload", mcpApproval);
        assertThat(mcpPayload).containsOnlyKeys("kind", "toolName", "question", "action", "summary", "checkpointUuid");
        assertThat(mcpPayload.get("kind")).isEqualTo("MCP_APPROVAL");
        assertThat(mcpPayload.get("action")).isEqualTo("调用工具 " + TOOL_NAME);
        assertThat(mcpPayload.get("summary")).isEqualTo(ARGS);
        assertThat(mcpPayload.get("checkpointUuid")).isEqualTo("ckpt-1");

        // riskLevel 缺省时省略该键（不是 null 占位）
        // A missing riskLevel omits the key (no null placeholder)
        SuspensionSignal explicitApproval = SuspensionSignal.builder()
                .kind(PendingCheckpointKind.APPROVAL)
                .toolName(RequestHumanApprovalTool.NAME)
                .question("该操作需要人工审批")
                .action("删除知识库")
                .summary("删除 KB-A 及其全部片段")
                .riskLevel("HIGH")
                .checkpointUuid("ckpt-2")
                .build();
        Map<String, Object> explicitPayload = ReflectionTestUtils.invokeMethod(
                llmService, "buildSuspensionEventPayload", explicitApproval);
        assertThat(explicitPayload).containsOnlyKeys("kind", "toolName", "question", "action", "summary",
                "riskLevel", "checkpointUuid");
        assertThat(explicitPayload.get("riskLevel")).isEqualTo("HIGH");

        // ASK_USER 形态不含审批键（问题卡契约不变）
        // The ASK_USER shape carries no approval keys (question-card contract unchanged)
        SuspensionSignal askUser = SuspensionSignal.builder()
                .kind(PendingCheckpointKind.ASK_USER)
                .toolName("ask_user")
                .question("你负责哪个部门？")
                .checkpointUuid("ckpt-3")
                .build();
        Map<String, Object> askPayload = ReflectionTestUtils.invokeMethod(
                llmService, "buildSuspensionEventPayload", askUser);
        assertThat(askPayload).containsOnlyKeys("kind", "toolName", "question", "checkpointUuid");
    }

    // ==================== 装配期包装矩阵 / Assembly-time wrapping matrix ====================

    @Test
    void wrapApprovalRequiredToolsWrapsOnlyHitNonCollaborativeMcpExecutors() {
        // 命中集合的 MCP 执行器被包审批装饰器；未命中的原样；已是协作类的防重复包装；
        // 集合里的未知名（MCP 未绑定）静默跳过
        // MCP executors hit by the set get the approval decorator; misses stay
        // as-is; already-collaborative entries avoid double wrapping; unknown
        // names in the set (no MCP bound) are skipped silently
        ToolExecutor submit = new CountingMcpExecutor(TOOL_NAME);
        ToolExecutor weather = new CountingMcpExecutor("weather");
        ToolExecutor askUser = new RequestHumanApprovalTool();
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        executors.put(TOOL_NAME, submit);
        executors.put("weather", weather);
        executors.put(RequestHumanApprovalTool.NAME, askUser);

        AbstractLLMService.wrapApprovalRequiredTools(executors,
                Set.of(TOOL_NAME, RequestHumanApprovalTool.NAME, "not_bound_tool"));

        assertThat(executors.get(TOOL_NAME)).isInstanceOf(ApprovalRequiredDecorator.class);
        assertThat(((ApprovalRequiredDecorator) executors.get(TOOL_NAME)).spec().name()).isEqualTo(TOOL_NAME);
        assertThat(executors.get("weather")).isSameAs(weather);
        assertThat(executors.get(RequestHumanApprovalTool.NAME)).isSameAs(askUser);
        assertThat(executors).hasSize(3);
    }

    @Test
    void wrapApprovalRequiredToolsLeavesEverythingUntouchedOnEmptyOrNullSet() {
        // 空集 / null：装配无审批门，执行器映射原样（行为不变的默认态）
        // An empty or null set: no gate is assembled and the executor map stays
        // untouched (the unchanged default state)
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        ToolExecutor submit = new CountingMcpExecutor(TOOL_NAME);
        executors.put(TOOL_NAME, submit);

        AbstractLLMService.wrapApprovalRequiredTools(executors, null);
        assertThat(executors.get(TOOL_NAME)).isSameAs(submit);

        AbstractLLMService.wrapApprovalRequiredTools(executors, Set.of());
        assertThat(executors.get(TOOL_NAME)).isSameAs(submit);
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private InnerStreamChatParam baseParams(StreamingChatModel streamingModel,
                                            Map<String, ToolExecutor> executors,
                                            ToolContext toolContext, AtomicReference<String> answer) {
        return InnerStreamChatParam.builder()
                .uuid("approval-uuid")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("帮我提交报销"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid(SSE_UUID)
                .toolExecutorMap(executors)
                .toolContext(toolContext)
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();
    }

    /**
     * 捕获 emitter 收到的全部事件名文本（event:name\n）：Spring 6.1 的 SseEventBuilder
     * 实现在每次 data() 前把已累积文本（含事件名）刷进私有 dataToSend 集合，反射读回其
     * String 元素——事件名两态断言的直接依据
     * <p>
     * Capture the event-name text (event:name\n) of every event the emitter
     * received: Spring 6.1's SseEventBuilder implementation flushes the
     * accumulated text (event name included) into the private dataToSend set
     * before every data() call; read its String elements back via reflection —
     * the direct basis for the two-state event-name assertions
     */
    private List<String> sentRawEvents() throws java.io.IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(captor.capture());
        List<String> rawEvents = new ArrayList<>();
        for (SseEmitter.SseEventBuilder builder : captor.getAllValues()) {
            Set<?> dataToSend = (Set<?>) ReflectionTestUtils.getField(builder, "dataToSend");
            if (null == dataToSend) {
                continue;
            }
            for (Object element : dataToSend) {
                Object data = ReflectionTestUtils.getField(element, "data");
                if (data instanceof String text) {
                    rawEvents.add(text);
                }
            }
        }
        return rawEvents;
    }

    private static ChatResponse chatResponse(AiMessage aiMessage, TokenUsage tokenUsage) {
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(ChatResponseMetadata.builder().tokenUsage(tokenUsage).build())
                .build();
    }

    private static ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    /**
     * 计数型假 MCP 执行器（isMcpTool=true）：放行断言的「内层恰好被调几次」依据
     * <p>
     * Counting fake MCP executor (isMcpTool=true): the "exactly how many times
     * the inner ran" basis for the pass-through assertions
     */
    private static final class CountingMcpExecutor implements ToolExecutor {
        static final String RESULT = "mcp-ok";

        final AtomicInteger invocations = new AtomicInteger();
        private final ToolSpecification spec;

        private CountingMcpExecutor(String toolName) {
            this.spec = ToolSpecification.builder()
                    .name(toolName)
                    .description("test mcp tool " + toolName)
                    .build();
        }

        @Override
        public ToolSpecification spec() {
            return spec;
        }

        @Override
        public boolean isMcpTool() {
            return true;
        }

        @Override
        public String execute(ToolExecutionRequest request, ToolContext context) {
            invocations.incrementAndGet();
            return RESULT;
        }
    }

    /**
     * 最小可实例化的 AbstractLLMService 测试子类
     * Minimal instantiable AbstractLLMService test subclass
     */
    private static final class TestLLMService extends AbstractLLMService {
        private TestLLMService() {
            super(testAiModel(), new ModelPlatform());
        }

        private static AiModel testAiModel() {
            AiModel aiModel = new AiModel();
            aiModel.setMaxInputTokens(8192);
            return aiModel;
        }

        void useRedisTemplate(StringRedisTemplate template) {
            this.stringRedisTemplate = template;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        protected ChatModel doBuildChatModel(ChatModelBuilderProperties properties) {
            return null;
        }

        @Override
        public StreamingChatModel buildStreamingChatModel(ChatModelBuilderProperties properties) {
            return null;
        }

        @Override
        protected com.pppp.zhimesh.common.languagemodel.data.LLMException parseError(Object error) {
            return null;
        }

        @Override
        public TokenCountEstimator getTokenEstimator() {
            return null;
        }
    }
}
