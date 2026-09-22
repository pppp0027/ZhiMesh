package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.SseManager;
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
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阻塞路径（innerChatWithDepth）工具循环达上限的优雅收尾验证：与流式路径
 * （ToolLoopGracefulExhaustionTest）行为对齐——不抛 B_LLM_SERVICE_DISABLED，
 * 而是剥掉工具规格并追加指令再答一轮；收尾轮仍索要工具才报
 * B_TOOL_CALL_LIMIT_EXCEEDED。
 * <p>
 * Graceful exhaustion of the blocking tool loop (innerChatWithDepth): aligned
 * with the streaming path (ToolLoopGracefulExhaustionTest) — instead of
 * B_LLM_SERVICE_DISABLED, one tool-less wrap-up round with an appended
 * instruction; only a wrap-up round that still requests tools raises
 * B_TOOL_CALL_LIMIT_EXCEEDED.
 */
class ToolLoopBlockingWrapUpTest {

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
        // 空 entries 的 SseManager：sendToolCall 对 null/未注册 uuid 静默短路
        // An empty-entries SseManager short-circuits sendToolCall for null/unregistered uuids
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());
        // BaseException 构造经 SpringUtil.getMessage 解析 i18n：装最小 MessageSource 桩
        // BaseException resolves i18n via SpringUtil.getMessage: install a minimal stub
        MessageSource messageSource = mock(MessageSource.class);
        when(context.getBean(MessageSource.class)).thenReturn(messageSource);
        when(messageSource.getMessage(anyString(), isNull(), any(Locale.class))).thenReturn("stub-message");

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
    void loopExhaustionForcesToollessWrapUpRoundInsteadOfError() {
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger round = new AtomicInteger();
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            if (round.incrementAndGet() == 1) {
                return chatResponse(
                        AiMessage.aiMessage(List.of(toolRequest("weather"))), new TokenUsage(100, 20));
            }
            return chatResponse(
                    AiMessage.aiMessage("基于已获取信息的最终回答"), new TokenUsage(150, 30));
        });

        // depth=7 + 本轮工具请求 → depth=8 达到默认上限（8），下一轮必须是无工具收尾
        // depth=7 plus this round's tool request makes depth hit the default cap
        // (8); the next round must be the tool-less wrap-up
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();
        ChatResponse response = ReflectionTestUtils.invokeMethod(llmService, "innerChatWithDepth",
                "blocking-uuid", chatModel, chatModelRequest(), chatRequest(), toolContext, 7);

        // 不抛 B_LLM_SERVICE_DISABLED：收尾轮文本作为正常结果返回
        // No B_LLM_SERVICE_DISABLED: the wrap-up round's text is returned as a normal result
        assertThat(response.aiMessage().text()).isEqualTo("基于已获取信息的最终回答");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, times(2)).chat(captor.capture());
        ChatRequest wrapUp = captor.getAllValues().get(1);
        // 收尾轮必须剥掉工具规格
        // The wrap-up round must strip tool specifications
        assertThat(wrapUp.parameters().toolSpecifications()).isEmpty();
        // 且追加"禁止再调用工具、直接作答"的指令
        // and append the "no more tools, answer directly" instruction
        assertThat(wrapUp.messages().get(wrapUp.messages().size() - 1).toString())
                .contains(AbstractLLMService.TOOL_LIMIT_REACHED_INSTRUCTION);

        // 阻塞路径无 SSE 通道，截断标注只落轨迹（META 合并与历史回放的唯一载体）
        // The blocking path has no SSE channel, so the truncation marker goes to
        // the trace only (the sole carrier for META merge and history replay)
        assertThat(toolContext.getToolTraces())
                .anySatisfy(trace -> {
                    assertThat(trace.getToolName()).isEqualTo(AbstractLLMService.TOOL_LIMIT_MARKER_NAME);
                    assertThat(trace.isSuccess()).isFalse();
                    assertThat(trace.getSeq()).isEqualTo(toolContext.getToolTraces().size() - 1);
                });
    }

    @Test
    void toollessWrapUpStillRequestingToolsThrowsLimitExceeded() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation ->
                // 提供商级异常：无工具规格的收尾轮仍返回工具请求
                // Provider-level anomaly: the tool-less wrap-up round still returns
                // a tool request
                chatResponse(AiMessage.aiMessage(List.of(toolRequest("weather"))), new TokenUsage(100, 20)));

        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(llmService, "innerChatWithDepth",
                "blocking-uuid-2", chatModel, chatModelRequest(), chatRequest(), toolContext, 8))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getCode())
                        .isEqualTo(ErrorEnum.B_TOOL_CALL_LIMIT_EXCEEDED.getCode()));

        // 恰好一次模型调用（收尾轮），无任何后续轮次，绝不无限循环
        // Exactly one model call (the wrap-up round), no further rounds, never an infinite loop
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void loopExhaustionWithoutToolContextStillWrapsUpQuietly() {
        // 无 ToolContext 的阻塞入口：截断标注无轨迹可落，收尾本身不受影响
        // A blocking entry without a ToolContext: the marker has no trace to land
        // in, yet the wrap-up itself is unaffected
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation ->
                chatResponse(AiMessage.aiMessage("无上下文的收尾回答"), new TokenUsage(10, 5)));

        ChatResponse response = ReflectionTestUtils.invokeMethod(llmService, "innerChatWithDepth",
                "blocking-uuid-3", chatModel, chatModelRequest(), chatRequest(), null, 8);

        assertThat(response.aiMessage().text()).isEqualTo("无上下文的收尾回答");
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
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

    private static ChatModelRequest chatModelRequest() {
        return ChatModelRequest.builder()
                .userMessage("天气怎么样？")
                .builtinTools(List.of(fixedTool("weather", "sunny")))
                .build();
    }

    private static ChatRequest chatRequest() {
        return ChatRequest.builder()
                .messages(new ArrayList<>(List.of(UserMessage.from("天气怎么样？"))))
                .parameters(ChatRequestParameters.builder().build())
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
