package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.service.LLMCallRecordService;
import com.pppp.zhimesh.common.service.ModelHealthService;
import com.pppp.zhimesh.common.service.UserDayCostService;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.metrics.LLMMetrics;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流内 LLM 消耗入账验证（设计验收标准 4）：streamingInvokeLLM / invokeLLM 在
 * 记录 token 之后，必须把消耗按解析出的模型 isFree 属性计入发起用户日成本，
 * 否则经角色触发长工作流等于配额白嫖。
 * <p>
 * Cost accounting for in-workflow LLM consumption (design acceptance criterion
 * 4): after recording tokens, streamingInvokeLLM / invokeLLM must charge the
 * consumption to the initiating user's daily cost with the resolved model's
 * isFree flag, or a long workflow triggered via a character becomes a quota
 * loophole.
 */
class WorkflowUtilCostAccountingTest {

    private static final String MODEL_PLATFORM = "cost-test-platform";
    private static final String MODEL_NAME = "cost-test-model";

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private List<AbstractLLMService> previousLlmServices;

    private UserDayCostService userDayCostService;
    private ChatModel chatModel;
    private StreamingChatModel streamingModel;

    private TestLLMService llmService;
    private AiModel aiModel;

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
        when(context.getBean(SseManager.class)).thenReturn(new SseManager());
        // 模型路由健康：注册表里的测试服务必须被判定为可用
        // Model routing must consider the registered test service healthy
        ModelHealthService healthService = mock(ModelHealthService.class);
        when(healthService.isHealthy(anyString(), anyString())).thenReturn(true);
        when(context.getBean(ModelHealthService.class)).thenReturn(healthService);
        userDayCostService = mock(UserDayCostService.class);
        when(context.getBean(UserDayCostService.class)).thenReturn(userDayCostService);
        when(context.getBean(LLMCallRecordService.class)).thenReturn(mock(LLMCallRecordService.class));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(context.getBean(StringRedisTemplate.class)).thenReturn(redisTemplate);

        chatModel = mock(ChatModel.class);
        streamingModel = mock(StreamingChatModel.class);
        aiModel = new AiModel();
        aiModel.setId(9L);
        aiModel.setName(MODEL_NAME);
        aiModel.setPlatform(MODEL_PLATFORM);
        aiModel.setIsEnable(true);
        aiModel.setMaxInputTokens(8192);
        ModelPlatform platform = new ModelPlatform();
        platform.setName(MODEL_PLATFORM);
        llmService = new TestLLMService(aiModel, platform);
        llmService.useRedisTemplate(redisTemplate);

        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
        // 静态 LLM 注册表：挂入测试服务，结束后还原快照
        // Static LLM registry: register the test service, restore the snapshot afterwards
        previousLlmServices = List.copyOf(LLMContext.getAllServices());
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
        LLMContext.addLLMService(llmService);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
        ReflectionTestUtils.setField(LLMContext.class, "LLM_SERVICES", previousLlmServices);
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
    }

    @Test
    void blockingWorkflowLlmCostIsAppendedToInitiatingUserWithFreeFlag() {
        aiModel.setIsFree(true);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                chatResponse("节点回答", new TokenUsage(300, 100)));

        User user = new User();
        user.setId(7L);
        user.setName("wf-user");
        WfState wfState = new WfState(user, List.of(), "wf-uuid-cost-blocking");
        WfNodeState nodeState = new WfNodeState();
        nodeState.setMetrics(new LLMMetrics());

        NodeIOData output = WorkflowUtil.invokeLLM(wfState, nodeState, MODEL_PLATFORM, MODEL_NAME, "总结以下内容");

        assertThat(output).isNotNull();
        // 消耗按 totalTokens（300+100=400）与解析后模型的 isFree=true 入账
        // The consumption is charged as totalTokens (300+100=400) with the
        // resolved model's isFree=true
        verify(userDayCostService).appendCostToUser(user, 400, true);
    }

    @Test
    void blockingWorkflowLlmCostIsAppendedToInitiatingUserWithPaidFlag() {
        aiModel.setIsFree(false);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                chatResponse("付费模型回答", new TokenUsage(50, 25)));

        User user = new User();
        user.setId(8L);
        user.setName("wf-user-paid");
        WfState wfState = new WfState(user, List.of(), "wf-uuid-cost-paid");
        WfNodeState nodeState = new WfNodeState();
        nodeState.setMetrics(new LLMMetrics());

        WorkflowUtil.invokeLLM(wfState, nodeState, MODEL_PLATFORM, MODEL_NAME, "总结以下内容");

        // 收费模型入付费账本（isFree=false）
        // A paid model charges the paid ledger (isFree=false)
        verify(userDayCostService).appendCostToUser(user, 75, false);
    }

    @Test
    void streamingWorkflowLlmCostIsAppendedToInitiatingUser() {
        aiModel.setIsFree(true);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(chatResponse("流式节点回答", new TokenUsage(210, 90)));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        User user = new User();
        user.setId(9L);
        user.setName("wf-user-stream");
        WfState wfState = new WfState(user, List.of(), "wf-uuid-cost-stream");
        WfNodeState nodeState = new WfNodeState();
        nodeState.setMetrics(new LLMMetrics());
        WorkflowNode node = new WorkflowNode();
        node.setId(11L);
        node.setUuid("node-uuid-cost-stream");
        // streamingInvokeLLM 完成回调会把输出写进已完成节点状态：构造最小挂载
        // The streaming completion callback writes outputs into the completed
        // node state: mount a minimal one
        AbstractWfNode completed = mock(AbstractWfNode.class);
        when(completed.getNode()).thenReturn(node);
        when(completed.getState()).thenReturn(nodeState);
        wfState.getCompletedNodes().add(completed);

        WorkflowUtil.streamingInvokeLLM(wfState, nodeState, node, MODEL_PLATFORM, MODEL_NAME,
                new ArrayList<>(List.of(UserMessage.from("生成周报"))));

        // 流式路径同样入账：totalTokens=300、isFree=true
        // The streaming path charges as well: totalTokens=300, isFree=true
        verify(userDayCostService).appendCostToUser(user, 300, true);
        // 节点指标照常记录（入账在指标记录之后，不互相影响）
        // Node metrics are still recorded (charging happens after and does not interfere)
        LLMMetrics metrics = (LLMMetrics) nodeState.getMetrics();
        assertThat(metrics.getInputTokens()).isEqualTo(210);
        assertThat(metrics.getOutputTokens()).isEqualTo(90);
    }

    private static ChatResponse chatResponse(String text, TokenUsage tokenUsage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(text))
                .metadata(ChatResponseMetadata.builder().tokenUsage(tokenUsage).build())
                .build();
    }

    /**
     * 最小可实例化的 AbstractLLMService 测试子类：阻塞/流式模型均可注入 mock
     * Minimal instantiable AbstractLLMService test subclass with injectable
     * blocking/streaming model mocks
     */
    private final class TestLLMService extends AbstractLLMService {
        private TestLLMService(AiModel aiModel, ModelPlatform platform) {
            super(aiModel, platform);
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
            return chatModel;
        }

        @Override
        public StreamingChatModel buildStreamingChatModel(ChatModelBuilderProperties properties) {
            return streamingModel;
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
