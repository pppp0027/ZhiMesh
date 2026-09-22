package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.data.InnerStreamChatParam;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import com.pppp.zhimesh.common.languagemodel.tool.McpToolExecutor;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.mockito.ArgumentCaptor;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2 统一 ToolExecutor 抽象的行为验证：混合注册按名解析、重名时内置优先、
 * 未知工具名兜底、超时/截断保护只作用于内置工具、循环上限走配置。
 * <p>
 * Behavioral verification for the unified ToolExecutor abstraction: by-name
 * resolution across mixed registrations, builtin-wins name conflicts, the
 * unknown-tool fallback, guardrails (timeout/truncation) applied to builtin
 * tools only, and the configuration-driven loop limit.
 */
class ToolExecutorResolutionTest {

    private ApplicationContext context;
    private ApplicationContext previousContext;

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
    void setUp() {
        context = mock(ApplicationContext.class);
        // 空 entries 的 SseManager：sendToolCall/errorAndShutdown 对未注册 uuid 直接短路
        // An empty-entries SseManager makes sendToolCall/errorAndShutdown short-circuit
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void mixedMcpAndBuiltinToolsResolveByName() {
        McpClient mcpClient = mock(McpClient.class);
        ToolSpecification weather = toolSpec("weather");
        ToolSpecification maps = toolSpec("maps");
        when(mcpClient.listTools()).thenReturn(List.of(weather, maps));
        ToolExecutor builtin = fixedTool("search_knowledge", "kb answer");
        ChatModelRequest request = ChatModelRequest.builder()
                .mcpClients(List.of(mcpClient))
                .builtinTools(List.of(builtin))
                .build();

        Map<String, ToolExecutor> tools = discoverRequestTools(request);

        assertThat(tools).containsOnlyKeys("weather", "maps", "search_knowledge");
        assertThat(tools.get("weather")).isInstanceOf(McpToolExecutor.class);
        assertThat(tools.get("weather").spec()).isSameAs(weather);
        assertThat(tools.get("maps").spec()).isSameAs(maps);
        assertThat(tools.get("search_knowledge")).isSameAs(builtin);
    }

    @Test
    void builtinToolOverridesMcpToolOnNameConflict() {
        McpClient mcpClient = mock(McpClient.class);
        ToolSpecification conflicting = toolSpec("search");
        ToolSpecification unique = toolSpec("unique_mcp");
        when(mcpClient.listTools()).thenReturn(List.of(conflicting, unique));
        ToolExecutor builtin = fixedTool("search", "builtin answer");
        ChatModelRequest request = ChatModelRequest.builder()
                .mcpClients(List.of(mcpClient))
                .builtinTools(List.of(builtin))
                .build();

        Map<String, ToolExecutor> tools = discoverRequestTools(request);

        assertThat(tools).containsOnlyKeys("search", "unique_mcp");
        assertThat(tools.get("search")).isSameAs(builtin);
        assertThat(tools.get("unique_mcp")).isInstanceOf(McpToolExecutor.class);
    }

    @Test
    void unknownToolNameYieldsErrorResultMessageWithoutThrowing() {
        ToolExecutor known = fixedTool("known", "ok");
        Map<String, ToolExecutor> tools = new HashMap<>(Map.of("known", known));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                aiMessageWithRequests("ghost"), tools, toolContext);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).text()).isEqualTo("No Tool executor found for this tool request");
        assertThat(messages.get(0).toolName()).isEqualTo("ghost");
        assertThat(toolContext.getToolTraces()).hasSize(1);
        assertThat(toolContext.getToolTraces().get(0))
                .extracting(ToolCallTrace::getToolName, ToolCallTrace::isSuccess, ToolCallTrace::getSeq)
                .containsExactly("ghost", false, 0);
    }

    @Test
    void builtinToolResultIsTruncatedToConfiguredMaxChars() {
        stubAgentSettings(agent -> agent.setToolResultMaxChars(10));
        ToolExecutor builtin = fixedTool("search_knowledge", "x".repeat(25));
        Map<String, ToolExecutor> tools = new HashMap<>(Map.of("search_knowledge", builtin));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                aiMessageWithRequests("search_knowledge"), tools, toolContext);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).text()).isEqualTo("x".repeat(10) + "\n...[truncated]");
        assertThat(toolContext.getToolTraces().get(0).isSuccess()).isTrue();
        assertThat(toolContext.getToolTraces().get(0).getResultSummary()).isEqualTo("x".repeat(10) + "\n...[truncated]");
    }

    @Test
    void builtinToolFailureBecomesFailureResultMessage() {
        ToolExecutor failing = new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return toolSpec("failing_tool");
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) {
                throw new IllegalStateException("boom");
            }
        };
        Map<String, ToolExecutor> tools = new HashMap<>(Map.of("failing_tool", failing));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                aiMessageWithRequests("failing_tool"), tools, toolContext);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).text()).isEqualTo("boom");
        assertThat(toolContext.getToolTraces().get(0).isSuccess()).isFalse();
    }

    @Test
    void builtinToolTimesOutAndReportsFailure() {
        stubAgentSettings(agent -> agent.setToolTimeoutMs(50));
        ToolExecutor slow = new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return toolSpec("slow_tool");
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) throws Exception {
                Thread.sleep(5000);
                return "late";
            }
        };
        Map<String, ToolExecutor> tools = new HashMap<>(Map.of("slow_tool", slow));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                aiMessageWithRequests("slow_tool"), tools, toolContext);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).text())
                .contains("timed out")
                .contains("slow_tool");
        assertThat(toolContext.getToolTraces().get(0).isSuccess()).isFalse();
    }

    @Test
    void mcpToolResultIsNeitherTruncatedNorAffectedByBuiltinTimeout() {
        // 截断阈值远小于结果长度且超时极短：MCP 工具必须原样返回，证明保护只作用于内置工具
        // Truncation threshold far below the result length and a tiny timeout:
        // the MCP tool must return verbatim, proving guardrails apply to builtin tools only
        stubAgentSettings(agent -> {
            agent.setToolResultMaxChars(10);
            agent.setToolTimeoutMs(1);
        });
        McpClient client = mock(McpClient.class);
        String longText = "y".repeat(25);
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().resultText(longText).build());
        Map<String, ToolExecutor> tools =
                new HashMap<>(Map.of("weather", new McpToolExecutor(toolSpec("weather"), client)));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                aiMessageWithRequests("weather"), tools, toolContext);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).text()).isEqualTo(longText);
        assertThat(toolContext.getToolTraces().get(0).isSuccess()).isTrue();
    }

    @Test
    void toolLoopStopsAtConfiguredMaxIterations() throws Exception {
        stubAgentSettings(agent -> agent.setMaxToolIterations(2));
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        McpClient mcpClient = mock(McpClient.class);
        ChatRequest withTools = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(UserMessage.from("hi"))))
                .parameters(ChatRequestParameters.builder()
                        .toolSpecifications(ToolSpecification.builder().name("weather").build())
                        .build())
                .build();

        // 达到上限的轮次不再直接 [ERROR]，而是强制一次无工具收尾：模型恰好被调一次，
        // 且请求的工具规格被清空、末尾追加收尾指令
        // A round at the cap no longer errors immediately but forces one tool-less
        // wrap-up: the model is called exactly once, with tool specs cleared and
        // the wrap-up instruction appended
        InnerStreamChatParam exhausted = InnerStreamChatParam.builder()
                .uuid("u1")
                .sseUuid("sse-1")
                .streamingChatModel(streamingModel)
                .mcpClients(List.of(mcpClient))
                .chatRequest(withTools)
                .toolCallDepth(2)
                .build();
        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", exhausted);

        // 未达上限的轮次照常携带原工具规格进入模型
        // A round below the cap enters the model with the original tool specs intact
        InnerStreamChatParam proceeding = InnerStreamChatParam.builder()
                .uuid("u1")
                .sseUuid("sse-1")
                .streamingChatModel(streamingModel)
                .mcpClients(List.of(mcpClient))
                .chatRequest(withTools)
                .toolCallDepth(1)
                .build();
        ReflectionTestUtils.invokeMethod(llmService, "innerStreamingChat", proceeding);

        // 两次调用一起捕获：第 1 次是无工具收尾轮，第 2 次保留原工具规格
        // Both calls captured together: the 1st is the tool-less wrap-up, the
        // 2nd keeps the original tool specs
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingModel, times(2)).chat(captor.capture(), any(StreamingChatResponseHandler.class));
        assertThat(captor.getAllValues().get(0).parameters().toolSpecifications()).isEmpty();
        assertThat(captor.getAllValues().get(0).messages()
                .get(captor.getAllValues().get(0).messages().size() - 1).toString())
                .contains(AbstractLLMService.TOOL_LIMIT_REACHED_INSTRUCTION);
        assertThat(captor.getAllValues().get(1).parameters().toolSpecifications()).hasSize(1);
    }

    @Test
    void runWorkflowRecursionFlagSurvivesExecutorHop() throws Exception {
        // 工作流线程（递归标记已置位）内再起 agent：其工具在 TOOL_EXECUTION_EXECUTOR
        // 池线程上执行，标记必须随提交线程一起传播过去，否则嵌套 run_workflow 被放行
        // An agent started inside a workflow thread (recursion flag set) runs its
        // tools on TOOL_EXECUTION_EXECUTOR pool threads: the flag must propagate
        // from the submitting thread, or a nested run_workflow gets through
        stubAgentSettings(agent -> agent.setToolTimeoutMs(5000));
        java.util.concurrent.atomic.AtomicBoolean flagOnPoolThread = new java.util.concurrent.atomic.AtomicBoolean(false);
        ToolExecutor probe = new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return toolSpec("probe_tool");
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) {
                flagOnPoolThread.set(Boolean.TRUE.equals(
                        com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool.IN_RUN_WORKFLOW.get()));
                return "done";
            }
        };
        Map<String, ToolExecutor> tools = new HashMap<>(Map.of("probe_tool", probe));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool.IN_RUN_WORKFLOW.set(Boolean.TRUE);
        try {
            List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                    aiMessageWithRequests("probe_tool"), tools, toolContext);
            assertThat(messages.get(0).text()).isEqualTo("done");
            // 提交线程自身的标记不受池线程清理影响
            // The submitting thread's own flag is untouched by pool-thread cleanup
            assertThat(com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool.IN_RUN_WORKFLOW.get()).isTrue();
        } finally {
            com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool.IN_RUN_WORKFLOW.remove();
        }

        assertThat(flagOnPoolThread.get()).isTrue();
    }

    @Test
    void runWorkflowRecursionFlagNotLeakedOntoPoolThread() throws Exception {
        // 提交线程无标记：池线程（可能复用）绝不能看到脏标记，否则会误拒合法的 run_workflow
        // No flag on the submitting thread: a (possibly reused) pool thread must
        // never observe a stale flag, or legitimate run_workflow calls get rejected
        stubAgentSettings(agent -> agent.setToolTimeoutMs(5000));
        java.util.concurrent.atomic.AtomicBoolean flagOnPoolThread = new java.util.concurrent.atomic.AtomicBoolean(true);
        ToolExecutor probe = new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return toolSpec("probe_tool");
            }

            @Override
            public String execute(ToolExecutionRequest request, ToolContext toolContext) {
                flagOnPoolThread.set(Boolean.TRUE.equals(
                        com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool.IN_RUN_WORKFLOW.get()));
                return "done";
            }
        };
        Map<String, ToolExecutor> tools = new HashMap<>(Map.of("probe_tool", probe));
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>()).build();

        List<ToolExecutionResultMessage> messages = createToolExecutionMessages(
                aiMessageWithRequests("probe_tool"), tools, toolContext);

        assertThat(messages.get(0).text()).isEqualTo("done");
        assertThat(flagOnPoolThread.get()).isFalse();
    }

    @Test
    void agentSettingsFallBackToDefaultsAndHonorOverrides() {
        // 未打桩时 mock 返回 null，应回退默认值（与直接 new ZhiMeshProperties() 一致）
        // Without a stub the mock returns null, so defaults must apply (same as new ZhiMeshProperties())
        ZhiMeshProperties.Agent defaults = resolveAgentSettings();
        assertThat(defaults.getMaxToolIterations()).isEqualTo(8);
        assertThat(defaults.getToolTimeoutMs()).isEqualTo(60000);
        assertThat(defaults.getToolResultMaxChars()).isEqualTo(4000);

        assertThat(new ZhiMeshProperties().getAgent().getMaxToolIterations()).isEqualTo(8);

        stubAgentSettings(agent -> {
            agent.setMaxToolIterations(3);
            agent.setToolTimeoutMs(1234);
            agent.setToolResultMaxChars(567);
        });
        ZhiMeshProperties.Agent overridden = resolveAgentSettings();
        assertThat(overridden.getMaxToolIterations()).isEqualTo(3);
        assertThat(overridden.getToolTimeoutMs()).isEqualTo(1234);
        assertThat(overridden.getToolResultMaxChars()).isEqualTo(567);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolExecutor> discoverRequestTools(ChatModelRequest request) {
        return (Map<String, ToolExecutor>) ReflectionTestUtils.invokeMethod(llmService, "discoverRequestTools", request);
    }

    @SuppressWarnings("unchecked")
    private List<ToolExecutionResultMessage> createToolExecutionMessages(AiMessage aiMessage,
                                                                         Map<String, ToolExecutor> tools,
                                                                         ToolContext toolContext) {
        return (List<ToolExecutionResultMessage>) ReflectionTestUtils.invokeMethod(
                llmService, "createToolExecutionMessages", aiMessage, tools, toolContext, "sse-test");
    }

    private ZhiMeshProperties.Agent resolveAgentSettings() {
        return ReflectionTestUtils.invokeMethod(llmService, "resolveAgentSettings");
    }

    private void stubAgentSettings(Consumer<ZhiMeshProperties.Agent> customizer) {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        customizer.accept(properties.getAgent());
        when(context.getBean(ZhiMeshProperties.class)).thenReturn(properties);
    }

    private static AiMessage aiMessageWithRequests(String... toolNames) {
        List<ToolExecutionRequest> requests = Arrays.stream(toolNames)
                .map(name -> ToolExecutionRequest.builder()
                        .id("id-" + name)
                        .name(name)
                        .arguments("{\"query\":\"x\"}")
                        .build())
                .toList();
        return AiMessage.aiMessage(requests);
    }

    private static ToolSpecification toolSpec(String name) {
        return ToolSpecification.builder().name(name).description("test tool " + name).build();
    }

    private static ToolExecutor fixedTool(String name, String result) {
        return new ToolExecutor() {
            @Override
            public ToolSpecification spec() {
                return toolSpec(name);
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
