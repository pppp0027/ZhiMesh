package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.AnswerMeta;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.PromptMeta;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同一工具连续失败熔断验证：达到 zhimesh.agent.max-tool-failures-per-tool（默认 3）
 * 后本请求不再真正执行该工具，熔断说明回喂模型；成功一次即清零；0=关闭。
 * <p>
 * Per-tool consecutive-failure breaker: at
 * zhimesh.agent.max-tool-failures-per-tool (default 3) the tool stops being
 * executed within the request and a breaker notice is fed back to the model;
 * one success resets the count; zero disables the breaker.
 */
class ToolLoopFailureBreakerTest {

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private StringRedisTemplate redisTemplate;

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
        when(context.getBean(ZhiMeshProperties.class)).thenReturn(new ZhiMeshProperties());

        redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        llmService.useRedisTemplate(redisTemplate);

        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void consecutiveFailuresDisableTheToolAndFeedNoticeToModel() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger realExecutions = new AtomicInteger();
        ToolExecutor failingTool = failingTool("fetch", realExecutions);
        // 第 1~4 轮模型都请求 fetch，第 5 轮直接作答；前 3 次真实执行失败，
        // 第 4 次触发熔断（不再真实执行），熔断说明作为工具结果回喂
        // Rounds 1-4 request fetch and round 5 answers directly; the first three
        // calls really execute and fail, the fourth hits the breaker (no real
        // execution) and the notice is fed back as the tool result
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() <= 4) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("fetch"))), new TokenUsage(100, 20)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("fetch 不可用，基于已有信息回答"), new TokenUsage(150, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<String> answer = new AtomicReference<>();
        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid("breaker-uuid")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("帮我查个网页"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-breaker-test")
                .toolExecutorMap(Map.of("fetch", failingTool))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        assertThat(answer.get()).isEqualTo("fetch 不可用，基于已有信息回答");
        // 真实执行恰好 3 次（默认阈值），第 4 次被熔断拦截
        // Exactly 3 real executions (default threshold); the fourth is blocked
        assertThat(realExecutions.get()).isEqualTo(3);
        // 熔断说明作为工具结果进入消息链（模型能看到"已停用"而不是又一个报错）
        // The breaker notice enters the message chain as a tool result (the model
        // sees "disabled" instead of yet another error)
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel, times(5)).chat(captor.capture(), any(StreamingChatResponseHandler.class));
        ChatRequest fourthRound = captor.getAllValues().get(4);
        assertThat(fourthRound.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .anyMatch(text -> text != null && text.contains("is now disabled")))
                .isTrue();
        // 轨迹同样记录了熔断（刷新后步骤条仍可见）
        // The trace records the breaker too (visible on the step bar after refresh)
        assertThat(params.getToolContext().getToolTraces())
                .anySatisfy(trace -> {
                    assertThat(trace.getToolName()).isEqualTo("fetch");
                    assertThat(trace.getResultSummary()).contains("is now disabled");
                });
    }

    @Test
    void oneSuccessResetsTheFailureCount() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger executions = new AtomicInteger();
        // 失败、失败、成功：计数 1→2→清零，始终未达 3，工具从未被熔断
        // fail, fail, success: 1 → 2 → reset; the cap is never reached
        ToolExecutor flakyTool = new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("flaky").description("flaky tool").build();
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) {
                return executions.incrementAndGet() < 3
                        ? throwFailure() : "ok";
            }
        };
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() <= 3) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("flaky"))), new TokenUsage(100, 20)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("最终答案"), new TokenUsage(150, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<String> answer = new AtomicReference<>();
        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid("breaker-reset-uuid")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("再试一次"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-breaker-reset-test")
                .toolExecutorMap(Map.of("flaky", flakyTool))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        assertThat(answer.get()).isEqualTo("最终答案");
        assertThat(executions.get()).isEqualTo(3);
        assertThat(params.getToolContext().getToolTraces())
                .noneSatisfy(trace -> assertThat(trace.getResultSummary()).contains("is now disabled"));
    }

    @Test
    void breakerDisabledWhenThresholdIsZero() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getAgent().setMaxToolFailuresPerTool(0);
        when(context.getBean(ZhiMeshProperties.class)).thenReturn(properties);

        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger executions = new AtomicInteger();
        ToolExecutor failingTool = failingTool("fetch", executions);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() <= 4) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("fetch"))), new TokenUsage(100, 20)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("关闭熔断时的回答"), new TokenUsage(150, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<String> answer = new AtomicReference<>();
        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid("breaker-off-uuid")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("查网页"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-breaker-off-test")
                .toolExecutorMap(Map.of("fetch", failingTool))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        assertThat(answer.get()).isEqualTo("关闭熔断时的回答");
        // 0=关闭：4 次请求全部真实执行，无熔断
        // Zero disables the breaker: all four requests really execute
        assertThat(executions.get()).isEqualTo(4);
    }

    private static String throwFailure() {
        throw new IllegalStateException("connection failed");
    }

    private static ToolExecutor failingTool(String name, AtomicInteger executions) {
        return new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name(name).description("test tool " + name).build();
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) {
                executions.incrementAndGet();
                throw new IllegalStateException("connection failed");
            }
        };
    }

    private static ChatResponse chatResponse(AiMessage aiMessage, TokenUsage tokenUsage) {
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(ChatResponseMetadata.builder().tokenUsage(tokenUsage).build())
                .build();
    }

    private static ToolExecutionRequest toolRequest(String name) {
        return ToolExecutionRequest.builder()
                .id("id-" + name)
                .name(name)
                .arguments("{\"url\":\"https://example.com\"}")
                .build();
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
