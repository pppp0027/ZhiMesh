package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
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
import org.mockito.invocation.InvocationOnMock;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具循环的 token 记账验证：中间工具轮与最终轮都必须写入 Redis
 * （每轮一次 input/output），否则配额只按最终轮扣减（少扣）。
 * <p>
 * Token accounting for the tool loop: every round, including intermediate
 * tool rounds, must be written to Redis (one input/output pair per round);
 * otherwise quota is charged for the final round only (under-charged).
 */
class ToolLoopTokenAccountingTest {

    private static final String UUID_KEY = "token-account-uuid";
    private static final String EXPECTED_REDIS_KEY = "token:usage:" + UUID_KEY;

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    /** LLMTokenUtil.rightPushAll 顺序追加的所有 token 数值（每轮 input、output 各一） */
    private List<String> recordedValues;
    private List<String> recordedKeys;

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
        // 空 entries 的 SseManager：sendToolCall 对未注册 uuid 直接短路
        // An empty-entries SseManager makes sendToolCall short-circuit
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());

        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        // SseManager.calculateToken 走 SpringUtil 获取 StringRedisTemplate
        // SseManager.calculateToken fetches StringRedisTemplate via SpringUtil
        when(context.getBean(StringRedisTemplate.class)).thenReturn(redisTemplate);
        llmService.useRedisTemplate(redisTemplate);

        recordedValues = new ArrayList<>();
        recordedKeys = new ArrayList<>();
        doAnswer(this::recordRightPushAll).when(listOperations).rightPushAll(anyString(), any(String[].class));

        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void streamingToolLoopRecordsTokensForEveryRound() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                // 第一轮：请求调用 weather 工具
                // Round 1: the model asks to call the weather tool
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("weather"))), new TokenUsage(100, 20)));
            } else {
                // 第二轮：拿到工具结果后输出纯文本收尾
                // Round 2: with the tool result the model answers in plain text
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("It is sunny today"), new TokenUsage(150, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid(UUID_KEY)
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("What's the weather?"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-token-test")
                .toolExecutorMap(Map.of("weather", fixedTool("weather", "sunny")))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .consumer((content, promptMeta, answerMeta) -> {
                })
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        // 两轮各入账一次（中间轮 + 最终轮），共 4 个元素，不重复不少记
        // Each round is recorded exactly once (intermediate + final), 4 elements in total
        verify(listOperations, times(2)).rightPushAll(anyString(), any(String[].class));
        assertThat(recordedKeys).containsExactly(EXPECTED_REDIS_KEY, EXPECTED_REDIS_KEY);
        assertThat(recordedValues).containsExactly("100", "20", "150", "30");
    }

    @Test
    void blockingToolLoopRecordsTokensForEveryRound() {
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger round = new AtomicInteger();
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            if (round.incrementAndGet() == 1) {
                return chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("weather"))), new TokenUsage(100, 20));
            }
            return chatResponse(AiMessage.aiMessage("It is sunny today"), new TokenUsage(150, 30));
        });

        // 不注册执行器：工具名未知的兜底分支同样驱动两轮循环，且绕开阻塞路径
        // sseUuid=null 时 sendToolCall 的空指针（先于本任务的历史问题）
        // Register no executor: the unknown-tool fallback still drives the two-round
        // loop, and avoids the pre-existing NPE in sendToolCall when the blocking
        // path passes sseUuid=null (a historical issue predating this task)
        ChatModelRequest chatModelRequest = ChatModelRequest.builder().build();
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(UserMessage.from("What's the weather?"))))
                .parameters(ChatRequestParameters.builder().build())
                .build();

        ChatResponse finalResponse = ReflectionTestUtils.invokeMethod(llmService, "innerChatWithDepth",
                UUID_KEY, chatModel, chatModelRequest, chatRequest,
                ToolContext.builder().toolTraces(new ArrayList<>()).build(), 0);

        assertThat(finalResponse.aiMessage().text()).isEqualTo("It is sunny today");
        verify(listOperations, times(2)).rightPushAll(anyString(), any(String[].class));
        assertThat(recordedKeys).containsExactly(EXPECTED_REDIS_KEY, EXPECTED_REDIS_KEY);
        assertThat(recordedValues).containsExactly("100", "20", "150", "30");
    }

    @Test
    void streamingMiddleRoundWithoutTokenUsageIsSkippedSafely() {
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger round = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (round.incrementAndGet() == 1) {
                // 中间轮无 tokenUsage：应静默跳过而不是 NPE，也不产生记录
                // Middle round without tokenUsage: skipped silently instead of NPE, no record
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.aiMessage(List.of(toolRequest("weather"))))
                        .metadata(ChatResponseMetadata.builder().build())
                        .build());
            } else {
                handler.onCompleteResponse(chatResponse(
                        AiMessage.aiMessage("It is sunny today"), new TokenUsage(150, 30)));
            }
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        InnerStreamChatParam params = InnerStreamChatParam.builder()
                .uuid(UUID_KEY)
                .user(new User())
                .streamingChatModel(streamingModel)
                .chatRequest(ChatRequest.builder()
                        .messages(new ArrayList<>(List.of(UserMessage.from("What's the weather?"))))
                        .parameters(ChatRequestParameters.builder().build())
                        .build())
                .sseUuid("sse-token-test")
                .toolExecutorMap(Map.of("weather", fixedTool("weather", "sunny")))
                .toolContext(ToolContext.builder().toolTraces(new ArrayList<>()).build())
                .consumer((content, promptMeta, answerMeta) -> {
                })
                .build();

        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", params);

        // 仅最终轮入账一次
        // Only the final round is recorded, exactly once
        verify(listOperations, times(1)).rightPushAll(anyString(), any(String[].class));
        assertThat(recordedValues).containsExactly("150", "30");
    }

    /**
     * 记录 LLMTokenUtil 发起的 rightPushAll 调用（key + 每轮 input/output 数值）
     * Record rightPushAll invocations issued by LLMTokenUtil (key + per-round input/output values)
     */
    private Object recordRightPushAll(InvocationOnMock invocation) {
        recordedKeys.add(invocation.getArgument(0));
        Object[] args = invocation.getArguments();
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof String[] values) {
                recordedValues.addAll(Arrays.asList(values));
            } else {
                recordedValues.add((String) args[i]);
            }
        }
        return 1L;
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
        protected LLMException parseError(Object error) {
            return null;
        }

        @Override
        public TokenCountEstimator getTokenEstimator() {
            return null;
        }
    }
}
