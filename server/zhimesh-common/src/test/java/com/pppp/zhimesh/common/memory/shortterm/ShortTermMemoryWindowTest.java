package com.pppp.zhimesh.common.memory.shortterm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryWindowTest {

    private final ChatMemoryStore store = new InMemoryStore();
    private final TokenCountEstimator estimator = new OneTokenPerMessageEstimator();

    @Test
    void appendingAiResponseTrimsImmediatelyAndKeepsSystemFirst() {
        String memoryId = "conversation:test";
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, SystemMessage.from("system"));
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, UserMessage.from("q1"));
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, AiMessage.from("a1"));
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, UserMessage.from("q2"));

        List<ChatMessage> afterQuestion = store.getMessages(memoryId);
        assertThat(afterQuestion).hasSize(3);
        assertThat(afterQuestion.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(afterQuestion.get(1)).isInstanceOf(AiMessage.class);
        assertThat(afterQuestion.get(2)).isInstanceOf(UserMessage.class);

        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, AiMessage.from("a2"));

        List<ChatMessage> afterAnswer = store.getMessages(memoryId);
        assertThat(afterAnswer).hasSize(3);
        assertThat(afterAnswer.get(0)).isEqualTo(SystemMessage.from("system"));
        assertThat(afterAnswer.get(1)).isEqualTo(UserMessage.from("q2"));
        assertThat(afterAnswer.get(2)).isEqualTo(AiMessage.from("a2"));
    }

    @Test
    void replacingSystemMessageKeepsReplacementAtTheFront() {
        String memoryId = "conversation:system-update";
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, SystemMessage.from("old"));
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, UserMessage.from("q1"));
        ShortTermMemoryWindow.append(store, memoryId, 3, estimator, SystemMessage.from("new"));

        assertThat(store.getMessages(memoryId).get(0)).isEqualTo(SystemMessage.from("new"));
    }

    @Test
    void keepsAugmentedPromptInRequestButPersistsOnlyRawUserMessage() {
        String memoryId = "conversation:raw-user-message";
        TokenWindowChatMemory memory = ShortTermMemoryWindow.open(store, memoryId, 10, estimator);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("previous question"));
        memory.add(AiMessage.from("previous answer"));
        memory.add(UserMessage.from("question plus request-scoped retrieval evidence"));
        List<ChatMessage> requestMessages = List.copyOf(memory.messages());

        ShortTermMemoryWindow.persistRawUserMessage(
                store, memoryId, requestMessages, UserMessage.from("question"));

        assertThat(requestMessages.get(requestMessages.size() - 1))
                .isEqualTo(UserMessage.from("question plus request-scoped retrieval evidence"));
        assertThat(store.getMessages(memoryId))
                .containsExactly(
                        SystemMessage.from("system"),
                        UserMessage.from("previous question"),
                        AiMessage.from("previous answer"),
                        UserMessage.from("question"));

        ShortTermMemoryWindow.append(store, memoryId, 10, estimator, AiMessage.from("current answer"));
        assertThat(store.getMessages(memoryId))
                .containsExactly(
                        SystemMessage.from("system"),
                        UserMessage.from("previous question"),
                        AiMessage.from("previous answer"),
                        UserMessage.from("question"),
                        AiMessage.from("current answer"));
    }

    private static class InMemoryStore implements ChatMemoryStore {
        private final Map<Object, List<ChatMessage>> messages = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(messages.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> newMessages) {
            messages.put(memoryId, new ArrayList<>(newMessages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messages.remove(memoryId);
        }
    }

    private static class OneTokenPerMessageEstimator implements TokenCountEstimator {
        @Override
        public int estimateTokenCountInText(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return 1;
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int count = 0;
            for (ChatMessage ignored : messages) {
                count++;
            }
            return count;
        }
    }
}
