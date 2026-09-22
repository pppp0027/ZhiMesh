package com.pppp.zhimesh.common.helper;

import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.vo.SseAskParam;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class SseManagerTest {

    @Test
    void workflowCanStartAfterClientConnectionAlreadyClosed() {
        SseManager manager = new SseManager();
        User user = new User();
        user.setId(7L);

        manager.startSse(user, "already-closed", "payload");

        assertThat(manager.isCompleted("already-closed")).isTrue();
    }

    @Test
    void callUsesPreResolvedLlmService() {
        SseManager manager = spy(new SseManager());
        AbstractLLMService llmService = mock(AbstractLLMService.class);
        SseAskParam askParam = new SseAskParam();

        doNothing().when(manager).registerEventStreamListener(askParam);

        manager.call(llmService, askParam, (response, promptMeta, answerMeta) -> {
        });

        verify(llmService).streamingChat(any(SseAskParam.class), any());
    }

    @Test
    void callResetsStaleTokenAccumulationBeforeStreaming() {
        // 重新生成复用同一 questionUuid：若不先清 token:usage:{uuid}，上次尝试的
        // 中间轮会与新尝试累加，calculateToken 读回后按旧+新双重计费
        // <p>
        // Regenerate reuses the same questionUuid: without clearing
        // token:usage:{uuid} first, the previous attempt's intermediate rounds
        // accumulate with the new attempt and calculateToken double-bills both
        SseManager manager = spy(new SseManager());
        AbstractLLMService llmService = mock(AbstractLLMService.class);
        SseAskParam askParam = new SseAskParam();
        askParam.setUuid("reused-question-uuid");

        doNothing().when(manager).registerEventStreamListener(askParam);

        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", redisTemplate);

        manager.call(llmService, askParam, (response, promptMeta, answerMeta) -> {
        });

        verify(redisTemplate).delete("token:usage:reused-question-uuid");
    }

    @Test
    void streamErrorCompletesAndUnregistersEmitter() throws IOException {
        SseManager manager = new SseManager();
        SseEmitter emitter = mock(SseEmitter.class);
        manager.register("sse-1", emitter);

        manager.handleStreamError(new IOException("upstream failed"), "sse-1");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertThat(manager.isCompleted("sse-1")).isTrue();
    }

    @Test
    void streamErrorStillCompletesWhenErrorEventCannotBeSent() throws IOException {
        SseManager manager = new SseManager();
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("client disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        manager.register("sse-2", emitter);

        manager.handleStreamError(new IOException("upstream failed"), "sse-2");

        verify(emitter).complete();
        assertThat(manager.isCompleted("sse-2")).isTrue();
    }

    @Test
    void unregisterReleasesBoundShortMemoryLease() {
        SseManager manager = new SseManager();
        ShortTermMemoryTurnCoordinator coordinator =
                mock(ShortTermMemoryTurnCoordinator.class);
        ReflectionTestUtils.setField(manager, "shortTermMemoryTurnCoordinator", coordinator);
        manager.register("sse-3", mock(SseEmitter.class));

        manager.unregister("sse-3");

        verify(coordinator).releaseRequest("sse-3");
        assertThat(manager.isCompleted("sse-3")).isTrue();
    }

    @Test
    void listenerRegistrationFailsFastAfterClientDisconnects() {
        SseManager manager = new SseManager();
        SseAskParam askParam = new SseAskParam();
        askParam.setSseUuid("missing-sse");

        assertThatThrownBy(() -> manager.registerEventStreamListener(askParam))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
    }

    // ==================== sendToolCall ====================

    @Test
    void sendToolCallWithNullUuidReturnsQuietlyInsteadOfThrowingNpe() {
        // blocking 路径 uuid 为 null：入口直接返回，不触碰 entries（get(null) 会 NPE）
        // Blocking path passes a null uuid: return at the entry instead of
        // touching entries (get(null) would throw an NPE)
        SseManager.sendToolCall(null, "search_knowledge", 12L, true);
        SseManager.sendToolCall(null, "search_knowledge", 12L, true, "{\"query\":\"x\"}", "hit");
        // 无异常即通过 / Passing without an exception is the assertion
    }

    @Test
    void sendToolCallWithUnregisteredUuidReturnsQuietly() {
        // 空 entries 的 SseManager：未注册 uuid 在 getEntry 处短路，不触碰 emitter
        // An empty-entries SseManager: an unregistered uuid short-circuits at
        // getEntry without touching any emitter
        org.springframework.context.ApplicationContext context = mock(org.springframework.context.ApplicationContext.class);
        org.mockito.Mockito.when(context.getBean(SseManager.class)).thenReturn(new SseManager());
        Object previous = ReflectionTestUtils.getField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext", context);
        try {
            SseManager.sendToolCall("never-registered", "search_knowledge", 5L, false,
                    "{\"query\":\"x\"}", "miss");
        } finally {
            ReflectionTestUtils.setField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext", previous);
        }
    }

    @Test
    void toolCallPayloadKeepsLegacyFieldsAndOmitsNullNewFields() {
        Map<String, Object> payload = SseManager.buildToolCallPayload("weather", 42L, true, null, null);

        // 旧三字段名与语义不变（向后兼容红线）/ Legacy three fields unchanged (compat red line)
        assertThat(payload).containsOnlyKeys("toolName", "durationMs", "success");
        assertThat(payload.get("toolName")).isEqualTo("weather");
        assertThat(payload.get("durationMs")).isEqualTo(42L);
        assertThat(payload.get("success")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void toolCallPayloadAppendsNullableNewFieldsWhenPresent() {
        Map<String, Object> payload = SseManager.buildToolCallPayload(
                "search_knowledge", 7L, false, "{\"query\":\"a\"}", "未检索到相关内容");

        assertThat(payload).containsOnlyKeys("toolName", "durationMs", "success", "args", "resultSummary");
        assertThat(payload.get("args")).isEqualTo("{\"query\":\"a\"}");
        assertThat(payload.get("resultSummary")).isEqualTo("未检索到相关内容");
    }

    @Test
    void toolCallPayloadTruncatesResultSummaryToKeepSseSmall() {
        Map<String, Object> payload = SseManager.buildToolCallPayload(
                "search_knowledge", 7L, true, null, "x".repeat(500));

        assertThat((String) payload.get("resultSummary")).hasSize(200);
    }

    @Test
    void calculateTokenReportsAccumulatedUsageAcrossToolLoopRounds() {
        // 工具循环中间轮（100/20）已缓存进 List，最终轮（150/30）由 calculateToken 追加后：
        // meta 应报累计值（250/50）而非仅最终轮——计费（calcTodayCost）读的就是这两个 meta
        // <p>
        // Intermediate tool-loop rounds (100/20) already cached in the List; after
        // calculateToken appends the final round (150/30), the metas must report the
        // accumulated totals (250/50) instead of the final round alone — calcTodayCost
        // bills exactly these meta numbers
        org.springframework.context.ApplicationContext context = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ListOperations<String, String> listOperations =
                mock(org.springframework.data.redis.core.ListOperations.class);
        org.mockito.Mockito.when(redisTemplate.opsForList()).thenReturn(listOperations);
        org.mockito.Mockito.when(listOperations.range(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(-1L)))
                .thenReturn(java.util.List.of("100", "20", "150", "30"));
        org.mockito.Mockito.when(context.getBean(org.mockito.ArgumentMatchers.eq(org.springframework.data.redis.core.StringRedisTemplate.class)))
                .thenReturn(redisTemplate);
        Object previous = ReflectionTestUtils.getField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext", context);
        try {
            dev.langchain4j.model.chat.response.ChatResponse response =
                    dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                    .tokenUsage(new dev.langchain4j.model.output.TokenUsage(150, 30))
                                    .build())
                            .aiMessage(dev.langchain4j.data.message.AiMessage.builder().text("ok").build())
                            .build();

            org.apache.commons.lang3.tuple.Pair<com.pppp.zhimesh.common.vo.PromptMeta, com.pppp.zhimesh.common.vo.AnswerMeta> pair =
                    SseManager.calculateToken(response, "acc-test-uuid");

            assertThat(pair.getLeft().getInputTokens()).isEqualTo(250);
            assertThat(pair.getRight().getInputTokens()).isEqualTo(250);
            assertThat(pair.getRight().getOutputTokens()).isEqualTo(50);
        } finally {
            ReflectionTestUtils.setField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext", previous);
        }
    }

    @Test
    void calculateTokenFallsBackToFinalRoundWhenCacheUnavailable() {
        // Redis 读回抛异常：回退最终轮数值，不因计费读取失败而中断回答链路
        // Redis read-back throws: fall back to the final-round numbers instead of
        // breaking the answer pipeline on a billing read failure
        org.springframework.context.ApplicationContext context = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ListOperations<String, String> listOperations =
                mock(org.springframework.data.redis.core.ListOperations.class);
        org.mockito.Mockito.when(redisTemplate.opsForList()).thenReturn(listOperations);
        org.mockito.Mockito.when(listOperations.range(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(-1L)))
                .thenThrow(new RuntimeException("redis down"));
        org.mockito.Mockito.when(context.getBean(org.mockito.ArgumentMatchers.eq(org.springframework.data.redis.core.StringRedisTemplate.class)))
                .thenReturn(redisTemplate);
        Object previous = ReflectionTestUtils.getField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext", context);
        try {
            dev.langchain4j.model.chat.response.ChatResponse response =
                    dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                    .tokenUsage(new dev.langchain4j.model.output.TokenUsage(150, 30))
                                    .build())
                            .aiMessage(dev.langchain4j.data.message.AiMessage.builder().text("ok").build())
                            .build();

            org.apache.commons.lang3.tuple.Pair<com.pppp.zhimesh.common.vo.PromptMeta, com.pppp.zhimesh.common.vo.AnswerMeta> pair =
                    SseManager.calculateToken(response, "fallback-test-uuid");

            assertThat(pair.getLeft().getInputTokens()).isEqualTo(150);
            assertThat(pair.getRight().getInputTokens()).isEqualTo(150);
            assertThat(pair.getRight().getOutputTokens()).isEqualTo(30);
        } finally {
            ReflectionTestUtils.setField(com.pppp.zhimesh.common.util.SpringUtil.class, "applicationContext", previous);
        }
    }

    @Test
    void toolCallPayloadNormalizesNullToolName() {
        Map<String, Object> payload = SseManager.buildToolCallPayload(null, 1L, true, null, null);

        assertThat(payload.get("toolName")).isEqualTo("unknown");
    }
}
