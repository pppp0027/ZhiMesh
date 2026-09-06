package com.pppp.zhimesh.common.memory.shortterm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryMessageCodecTest {

    @Test
    void preservesThinkingAndOnlyKeepsFirstSystemMessage() {
        AiMessage answer = AiMessage.builder().text("answer").thinking("reasoning").build();

        String json = ShortTermMemoryMessageCodec.normalizeToJson(List.of(
                SystemMessage.from("system-1"),
                UserMessage.from("question"),
                SystemMessage.from("system-2"),
                answer));

        List<ChatMessage> restored = ShortTermMemoryMessageCodec.fromJson(json);
        assertThat(restored).hasSize(3);
        assertThat(((SystemMessage) restored.get(0)).text()).isEqualTo("system-1");
        assertThat(((UserMessage) restored.get(1)).singleText()).isEqualTo("question");
        assertThat(((AiMessage) restored.get(2)).thinking()).isEqualTo("reasoning");
    }

    @Test
    void removesLeadingAiMessageForCompatibility() {
        String json = ShortTermMemoryMessageCodec.normalizeToJson(List.of(
                AiMessage.from("orphan"),
                UserMessage.from("question")));

        assertThat(ShortTermMemoryMessageCodec.fromJson(json))
                .containsExactly(UserMessage.from("question"));
    }

    @Test
    void missingJsonReturnsEmptyList() {
        assertThat(ShortTermMemoryMessageCodec.fromJson(null)).isEmpty();
        assertThat(ShortTermMemoryMessageCodec.fromJson(" ")).isEmpty();
    }
}
