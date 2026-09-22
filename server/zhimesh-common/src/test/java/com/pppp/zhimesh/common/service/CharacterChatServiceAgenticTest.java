package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterMessageToolCall;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.Workflow;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.RunWorkflowTool;
import com.pppp.zhimesh.common.languagemodel.tool.SearchKnowledgeTool;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolRagContext;
import com.pppp.zhimesh.common.mapper.CharacterMessageToolCallMapper;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.rag.DeduplicatingContentRetriever;
import com.pppp.zhimesh.common.rag.ZhiMeshEmbeddingStoreContentRetriever;
import com.pppp.zhimesh.common.rag.bm25.Bm25ContentRetriever;
import com.pppp.zhimesh.common.rag.intent.MemoryRetrievalPolicy;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.AnswerMeta;
import com.pppp.zhimesh.common.vo.ChatContext;
import com.pppp.zhimesh.common.vo.PromptMeta;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import com.pppp.zhimesh.common.vo.SseAskParam;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import com.pppp.zhimesh.common.workflow.WfNodeInputConfig;
import com.pppp.zhimesh.common.workflow.WorkflowStarter;
import com.pppp.zhimesh.common.workflow.def.WfNodeIOText;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4 Agentic 分支的行为验证：isAgentic=false 时请求逐字节等价（回归锁）、
 * isAgentic=true 时注册 search_knowledge 并贯通 ToolContext、工具证据合并去重、
 * 轨迹落库参数、meta 载荷扩展。
 * <p>
 * Behavioral verification for the T4 agentic branch: byte-for-byte equivalence
 * when isAgentic=false (regression lock), search_knowledge registration and
 * ToolContext wiring when isAgentic=true, tool-evidence merge dedup, trace
 * persistence parameters, and the extended meta payload.
 */
class CharacterChatServiceAgenticTest {

    private static final String SSE_UUID = "sse-agentic-test";
    private static final String MODEL_PLATFORM = "test-platform";
    private static final String MODEL_NAME = "test-model";

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private List<AbstractLLMService> previousLlmServices;

    private CharacterChatService chatService;
    private ChatContextResolver chatContextResolver;
    private CharacterService characterService;
    private SseManager sseManager;
    private CharacterMessageToolCallMapper toolCallMapper;
    private CharacterMessageService characterMessageService;
    private CharacterChatService self;
    private AbstractLLMService llmService;
    private EmbeddingModel embeddingModel;
    private WorkflowService workflowService;
    private WorkflowNodeService workflowNodeService;
    private WorkflowStarter workflowStarter;

    private User user;
    private Character character;
    private List<KbInfoResp> filteredKb;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // ===== SpringUtil 上下文桩：sendPartial/getEntry 短路、retrieve 走 trivial 短路、模型路由健康 =====
        // SpringUtil context stub: sendPartial/getEntry short-circuit, retrieval
        // takes the trivial-turn shortcut, model routing is healthy
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

        // ===== LLM 静态注册表：挂入一个健康可用的 mock 服务，结束后还原快照 =====
        // Static LLM registry: register one healthy mock service, restore the snapshot afterwards
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
        previousLlmServices = List.copyOf(LLMContext.getAllServices());
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
        LLMContext.addLLMService(llmService);

