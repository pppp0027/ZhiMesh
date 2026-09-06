package com.pppp.zhimesh.common.memory.shortterm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates and updates token-bounded short-term chat memory consistently.
 */
public final class ShortTermMemoryWindow {

    private ShortTermMemoryWindow() {
    }

    public static TokenWindowChatMemory open(ChatMemoryStore store, Object memoryId,
                                             int maxTokens, TokenCountEstimator tokenCountEstimator) {
        return TokenWindowChatMemory.builder()
                .chatMemoryStore(store)
                .id(memoryId)
                .maxTokens(Math.max(1, maxTokens), tokenCountEstimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    public static List<ChatMessage> append(ChatMemoryStore store, Object memoryId,
                                           int maxTokens, TokenCountEstimator tokenCountEstimator,
                                           ChatMessage message) {
        TokenWindowChatMemory memory = open(store, memoryId, maxTokens, tokenCountEstimator);
        memory.add(message);
        return memory.messages();
    }

    /**
     * Replaces the current request's augmented user message in persistent memory
     * with the raw user text while leaving the already-created model snapshot intact.
     */
    public static List<ChatMessage> persistRawUserMessage(ChatMemoryStore store, Object memoryId,
                                                          List<ChatMessage> requestMessages,
                                                          UserMessage rawUserMessage) {
        if (requestMessages == null || requestMessages.isEmpty()
                || !(requestMessages.get(requestMessages.size() - 1) instanceof UserMessage)) {
            throw new IllegalStateException("Short-term request must end with a user message");
        }
        List<ChatMessage> persistedMessages = new ArrayList<>(requestMessages);
        int lastIndex = persistedMessages.size() - 1;
        if (!persistedMessages.get(lastIndex).equals(rawUserMessage)) {
            persistedMessages.set(lastIndex, rawUserMessage);
            store.updateMessages(memoryId, persistedMessages);
        }
        return List.copyOf(persistedMessages);
    }
}
