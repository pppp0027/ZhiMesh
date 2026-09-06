package com.pppp.zhimesh.common.memory.shortterm;

import org.springframework.stereotype.Component;

@Component
public class ShortTermMemoryKeyResolver {

    private static final String CONVERSATION_PREFIX = "conversation:";

    public String forConversation(String conversationUuid) {
        return CONVERSATION_PREFIX + conversationUuid;
    }
}