        // ===== 被测服务与依赖 / Service under test and its dependencies =====
        chatService = new CharacterChatService();
        chatContextResolver = mock(ChatContextResolver.class);
        characterService = mock(CharacterService.class);
        sseManager = mock(SseManager.class);
        toolCallMapper = mock(CharacterMessageToolCallMapper.class);
        characterMessageService = mock(CharacterMessageService.class);
        self = mock(CharacterChatService.class);
        embeddingModel = mock(EmbeddingModel.class);
        workflowService = mock(WorkflowService.class);
        workflowNodeService = mock(WorkflowNodeService.class);
        workflowStarter = mock(WorkflowStarter.class);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        ReflectionTestUtils.setField(chatService, "chatContextResolver", chatContextResolver);
        ReflectionTestUtils.setField(chatService, "characterService", characterService);
        ReflectionTestUtils.setField(chatService, "sseManager", sseManager);
        ReflectionTestUtils.setField(chatService, "characterMessageService", characterMessageService);
        ReflectionTestUtils.setField(chatService, "characterMessageToolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "self", self);
        ReflectionTestUtils.setField(chatService, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(chatService, "workflowService", workflowService);
        ReflectionTestUtils.setField(chatService, "workflowNodeService", workflowNodeService);
        ReflectionTestUtils.setField(chatService, "workflowStarter", workflowStarter);
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
        // getAnswerContentType 直接拆箱实体字段，测试里给一个合法默认值
        // getAnswerContentType unboxes the entity field directly; give it a valid default here
        character.setAnswerContentType(0);

        KbInfoResp kb = new KbInfoResp();
        kb.setUuid("kb-uuid-a");
        kb.setTitle("KB-A");
        filteredKb = new ArrayList<>(List.of(kb));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
        // 还原静态 LLM 注册表快照与健康缓存，避免污染其它测试类
        // Restore the static LLM registry snapshot and health cache to avoid leaking into other test classes
        ReflectionTestUtils.setField(LLMContext.class, "LLM_SERVICES", previousLlmServices);
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
        // 清理 run_workflow 的递归标记，避免跨用例泄漏
        // Clear the run_workflow recursion flag to avoid cross-test leakage
        RunWorkflowTool.IN_RUN_WORKFLOW.remove();
    }

    // ==================== ask() 分支（回归锁 + Agentic 注册） ====================

    @Test
    void nonAgenticCharacterKeepsRequestUntouched() {
        // isAgentic 为 null 与显式 false 都不得构造 ToolContext / builtinTools（回归锁）
        // Both a null and an explicit false isAgentic must construct neither
        // ToolContext nor builtinTools (regression lock)
        // List.of 不接受 null 元素，用 Arrays.asList 承载 null/false 两种取值
        // List.of rejects null elements; use Arrays.asList to carry both null and false
        for (Boolean isAgentic : java.util.Arrays.asList(null, Boolean.FALSE)) {
            runExecuteChat(isAgentic, true, null);
        }

        ArgumentCaptor<SseAskParam> paramCaptor = ArgumentCaptor.forClass(SseAskParam.class);
        ArgumentCaptor<TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta>> callbackCaptor =
                ArgumentCaptor.forClass((Class) TriConsumer.class);
        verify(sseManager, times(2)).call(eq(llmService), paramCaptor.capture(), any(), callbackCaptor.capture());
        for (SseAskParam param : paramCaptor.getAllValues()) {
            assertThat(param.getHttpRequestParams().getBuiltinTools())
                    .as("isAgentic=null/false must not register builtin tools").isNull();
            assertThat(param.getToolContext())
                    .as("isAgentic=null/false must not build a tool context").isNull();
        }

        // 完成回调后 meta 也不携带 toolCalls（旧载荷形状不变）
        // After completion the meta carries no toolCalls either (legacy payload shape)
        AnswerMeta meta = AnswerMeta.builder().build();
        callbackCaptor.getValue().accept(new LLMResponseContent(null, "answer", null),
                new PromptMeta(1, "q-uuid"), meta);
        assertThat(meta.getToolCalls()).isNull();
        verify(self).saveAfterAiResponse(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), eq((ToolContext) null));

        // 非 Agentic 路径连工作流可见性查询都不触发（零额外开销）
        // The non-agentic path never even triggers the workflow visibility query
        verifyNoInteractions(workflowService);
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void agenticCharacterRegistersSearchKnowledgeToolWithContext() {
        // 有短期记忆ID但 understandContextEnable 关闭：记忆接线保持为空（负例）
        // A short-memory id exists but understandContextEnable is off: the memory
        // wiring stays null (negative case)
        runExecuteChat(Boolean.TRUE, true, "mem-1");

        SseAskParamCapture captured = captureAskParam();
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools()).hasSize(1);
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools().get(0))
                .isInstanceOf(SearchKnowledgeTool.class);

