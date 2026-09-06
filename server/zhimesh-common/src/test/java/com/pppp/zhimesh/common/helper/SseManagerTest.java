package com.pppp.zhimesh.common.helper;

import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.vo.SseAskParam;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

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
}
