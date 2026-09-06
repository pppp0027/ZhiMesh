package com.pppp.zhimesh.common.memory.shortterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisChatMemoryStoreTest {

    private StringRedisTemplate template;
    private ValueOperations<String, String> values;
    private ZhiMeshProperties properties;
    private RedisChatMemoryStore store;
    private final AtomicReference<String> value = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        properties = new ZhiMeshProperties();
        when(template.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            value.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString());
        when(values.get(anyString())).thenAnswer(invocation -> value.get());
        store = new RedisChatMemoryStore(template, properties);
    }

    @Test
    void roundTripsLangChainMessages() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("question"),
                AiMessage.builder().text("answer").thinking("thinking").build());

        store.updateMessages("conversation:c-1", messages);

        assertThat(store.getMessages("conversation:c-1")).hasSize(2);
        assertThat(((AiMessage) store.getMessages("conversation:c-1").get(1)).thinking())
                .isEqualTo("thinking");
    }

    @Test
    void separatesConversationAndGenericNamespaces() {
        assertThat(store.key("conversation:same"))
                .startsWith(RedisChatMemoryStore.PREFIX + "conversation:");
        assertThat(store.key("same"))
                .startsWith(RedisChatMemoryStore.PREFIX + "memory:");
        assertThat(store.key("conversation:same")).isNotEqualTo(store.key("same"));
    }

    @Test
    void rejectsValuesOverConfiguredLimit() {
        properties.getConversation().setShortMemoryMaxValueBytes(10);

        assertThatThrownBy(() ->
                store.updateMessages("conversation:c-1",
                        List.of(UserMessage.from("a message longer than ten bytes"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("byte limit");
    }

    @Test
    void usesSlidingTtlWhenConfigured() {
        properties.getConversation().setShortMemoryTtl(Duration.ofMinutes(5));
        doAnswer(invocation -> {
            value.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));

        store.updateMessages("conversation:c-1", List.of(UserMessage.from("question")));

        assertThat(value.get()).isNotBlank();
    }

    @Test
    void rejectsCorruptedRedisJsonInsteadOfTreatingItAsEmptyMemory() {
        value.set("not-json");

        assertThatThrownBy(() -> store.getMessages("conversation:c-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data is invalid");
    }

}