        ToolContext toolContext = captured.param.getToolContext();
        assertThat(toolContext).isNotNull();
        assertThat(toolContext.getUser()).isSameAs(user);
        assertThat(toolContext.getCharacterId()).isEqualTo(55L);
        // understandContextEnable 关闭时记忆接线为空，与预检索口径一致
        // Memory wiring stays null while understandContextEnable is off, matching pre-retrieval
        assertThat(toolContext.getMemoryId()).isNull();
        assertThat(toolContext.getToolTraces()).isEmpty();
        assertThat(toolContext.getRefCollector()).isEmpty();

        ToolRagContext ragContext = toolContext.getRagContext();
        assertThat(ragContext).isNotNull();
        assertThat(ragContext.getFilteredKb()).isSameAs(filteredKb);
        assertThat(ragContext.getLlmService()).isSameAs(llmService);
        assertThat(ragContext.getEmbeddingModel()).isSameAs(embeddingModel);
    }

    @Test
    void agenticCharacterGatesOnAvailableKnowledgeBases() {
        // 开关打开但既无可检索知识库也无工作流可用：不注册任何对象
        // Switch on but neither a searchable KB nor a workflow exists: register nothing
        runExecuteChat(Boolean.TRUE, false, null);

        SseAskParamCapture captured = captureAskParam();
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools()).isNull();
        assertThat(captured.param.getToolContext()).isNull();
    }

    // ==================== run_workflow 注册门控 / run_workflow registration gating ====================

    @Test
    void agenticCharacterRegistersOnlyRunWorkflowWithoutKnowledgeBases() {
        // 开关打开、无知识库但有可调用工作流：只注册 run_workflow；ToolContext 可用但
        // ragContext 为 null（search_knowledge 未注册，null ragContext 短路兼容保持）
        // Switch on, no KB but runnable workflows exist: only run_workflow is
        // registered; the ToolContext exists yet ragContext stays null
        // (search_knowledge is not registered; null-ragContext compatibility kept)
        runExecuteChat(Boolean.TRUE, false, null,
                List.of(workflow("周报生成", "wf-uuid-1", 1L)), Map.of());

        SseAskParamCapture captured = captureAskParam();
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools()).hasSize(1);
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools().get(0))
                .isInstanceOf(RunWorkflowTool.class);

        ToolContext toolContext = captured.param.getToolContext();
        assertThat(toolContext).isNotNull();
        assertThat(toolContext.getUser()).isSameAs(user);
        assertThat(toolContext.getRagContext()).isNull();
    }

    @Test
    void agenticCharacterRegistersBothToolsWhenKnowledgeBasesAndWorkflowsAvailable() {
        // 知识库与工作流同时可用：两个内置工具都注册
        // Both KBs and workflows available: register both builtin tools
        runExecuteChat(Boolean.TRUE, true, null,
                List.of(workflow("周报生成", "wf-uuid-1", 1L)), Map.of());

        SseAskParamCapture captured = captureAskParam();
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools()).hasSize(2);
        assertThat(captured.param.getHttpRequestParams().getBuiltinTools())
                .extracting(tool -> tool.getClass().getSimpleName())
                .containsExactly("SearchKnowledgeTool", "RunWorkflowTool");
        // 有知识库时 ragContext 照常构造（既有断言语义不变）
        // ragContext is built as usual when KBs exist (existing semantics)
        assertThat(captured.param.getToolContext().getRagContext()).isNotNull();
    }

    @Test
    void registeredRunWorkflowToolResolvesTitleToVisibleWorkflowAndWrapsInput() throws Exception {
        // 端到端接线：ask() 把 Workflow+起始节点文本输入定义映射进工具可见清单；被注册的
        // 工具按标题精确命中 uuid，并把 input 包装成起始节点期望的 ObjectNode 形状
        // （maxLength=10 超长先行截断），输出按固定格式包装
        // End-to-end wiring: ask() maps Workflow + start-node TEXT defs into the
        // tool catalog; the registered tool resolves the title to the uuid and
        // wraps input into the ObjectNode shape the start node expects
        // (pre-truncated at maxLength=10), and wraps the output in the fixed format
        runExecuteChat(Boolean.TRUE, true, null,
                List.of(workflow("周报生成", "wf-uuid-1", 1L)),
                Map.of(1L, textStartInputConfig("var_user_input", 10)));

        SseAskParamCapture captured = captureAskParam();
        RunWorkflowTool tool = (RunWorkflowTool) captured.param.getHttpRequestParams().getBuiltinTools().stream()
                .filter(item -> item instanceof RunWorkflowTool)
                .findFirst().orElseThrow();

        Map<String, Object> blockingResult = new LinkedHashMap<>();
        blockingResult.put("task_id", "rt-1");
        blockingResult.put("status", "completed");
        blockingResult.put("outputs", textOutputs("output", "周报正文"));
        when(workflowStarter.blocking(eq(user), eq("wf-uuid-1"), any())).thenReturn(blockingResult);

        String result = tool.execute(ToolExecutionRequest.builder()
                        .id("req-1").name(RunWorkflowTool.NAME)
                        .arguments("{\"workflowTitle\":\" 周报生成 \",\"input\":\"这一段素材超过十个字符肯定被截断\"}")
                        .build(),
                captured.param.getToolContext());

        assertThat(result).isEqualTo("工作流《周报生成》执行完成，输出如下：\n周报正文");

        ArgumentCaptor<List> inputsCaptor = ArgumentCaptor.forClass(List.class);
        verify(workflowStarter).blocking(eq(user), eq("wf-uuid-1"), inputsCaptor.capture());
        List<?> inputs = inputsCaptor.getValue();
        assertThat(inputs).hasSize(1);
        ObjectNode userInput = (ObjectNode) inputs.get(0);
        assertThat(userInput.get("name").asText()).isEqualTo("var_user_input");
        assertThat(userInput.get("content").get("type").asInt()).isEqualTo(1);
        // maxLength=10：超长输入被先行硬截断（起始节点校验超长直接判非法）
        // maxLength=10: the oversized input is hard-truncated first
        assertThat(userInput.get("content").get("value").asText()).hasSize(10);
    }

    @Test
    void runWorkflowInternalTimeoutAlwaysFiresBeforeOuterGuardrail() {
        // 内部等待上限必须严格小于外层 guardrail（tool-timeout-ms）：否则外层先
        // cancel(true) 打断池线程的 join，"仍在执行中"摘要退化为工具失败。低超时
        // 配置（8s）下内部应为 3s，而不是被 10s 下限抬到外层之后
        // The internal wait cap must stay strictly below the outer guardrail
        // (tool-timeout-ms): otherwise the outer cancel(true) interrupts the pool
        // thread's join first and the "still running" summary degrades into a tool
        // failure. At a low timeout config (8s) the cap must be 3s, not lifted
        // past the outer timeout by a 10s floor
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getAgent().setToolTimeoutMs(8000);
        ReflectionTestUtils.setField(chatService, "adiProperties", properties);

        Long internalMs = ReflectionTestUtils.invokeMethod(chatService, "resolveRunWorkflowInternalTimeoutMs");

        assertThat(internalMs).isEqualTo(3000L);
    }

    /** 构造 blocking 输出 ObjectNode：{paramName: {type, title, value}} / Build the blocking outputs node */
    private static ObjectNode textOutputs(String paramName, String value) {
        ObjectNode content = JsonUtil.createObjectNode();
        content.put("type", 1);
        content.put("title", "输出");
        content.put("value", value);
        ObjectNode outputs = JsonUtil.createObjectNode();
        outputs.set(paramName, content);
        return outputs;
    }

    @Test
    void agenticToolContextCarriesMemoryIdOnlyWhenContextUnderstandingEnabled() {
        character.setUnderstandContextEnable(true);
        runExecuteChat(Boolean.TRUE, true, "mem-1");

        SseAskParamCapture captured = captureAskParam();
        assertThat(captured.param.getToolContext()).isNotNull();
        assertThat(captured.param.getToolContext().getMemoryId()).isEqualTo("mem-1");
    }

    @Test
    void completedAnswerFillsToolCallsAndLightsRefFlagFromToolEvidence() {
        runExecuteChat(Boolean.TRUE, true, null);
        SseAskParamCapture captured = captureAskParam();
        ToolContext toolContext = captured.param.getToolContext();

        // 模拟工具循环的产出：一次 search_knowledge 轨迹 + 一条知识库向量命中
        // Simulate tool-loop output: one search_knowledge trace and one KB vector hit
        toolContext.getToolTraces().add(ToolCallTrace.builder()
                .seq(0).toolName("search_knowledge").args("{\"query\":\"对比A和B\"}")
                .resultSummary("[来源: KB-A] 片段").durationMs(120L).success(true).build());
        Map<String, Double> toolHits = new HashMap<>(Map.of("kb-emb-tool", 0.91D));
        RetrieverWrapper toolWrapper = kbWrapper(toolHits);
        toolContext.getRefCollector().add(toolWrapper);

        AnswerMeta meta = AnswerMeta.builder().build();
        captured.callback.accept(new LLMResponseContent(null, "answer", null),
                new PromptMeta(1, "q-uuid"), meta);

        // 工具命中点亮 is_ref_embedding；meta 携带轨迹
        // The tool hit lights is_ref_embedding; the meta carries the trace
        assertThat(meta.getIsRefEmbedding()).isTrue();
        assertThat(meta.getToolCalls()).extracting(ToolCallTrace::getToolName, ToolCallTrace::getSeq)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("search_knowledge", 0));

        // 落库收到合并后的证据集合与同一 ToolContext
        // Persistence receives the merged evidence set and the same ToolContext
        ArgumentCaptor<List> retrieversCaptor = ArgumentCaptor.forClass(List.class);
        verify(self).saveAfterAiResponse(any(), any(), retrieversCaptor.capture(), any(), any(), eq(meta),
                any(), any(), any(), eq(toolContext));
        assertThat(retrieversCaptor.getValue()).contains(toolWrapper);
        // 回调内不直接落引用，持久化统一走 saveAfterAiResponse
        // The callback itself never persists refs; persistence goes through saveAfterAiResponse only
        verify(characterMessageService, never()).createEmbeddingRefs(any(), any(), any());
    }

    // ==================== 轨迹落库 / Trace persistence ====================

    @Test
    void saveToolCallTracesPersistsAllTracesWithMessageIdAndSeqOrder() {
        ToolContext toolContext = ToolContext.builder().toolTraces(new ArrayList<>(List.of(
                ToolCallTrace.builder().seq(0).toolName("search_knowledge")
                        .args("{\"query\":\"a\"}").resultSummary("r0").durationMs(11L).success(true).build(),
                ToolCallTrace.builder().seq(1).toolName("search_knowledge")
                        .args("{\"query\":\"b\"}").resultSummary("r1").durationMs(22L).success(false).build())))
                .build();

        ReflectionTestUtils.invokeMethod(chatService, "saveToolCallTraces", toolContext, 99L);

        ArgumentCaptor<CharacterMessageToolCall> captor = ArgumentCaptor.forClass(CharacterMessageToolCall.class);
        verify(toolCallMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CharacterMessageToolCall::getMessageId, CharacterMessageToolCall::getSeq,
                        CharacterMessageToolCall::getToolName, CharacterMessageToolCall::getArgs,
                        CharacterMessageToolCall::getResultSummary, CharacterMessageToolCall::getDurationMs,
                        CharacterMessageToolCall::getSuccess)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(99L, 0, "search_knowledge",
                                "{\"query\":\"a\"}", "r0", 11L, true),
                        org.assertj.core.groups.Tuple.tuple(99L, 1, "search_knowledge",
                                "{\"query\":\"b\"}", "r1", 22L, false));
    }

    @Test
    void saveToolCallTracesSkipsQuietlyWithoutToolContextOrTraces() {
        ReflectionTestUtils.invokeMethod(chatService, "saveToolCallTraces", (Object) null, 99L);
        ReflectionTestUtils.invokeMethod(chatService, "saveToolCallTraces",
                ToolContext.builder().toolTraces(new ArrayList<>()).build(), 99L);

        verifyNoInteractions(toolCallMapper);
    }

    // ==================== refCollector 合并去重 / refCollector merge dedup ====================

    @Test
    void mergedToolRefsPersistEachFragmentExactlyOnce() {
        // 预检索命中 kb-emb-1；工具命中 kb-emb-1 + kb-emb-2 → 只补落 kb-emb-2
        // Pre-retrieval hit kb-emb-1; the tool hit kb-emb-1 + kb-emb-2 → only kb-emb-2 is persisted again
        Map<String, Double> preflightHits = new HashMap<>(Map.of("kb-emb-1", 0.9D));
        RetrieverWrapper preflight = kbWrapper(preflightHits);
        Map<String, Double> toolHits = new HashMap<>(Map.of("kb-emb-1", 0.8D, "kb-emb-2", 0.7D));
        RetrieverWrapper firstTool = kbWrapper(toolHits);
        // 第二次工具调用重复命中 kb-emb-2 + 全新 kb-emb-3 → 只补落 kb-emb-3
        // A second tool call repeats kb-emb-2 plus fresh kb-emb-3 → only kb-emb-3 is persisted again
        Map<String, Double> secondToolHits = new HashMap<>(Map.of("kb-emb-2", 0.6D, "kb-emb-3", 0.5D));
        RetrieverWrapper secondTool = kbWrapper(secondToolHits);

        ToolContext toolContext = ToolContext.builder()
                .refCollector(new ArrayList<>(List.of(firstTool, secondTool))).build();

        List<RetrieverWrapper> merged = ReflectionTestUtils.invokeMethod(chatService,
                "mergeToolCollectedRefs", List.of(preflight), toolContext);

        assertThat(merged).containsExactly(preflight, firstTool, secondTool);
        ReflectionTestUtils.invokeMethod(chatService, "createRef", merged, user, 99L);

        verify(characterMessageService).createEmbeddingRefs(user, 99L, Map.of("kb-emb-1", 0.9D));
        verify(characterMessageService).createEmbeddingRefs(user, 99L, Map.of("kb-emb-2", 0.7D));
        verify(characterMessageService).createEmbeddingRefs(user, 99L, Map.of("kb-emb-3", 0.5D));
        verify(characterMessageService, times(3)).createEmbeddingRefs(any(), any(), any());
    }

    @Test
    void mergedToolRefsDeduplicateBm25HitsByChunkUuid() {
        List<Bm25ContentRetriever.Bm25RetrievedHit> preflightHits = new ArrayList<>(List.of(hit("chunk-1")));
        RetrieverWrapper preflight = bm25Wrapper(preflightHits);
        List<Bm25ContentRetriever.Bm25RetrievedHit> toolHits =
                new ArrayList<>(List.of(hit("chunk-1"), hit("chunk-2")));
        RetrieverWrapper toolWrapper = bm25Wrapper(toolHits);

        ToolContext toolContext = ToolContext.builder()
                .refCollector(new ArrayList<>(List.of(toolWrapper))).build();

        List<RetrieverWrapper> merged = ReflectionTestUtils.invokeMethod(chatService,
                "mergeToolCollectedRefs", List.of(preflight), toolContext);
        ReflectionTestUtils.invokeMethod(chatService, "createRef", merged, user, 99L);

        // chunk-1 只落一次（预检索那次），工具侧仅补落 chunk-2
        // chunk-1 is persisted once (by pre-retrieval); the tool side only adds chunk-2
        verify(characterMessageService, times(2)).createBm25Refs(any(), any(), any(), any());
        verify(characterMessageService).createBm25Refs(eq(user), eq(99L), any(), eq(List.of(hit("chunk-2"))));
    }

    @Test
    void mergedToolRefsKeepChannelsIsolated() {
        // 同一 embeddingId 出现在不同通道（语义记忆 vs 情景记忆）不是重复：
        // 两个通道物理隔离，各落各的
        // The same embeddingId across different channels (semantic vs episodic
        // memory) is not a duplicate: the channels are physically isolated stores
        Map<String, Double> semanticHits = new HashMap<>(Map.of("mem-emb-1", 0.9D));
        RetrieverWrapper semantic = RetrieverWrapper.builder()
                .contentFrom(ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY)
                .retriever(new DeduplicatingContentRetriever(List.of(embeddingSource(semanticHits)),
                        null, 1, false, null, new ZhiMeshProperties.Retrieval()))
                .build();
        Map<String, Double> episodicToolHits = new HashMap<>(Map.of("mem-emb-1", 0.8D));
        RetrieverWrapper episodicTool = RetrieverWrapper.builder()
                .contentFrom(ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC)
                .retriever(new DeduplicatingContentRetriever(List.of(embeddingSource(episodicToolHits)),
                        null, 1, false, null, new ZhiMeshProperties.Retrieval()))
                .build();
        ToolContext toolContext = ToolContext.builder()
                .refCollector(new ArrayList<>(List.of(episodicTool))).build();

        List<RetrieverWrapper> merged = ReflectionTestUtils.invokeMethod(chatService,
                "mergeToolCollectedRefs", List.of(semantic), toolContext);
        ReflectionTestUtils.invokeMethod(chatService, "createRef", merged, user, 99L);

        verify(characterMessageService).createMemoryRefs(user, 99L, Map.of("mem-emb-1", 0.9D),
                com.pppp.zhimesh.common.enums.MemoryType.SEMANTIC);
        verify(characterMessageService).createMemoryRefs(user, 99L, Map.of("mem-emb-1", 0.8D),
                com.pppp.zhimesh.common.enums.MemoryType.EPISODIC);
    }

    @Test
    void mergeWithoutToolContextReturnsPreflightListUnchanged() {
        List<RetrieverWrapper> preflight = new ArrayList<>();
        // invokeMethod 的泛型返回值直接喂 assertThat 会与 Predicate 重载歧义，先落到局部变量
        // Feeding invokeMethod's generic return straight into assertThat trips over
        // the Predicate overloads; land it in a local variable first
        Object mergedWithoutContext = ReflectionTestUtils.invokeMethod(chatService,
                "mergeToolCollectedRefs", preflight, (Object) null);
        assertThat(mergedWithoutContext).isSameAs(preflight);
        Object mergedWithoutRefs = ReflectionTestUtils.invokeMethod(chatService,
                "mergeToolCollectedRefs", preflight, ToolContext.builder().refCollector(new ArrayList<>()).build());
        assertThat(mergedWithoutRefs).isSameAs(preflight);
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    /**
     * 运行 executeChat：isAgentic/知识库可用性/记忆ID 三参决定分支走向（无可调用工作流）
     * Run executeChat with the branch-deciding inputs, no runnable workflows.
     */
    private void runExecuteChat(Boolean isAgentic, boolean withKb, String shortTermMemoryId) {
        runExecuteChat(isAgentic, withKb, shortTermMemoryId, List.of(), Map.of());
    }

    /**
     * 运行 executeChat：isAgentic/知识库可用性/记忆ID/可调用工作流/起始节点输入定义
     * 共同决定分支走向
     * Run executeChat with all branch-deciding inputs: isAgentic / KB
     * availability / memory id / runnable workflows / start-node input defs.
     */
    private void runExecuteChat(Boolean isAgentic, boolean withKb, String shortTermMemoryId,
                                List<Workflow> workflows, Map<Long, WfNodeInputConfig> startInputConfigs) {
        character.setIsAgentic(isAgentic);
        List<KbInfoResp> kbList = withKb ? filteredKb : new ArrayList<>();
        AskReq askReq = askReq();
        when(characterService.filterEnableKb(user, character)).thenReturn(kbList);
        when(chatContextResolver.resolve(user, askReq))
                .thenReturn(new ChatContext(user, character, null, "req-uuid", shortTermMemoryId));
        when(workflowService.listRunnableForUser(user, 100)).thenReturn(workflows);
        when(workflowNodeService.getStartNodeInputConfigs(any())).thenReturn(startInputConfigs);

        ReflectionTestUtils.invokeMethod(chatService, "executeChat", SSE_UUID, user, askReq);
    }

    /** 可调用工作流 fixture：mine 工作流实体 / Runnable-workflow fixture: an owned workflow entity */
    private Workflow workflow(String title, String uuid, long id) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setUuid(uuid);
        workflow.setTitle(title);
        workflow.setUserId(user.getId());
        workflow.setIsEnable(true);
        return workflow;
    }

    /** 起始节点输入定义：单个文本参数（name/maxLength 可指定）/ Start-node defs: one TEXT param */
    private WfNodeInputConfig textStartInputConfig(String paramName, Integer maxLength) {
        WfNodeIOText def = WfNodeIOText.builder()
                .uuid("def-uuid")
                .type(1)
                .name(paramName)
                .title(paramName)
                .required(false)
                .maxLength(maxLength)
                .build();
        WfNodeInputConfig config = new WfNodeInputConfig();
        config.setUserInputs(List.of(def));
        config.setRefInputs(new ArrayList<>());
        return config;
    }

    private AskReq askReq() {
        AskReq askReq = new AskReq();
        askReq.setCharacterUuid(character.getUuid());
        askReq.setPrompt("对比 A 库和 B 库里 X 的差异");
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

    /** 知识库向量通道 wrapper（Deduplicating 包装，贴近生产结构）/ KB vector-channel wrapper (dedup-wrapped, production-shaped) */
    private RetrieverWrapper kbWrapper(Map<String, Double> hits) {
        return RetrieverWrapper.builder()
                .contentFrom(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE)
                .retriever(new DeduplicatingContentRetriever(List.of(embeddingSource(hits)),
                        null, 1, false, null, new ZhiMeshProperties.Retrieval()))
                .build();
    }

    /** BM25 通道 wrapper / BM25-channel wrapper */
    private RetrieverWrapper bm25Wrapper(List<Bm25ContentRetriever.Bm25RetrievedHit> hits) {
        return RetrieverWrapper.builder()
                .contentFrom(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE)
                .retriever(new DeduplicatingContentRetriever(List.of(bm25Source(hits)),
                        null, 1, false, null, new ZhiMeshProperties.Retrieval()))
                .build();
    }

    /**
     * 桩一个向量源检索器：getRetrievedEmbeddingToScore 返回可变 map，
     * retainRetrievedEmbeddings 语义与真实实现一致（保留交集，空集即清空）
     * Stub a vector source retriever: a mutable hits map plus
     * retainRetrievedEmbeddings semantics identical to the real implementation
     * (retain the intersection; an empty set clears).
     */
    private static ZhiMeshEmbeddingStoreContentRetriever embeddingSource(Map<String, Double> hits) {
        ZhiMeshEmbeddingStoreContentRetriever source = mock(ZhiMeshEmbeddingStoreContentRetriever.class);
        when(source.getRetrievedEmbeddingToScore()).thenReturn(hits);
        doAnswer(invocation -> {
            Set<String> keep = invocation.getArgument(0);
            hits.keySet().retainAll(keep == null ? Set.of() : keep);
            return null;
        }).when(source).retainRetrievedEmbeddings(any());
        return source;
    }

    /** 桩一个 BM25 源检索器（retain 语义同真实实现）/ Stub a BM25 source retriever (real retain semantics) */
    private static Bm25ContentRetriever bm25Source(List<Bm25ContentRetriever.Bm25RetrievedHit> hits) {
        Bm25ContentRetriever source = mock(Bm25ContentRetriever.class);
        when(source.getRetrievedTerms()).thenReturn(List.of("term"));
        when(source.getRetrievedHits()).thenReturn(hits);
        doAnswer(invocation -> {
            Set<String> keep = invocation.getArgument(0);
            hits.removeIf(hit -> keep == null || !keep.contains(hit.chunkUuid()));
            return null;
        }).when(source).retainRetrievedHits(any());
        return source;
    }

    private static Bm25ContentRetriever.Bm25RetrievedHit hit(String chunkUuid) {
        return new Bm25ContentRetriever.Bm25RetrievedHit(chunkUuid, "kb-uuid-a", "item-1", "content", 0.9D, 1);
    }

    /** call() 捕获结果：请求参数 + 完成回调 / Captured call(): request params + completion callback */
    private record SseAskParamCapture(
            SseAskParam param,
            TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> callback) {
    }
}
