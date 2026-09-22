package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
import com.pppp.zhimesh.common.vo.SseAskParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeRagTokenResetTest {

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private StringRedisTemplate redisTemplate;
    private List<com.pppp.zhimesh.common.languagemodel.AbstractLLMService> previousLlmServices;

    @BeforeEach
    void setUp() {
        context = mock(ApplicationContext.class);
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);

        // BaseException 构造时经 SpringUtil.getMessage 解析 i18n：装一个最小 MessageSource
        // BaseException resolves i18n through SpringUtil.getMessage: install a minimal MessageSource
        MessageSource messageSource = mock(MessageSource.class);
        when(context.getBean(MessageSource.class)).thenReturn(messageSource);
        when(messageSource.getMessage(anyString(), isNull(), any(Locale.class))).thenReturn("stub-message");

        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(context.getBean(StringRedisTemplate.class)).thenReturn(redisTemplate);

        // 真实 SseManager + mock emitter：registerEventStreamListener 与错误收尾都走真实分支
        // A real SseManager with a mock emitter: both registerEventStreamListener and the
        // error-completion path run their real branches
        SseManager sseManager = new SseManager();
        ReflectionTestUtils.setField(sseManager, "stringRedisTemplate", redisTemplate);
        sseManager.register("sse-rag-1", mock(SseEmitter.class));
        when(context.getBean(SseManager.class)).thenReturn(sseManager);

        AsyncTaskExecutor completionExecutor = mock(AsyncTaskExecutor.class);
        when(context.getBean("chatExecutor", AsyncTaskExecutor.class)).thenReturn(completionExecutor);

        // memoryId 为空的 acquire 返回 noop lease，不触碰 Redis
        // acquire with a blank memoryId returns a noop lease without touching Redis
        when(context.getBean(ShortTermMemoryTurnCoordinator.class)).thenReturn(
                new ShortTermMemoryTurnCoordinator(redisTemplate, new ZhiMeshProperties(),
                        mock(ScheduledExecutorService.class)));

        // 隔离静态 LLM 注册表：query() 在模型解析处抛 BaseException 进入错误收尾，
        // 与本用例断言的 reset 行为互不干扰
        // Isolate the static LLM registry: query() throws a BaseException at model
        // resolution and lands in the error-completion path, orthogonal to the
        // reset behavior under assertion here
        previousLlmServices = List.copyOf(LLMContext.getAllServices());
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
        ReflectionTestUtils.setField(LLMContext.class, "LLM_SERVICES", new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
        ReflectionTestUtils.setField(LLMContext.class, "LLM_SERVICES", previousLlmServices);
        ReflectionTestUtils.setField(LLMContext.class, "healthService", null);
    }

    @Test
    void ragChatClearsStaleTokenAccumulationBeforeStreaming() {
        // 知识库 QA 重新生成复用 qaRecord 的 uuid（KnowledgeBaseService 里
        // sseAskParam.setUuid(qaRecord.getUuid())），检索路径不经过 SseManager.call：
        // 若 ragChat 不先清 token:usage:{uuid}，calculateToken 读回时会把上次尝试的
        // 记录与本次累加，updateQaRecord 按双重数字计费
        // <p>
        // KB QA regeneration reuses the qaRecord uuid (sseAskParam.setUuid(
        // qaRecord.getUuid()) in KnowledgeBaseService), and the retrieval path never
        // goes through SseManager.call: unless ragChat clears token:usage:{uuid}
        // first, calculateToken reads the previous attempt's records back together
        // with the current one and updateQaRecord bills the doubled numbers
        SseAskParam askParam = new SseAskParam();
        askParam.setUuid("qa-reused-uuid");
        askParam.setSseUuid("sse-rag-1");
        User user = new User();
        user.setId(7L);
        askParam.setUser(user);
        askParam.setHttpRequestParams(ChatModelRequest.builder().build());

        new CompositeRag("rag-token-reset-test")
                .ragChat(List.of(), askParam, (response, promptMeta, answerMeta) -> {
                });

        verify(redisTemplate).delete("token:usage:qa-reused-uuid");
    }
}
