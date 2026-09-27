package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.AskUserTool;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂起-恢复内核（AbstractLLMService 挂起分支）的行为验证：协作类工具挂起后不再递归
 * 调用模型、以「问题文本 + 本轮真实 tokenUsage」合成收尾复用正常完成路径（token 恰好
 * 入账一次）、同轮同伴工具先执行且只有第一个协作请求挂起（其余连结果消息都不产生）、
 * 挂起次数达上限/未接线回调时回退为引导文本结果并照常续跑。
 * <p>
 * Behavioral verification of the suspend/resume kernel (AbstractLLMService's
 * suspension branch): after a collaborative tool suspends, no further model
 * call happens; the synthesized wrap-up (question text + the round's real
 * tokenUsage) reuses the ordinary completion path (tokens billed exactly
 * once); same-round peers execute first and only the first collaborative
 * request suspends (the others yield no result message at all); when the
 * suspension cap is hit or no sink is wired, the branch falls back to a
 * guidance-text tool result and the loop continues normally.
 */
class ToolLoopSuspensionTest {

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;

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
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        // calculateToken 经 SpringUtil 取 StringRedisTemplate：挂起轮 token 入账断言依赖它
        // calculateToken resolves StringRedisTemplate via SpringUtil: the
        // billed-exactly-once assertion depends on it
        when(context.getBean(StringRedisTemplate.class)).thenReturn(redisTemplate);
        llmService.useRedisTemplate(redisTemplate);

        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void collaborativeSuspensionStopsModelCallsAndSynthesizesQuestionWrapUp() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(toolRequest("id-ask-1", "ask_user",
                            "{\"question\":\"你负责哪个部门？\"}"))),
                    new TokenUsage(100, 20)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<SuspensionSignal> capturedSignal = new AtomicReference<>();
        AtomicReference<List<ChatMessage>> capturedSnapshot = new AtomicReference<>();
        AtomicInteger capturedDepth = new AtomicInteger(-1);
        AtomicInteger capturedSuspensionCount = new AtomicInteger(-1);
        SuspensionCheckpointSink sink = (signal, snapshot, depth, suspensionCount) -> {
            capturedSignal.set(signal);
            capturedSnapshot.set(snapshot);
            capturedDepth.set(depth);
            capturedSuspensionCount.set(suspensionCount);
            return "ckpt-uuid-1";
        };
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionSink(sink)
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", baseParams(streamingModel,
                Map.of("ask_user", new AskUserTool()), toolContext, answer));

        // 挂起后绝不递归调用模型：模型恰好被调 1 次
        // No recursive model call after suspension: exactly one model call
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        // 合成收尾：consumer 收到的内容就是问题文本（复用正常完成路径）
        // Synthesized wrap-up: the consumer's content is the question text
        // (reusing the ordinary completion path)
        assertThat(answer.get()).isEqualTo("你负责哪个部门？");

        // 检查点落库参数：预算继承（depth+1 / suspensionCount=已耗+1）与载荷截断后的信号
        // Checkpoint-persist parameters: inherited budgets (depth+1 /
        // suspensionCount = consumed+1) and the post-truncation signal
        assertThat(capturedDepth.get()).isEqualTo(1);
        assertThat(capturedSuspensionCount.get()).isEqualTo(1);
        assertThat(capturedSignal.get().getQuestion()).isEqualTo("你负责哪个部门？");
        assertThat(capturedSignal.get().getKind()).isEqualTo(com.pppp.zhimesh.common.enums.PendingCheckpointKind.ASK_USER);
        // persist 返回的 uuid 已回填进同一信号对象（完成回调据此装饰 meta 载荷）
        // The uuid returned by persist is backfilled into the same signal
        // object (the completion callback decorates the meta payload from it)
        assertThat(capturedSignal.get().getCheckpointUuid()).isEqualTo("ckpt-uuid-1");

        // 快照 = 请求消息链 + 本轮 AiMessage（解析后的同一批请求对象），挂起请求无结果消息
        // Snapshot = request message chain + this round's AiMessage (the same
        // parsed request objects); the suspended request yields no result message
        List<ChatMessage> snapshot = capturedSnapshot.get();
        assertThat(snapshot).hasSize(2);
        AiMessage snapshotAi = (AiMessage) snapshot.get(1);
        assertThat(snapshotAi.toolExecutionRequests()).hasSize(1);
        assertThat(snapshotAi.toolExecutionRequests().get(0).id()).isEqualTo("id-ask-1");
        assertThat(snapshot.stream().filter(message -> message instanceof ToolExecutionResultMessage)).isEmpty();

        // 挂起轨迹：toolName=ask_user / success=true / resultSummary=问题文本
        // Suspension trace: toolName=ask_user / success=true / resultSummary=question
        assertThat(toolContext.getToolTraces()).hasSize(1);
        ToolCallTrace trace = toolContext.getToolTraces().get(0);
        assertThat(trace.getToolName()).isEqualTo("ask_user");
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getResultSummary()).isEqualTo("你负责哪个部门？");

        // 挂起轮 token 恰好入账一次：合成收尾带本轮真实 usage（100/20），中间轮未预入账
        // The suspension round's tokens are billed exactly once: the synthetic
        // wrap-up carries the round's real usage (100/20) and the intermediate
        // cache never pre-recorded it
        verify(listOperations, times(1)).rightPushAll(anyString(), eq("100"), eq("20"));
    }

    @Test
    void sameRoundPeersExecuteFirstAndOnlyFirstCollaborativeRequestSuspends() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(
                            toolRequest("id-w", "weather", "{\"city\":\"广州\"}"),
                            toolRequest("id-a1", "ask_user", "{\"question\":\"第一个问题？\"}"),
                            toolRequest("id-a2", "ask_user", "{\"question\":\"第二个问题？\"}"))),
                    new TokenUsage(90, 15)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<List<ChatMessage>> capturedSnapshot = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    capturedSnapshot.set(snapshot);
                    return "ckpt-uuid-2";
                })
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        executors.put("weather", fixedTool("weather", "sunny"));
        executors.put("ask_user", new AskUserTool());
        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, executors, toolContext, answer));

        // 模型仍只被调 1 次；consumer 收到第一个协作请求的问题
        // Still exactly one model call; the consumer gets the first
        // collaborative request's question
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(answer.get()).isEqualTo("第一个问题？");

        // 同轮非协作同伴先执行并产生结果；两个协作请求都没有结果消息（占位由恢复轮注入）
        // The non-collaborative peer executed first with a result; neither
        // collaborative request has a result message (placeholders are injected
        // at resume time)
        List<ChatMessage> snapshot = capturedSnapshot.get();
        List<ToolExecutionResultMessage> results = snapshot.stream()
                .filter(message -> message instanceof ToolExecutionResultMessage)
                .map(message -> (ToolExecutionResultMessage) message)
                .toList();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("id-w");
        assertThat(results.get(0).text()).isEqualTo("sunny");
        AiMessage snapshotAi = (AiMessage) snapshot.get(snapshot.size() - 2);
        assertThat(snapshotAi.toolExecutionRequests())
                .extracting(ToolExecutionRequest::id)
                .containsExactly("id-w", "id-a1", "id-a2");
    }

    @Test
    void suspensionLimitReachedReturnsGuidanceTextResultAndLoopContinues() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("id-ask-1", "ask_user",
                                "{\"question\":\"还要问？\"}"))),
                        new TokenUsage(80, 10)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("基于已有信息的直接回答"), new TokenUsage(120, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // 已耗挂起次数=上限（默认 3）：本协作请求不得再挂起
        // Consumed suspensions already at the cap (default 3): this
        // collaborative request must not suspend again
        AtomicReference<String> answer = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionCount(3)
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    throw new AssertionError("sink must not be called at the suspension cap");
                })
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of("ask_user", new AskUserTool()), toolContext, answer));

        // 循环照常续跑：两轮模型调用，最终回答正常送达
        // The loop continues normally: two model calls, the final answer arrives
        verify(streamingModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(answer.get()).isEqualTo("基于已有信息的直接回答");

        // 第二轮请求里挂着引导文本结果；轨迹 success=true、摘要=引导文本
        // The second round's request carries the guidance-text result; the trace
        // records success=true with the guidance as its summary
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel, times(2)).chat(requestCaptor.capture(), any(StreamingChatResponseHandler.class));
        ChatRequest secondRound = requestCaptor.getAllValues().get(1);
        assertThat(secondRound.messages().stream()
                .filter(message -> message instanceof ToolExecutionResultMessage)
                .map(message -> ((ToolExecutionResultMessage) message).text()))
                .containsExactly(AbstractLLMService.SUSPENSION_LIMIT_REACHED_TEXT);
        assertThat(toolContext.getToolTraces()).hasSize(1);
        assertThat(toolContext.getToolTraces().get(0).getToolName()).isEqualTo("ask_user");
        assertThat(toolContext.getToolTraces().get(0).isSuccess()).isTrue();
        assertThat(toolContext.getToolTraces().get(0).getResultSummary())
                .isEqualTo(AbstractLLMService.SUSPENSION_LIMIT_REACHED_TEXT);
        // 信号从未置位
        // The signal was never raised
        assertThat(toolContext.getSuspensionSignal()).isNull();
    }

    @Test
    void collaborativeToolWithoutSinkAnswersInlineUnsupported() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("id-ask-1", "ask_user",
                                "{\"question\":\"Q?\"}"))),
                        new TokenUsage(80, 10)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("inline answer"), new TokenUsage(120, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // 无 suspensionSink（阻塞路径误注册协作工具的形态）：走「不支持挂起」内联回答
        // No suspensionSink (the shape of a collaborative tool mistakenly
        // registered on the blocking path): the inline "unsupported" answer
        AtomicReference<String> answer = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of("ask_user", new AskUserTool()), toolContext, answer));

        verify(streamingModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        assertThat(answer.get()).isEqualTo("inline answer");
        assertThat(toolContext.getToolTraces()).hasSize(1);
        assertThat(toolContext.getToolTraces().get(0).getResultSummary())
                .isEqualTo(AbstractLLMService.SUSPENSION_UNSUPPORTED_TEXT);
        assertThat(toolContext.getSuspensionSignal()).isNull();
    }

    @Test
    void blankIdToolRequestsKeepRequestResultPairingAcrossSuspensionSnapshot() {
        // 空白 id 的提供商形态：解析补全 id 后，快照 AiMessage 与执行用的是同一批请求对象
        // （挂起请求 id 与检查点 pendingRequestId 可配对）
        // Blank-id provider shape: after parsing completes the ids, the snapshot
        // AiMessage reuses the very request objects used for execution (the
        // suspended request's id pairs with the checkpoint's pendingRequestId)
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(
                            ToolExecutionRequest.builder().id("").name("ask_user")
                                    .arguments("{\"question\":\"配对问题？\"}").build())),
                    new TokenUsage(50, 8)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<List<ChatMessage>> capturedSnapshot = new AtomicReference<>();
        AtomicReference<SuspensionSignal> capturedSignal = new AtomicReference<>();
        ToolContext toolContext = ToolContext.builder()
                .toolTraces(new ArrayList<>())
                .suspensionSink((signal, snapshot, depth, suspensionCount) -> {
                    capturedSnapshot.set(snapshot);
                    capturedSignal.set(signal);
                    return "ckpt-uuid-3";
                })
                .build();
        AtomicReference<String> answer = new AtomicReference<>();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat",
                baseParams(streamingModel, Map.of("ask_user", new AskUserTool()), toolContext, answer));

        assertThat(answer.get()).isEqualTo("配对问题？");
        // 解析补全后的 id 非空，且信号里的 requestId 与快照 AiMessage 的请求 id 一致
        // The parsed id is non-blank and the signal's requestId matches the
        // snapshot AiMessage's request id
        AiMessage snapshotAi = (AiMessage) capturedSnapshot.get().get(1);
        assertThat(snapshotAi.toolExecutionRequests().get(0).id()).isNotBlank();
        assertThat(capturedSignal.get().getRequestId())
                .isEqualTo(snapshotAi.toolExecutionRequests().get(0).id());
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    void agentQuestionPayloadCarriesContractShapeWithCheckpointUuid() {
        // SSE 载荷契约（T6 前端按此渲染问题卡片）：kind/toolName/question/checkpointUuid，
        // options 为空时省略该键；带 options 时原样下发。事件在检查点持久化成功后才发，
        // 故 checkpointUuid 恒在（与 AnswerMeta.suspension 同源）
        // The SSE payload contract (T6's frontend renders the question card
        // from it): kind/toolName/question/checkpointUuid, the options key
        // omitted when empty; carried verbatim when present. The event fires
        // only after the checkpoint is durably persisted, so checkpointUuid is
        // always set (same source as AnswerMeta.suspension)
        SuspensionSignal withoutOptions = SuspensionSignal.builder()
                .kind(com.pppp.zhimesh.common.enums.PendingCheckpointKind.ASK_USER)
                .toolName("ask_user")
                .requestId("id-ask-1")
                .question("你负责哪个部门？")
                .checkpointUuid("ckpt-uuid-1")
                .build();
        Map<String, Object> payload = ReflectionTestUtils.invokeMethod(
                llmService, "buildSuspensionEventPayload", withoutOptions);
        assertThat(payload).containsOnlyKeys("kind", "toolName", "question", "checkpointUuid");
        assertThat(payload.get("kind")).isEqualTo("ASK_USER");
        assertThat(payload.get("toolName")).isEqualTo("ask_user");
        assertThat(payload.get("question")).isEqualTo("你负责哪个部门？");
        assertThat(payload.get("checkpointUuid")).isEqualTo("ckpt-uuid-1");

        SuspensionSignal withOptions = SuspensionSignal.builder()
                .kind(com.pppp.zhimesh.common.enums.PendingCheckpointKind.ASK_USER)
                .toolName("ask_user")
                .requestId("id-ask-2")
                .question("选择审批结论？")
                .options(List.of("同意", "拒绝"))
                .checkpointUuid("ckpt-uuid-2")
                .build();
        Map<String, Object> optionsPayload = ReflectionTestUtils.invokeMethod(
                llmService, "buildSuspensionEventPayload", withOptions);
        assertThat(optionsPayload).containsOnlyKeys("kind", "toolName", "question", "options", "checkpointUuid");
        assertThat(optionsPayload.get("options")).isEqualTo(List.of("同意", "拒绝"));
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private InnerStreamChatParam baseParams(StreamingChatModel streamingModel,
                                            Map<String, ToolExecutor> executors,
                                            ToolContext toolContext, AtomicReference<String> answer) {
        return InnerStreamChatParam.builder()
                .uuid("suspend-uuid")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("帮我整理报销"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-suspend-test")
                .toolExecutorMap(executors)
                .toolContext(toolContext)
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();
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

    private static ToolExecutor fixedTool(String name, String result) {
        return new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name(name).description("test tool " + name).build();
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) {
                return result;
            }
        };
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
