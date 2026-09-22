package com.pppp.zhimesh.common.languagemodel;

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
 * 工具循环达上限时的优雅收尾验证：不直接 [ERROR]，而是剥掉工具规格并追加系统
 * 指令，强制模型基于已获取的信息直接作答（design 验收标准 3）。
 * <p>
 * Graceful exhaustion of the tool loop: instead of an immediate [ERROR], the
 * loop strips tool specifications and appends an instruction so the model must
 * answer directly from what it already gathered (design acceptance criterion 3).
 */
class ToolLoopGracefulExhaustionTest {

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private StringRedisTemplate redisTemplate;

    private final TestLLMService llmService = new TestLLMService();

    @BeforeAll
    static void seedTtsConfig() {
        // AbstractLLMService 构造器需要 tts_setting；{} 即可得到非空 TtsSetting
        // The AbstractLLMService constructor needs tts_setting; "{}" yields a non-null TtsSetting
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
        // 空 entries 的 SseManager：sendToolCall/errorAndShutdown 对未注册 uuid 静默短路
        // An empty-entries SseManager short-circuits sendToolCall/errorAndShutdown
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());

        redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        // calculateToken 的累计读回拿到 null List 时回退最终轮数值，此处不关心具体 meta
        // calculateToken falls back to final-round numbers on a null read-back; the
        // exact metas are irrelevant here
        llmService.useRedisTemplate(redisTemplate);

        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void loopExhaustionForcesToollessWrapUpRoundInsteadOfError() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("weather"))), new TokenUsage(100, 20)));
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("基于已获取信息的最终回答"), new TokenUsage(150, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        // toolCallDepth=7 + 本轮工具请求 → depth=8 达到默认上限（8），下一轮必须走无工具收尾
        // toolCallDepth=7 plus this round's tool request makes depth hit the default
        // cap (8); the next round must be the tool-less wrap-up
        AtomicReference<String> answer = new AtomicReference<>();
        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid("graceful-uuid")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("天气怎么样？"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-graceful-test")
                .toolExecutorMap(Map.of("weather", fixedTool("weather", "sunny")))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .toolCallDepth(7)
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        // 无 [ERROR]：最终文本经 consumer 正常送达
        // No [ERROR]: the final text reaches the consumer normally
        assertThat(answer.get()).isEqualTo("基于已获取信息的最终回答");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel, times(2)).chat(captor.capture(), any(StreamingChatResponseHandler.class));
        ChatRequest wrapUp = captor.getAllValues().get(1);
        // 收尾轮必须剥掉工具规格（空列表覆盖原值）
        // The wrap-up round must strip tool specifications (empty list overrides)
        assertThat(wrapUp.parameters().toolSpecifications()).isEmpty();
        // 且追加"禁止再调用工具、直接作答"的系统指令
        // and append the "no more tools, answer directly" instruction
        assertThat(wrapUp.messages().get(wrapUp.messages().size() - 1).toString())
                .contains(AbstractLLMService.TOOL_LIMIT_REACHED_INSTRUCTION);

        // 截断标注同时落进轨迹：META 合并与历史回放都要能看到"本轮被上限截断"，
        // 否则刷新后步骤条丢失这条标注
        // The truncation marker is also recorded as a trace: META merge and history
        // replay must both show "this round was cut short by the cap", or the
        // step bar loses the marker after a refresh
        assertThat(params.getToolContext().getToolTraces())
                .anySatisfy(trace -> {
                    assertThat(trace.getToolName()).isEqualTo(AbstractLLMService.TOOL_LIMIT_MARKER_NAME);
                    assertThat(trace.isSuccess()).isFalse();
                    assertThat(trace.getSeq()).isEqualTo(params.getToolContext().getToolTraces().size() - 1);
                });
    }

    @Test
    void loopExhaustionWithoutToolContextStillWrapsUpQuietly() {
        // 非 Agentic 入口无 ToolContext：标注只走 SSE，轨迹缺失不影响优雅收尾
        // Non-agentic entries carry no ToolContext: the marker goes SSE-only and
        // its missing trace must not break the graceful wrap-up
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage("无上下文的收尾回答"), new TokenUsage(10, 5)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<String> answer = new AtomicReference<>();
        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid("graceful-uuid-3")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("hi"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-graceful-test-3")
                .toolCallDepth(7)
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        assertThat(answer.get()).isEqualTo("无上下文的收尾回答");
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    void toollessWrapUpStillRequestingToolsTerminatesWithoutThirdModelCall() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            // 提供商级异常：无工具规格的收尾轮仍返回工具请求
            // Provider-level anomaly: the tool-less wrap-up round still returns a
            // tool request
            handler.onCompleteResponse(chatResponse(
                    AiMessage.aiMessage(List.of(toolRequest("weather"))), new TokenUsage(100, 20)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<String> answer = new AtomicReference<>();
        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid("graceful-uuid-2")
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("天气怎么样？"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-graceful-test-2")
                .toolExecutorMap(Map.of("weather", fixedTool("weather", "sunny")))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .toolCallDepth(7)
                .consumer((LLMResponseContent content, PromptMeta promptMeta, AnswerMeta answerMeta) ->
                        answer.set(content.getContent()))
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        // 终止为错误：consumer 不收到文本，模型恰好被调 2 次（上限轮 + 收尾轮），无无限循环
        // Terminated as an error: the consumer gets no text and the model is called
        // exactly twice (cap round + wrap-up round), with no infinite loop
        assertThat(answer.get()).isNull();
        verify(streamingModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
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
                .arguments("{\"city\":\"guangzhou\"}")
                .build();
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
