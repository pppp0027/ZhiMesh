package com.pppp.zhimesh.common.memory.shortterm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis-only facade used by all stateful chat paths.
 */
@Service
public class ShortTermMemoryService implements ChatMemoryStore {

    private final RedisChatMemoryStore redisStore;

    public ShortTermMemoryService(RedisChatMemoryStore redisStore) {
        this.redisStore = redisStore;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return redisStore.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        redisStore.updateMessages(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisStore.deleteMessages(memoryId);
    }
}
