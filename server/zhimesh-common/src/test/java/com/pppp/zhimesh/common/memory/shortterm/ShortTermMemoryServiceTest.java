package com.pppp.zhimesh.common.memory.shortterm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermMemoryServiceTest {

    private RedisChatMemoryStore redisStore;
    private ShortTermMemoryService service;
    private List<ChatMessage> messages;

    @BeforeEach
    void setUp() {
        redisStore = mock(RedisChatMemoryStore.class);
        service = new ShortTermMemoryService(redisStore);
        messages = List.of(UserMessage.from("question"));
    }

    @Test
    void delegatesReadsToRedis() {
        when(redisStore.getMessages("memory-1")).thenReturn(messages);

        assertThat(service.getMessages("memory-1")).isEqualTo(messages);
        verify(redisStore).getMessages("memory-1");
    }

    @Test
    void delegatesWritesToRedis() {
        service.updateMessages("memory-1", messages);

        verify(redisStore).updateMessages("memory-1", messages);
    }

    @Test
    void delegatesDeletesToRedis() {
        service.deleteMessages("memory-1");

        verify(redisStore).deleteMessages("memory-1");
    }
}
