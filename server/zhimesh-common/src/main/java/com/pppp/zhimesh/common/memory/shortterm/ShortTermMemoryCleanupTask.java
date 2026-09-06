package com.pppp.zhimesh.common.memory.shortterm;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class ShortTermMemoryCleanupTask {

    @Resource
    private ShortTermMemoryKeyResolver keyResolver;
    @Resource
    private ShortTermMemoryService shortTermMemoryService;

    public void deleteConversation(String conversationUuid) {
        shortTermMemoryService.deleteMessages(keyResolver.forConversation(conversationUuid));
    }

    public void deleteCharacter(String characterUuid, Collection<String> conversationUuids) {
        conversationUuids.forEach(uuid ->
                shortTermMemoryService.deleteMessages(keyResolver.forConversation(uuid)));
    }
}
