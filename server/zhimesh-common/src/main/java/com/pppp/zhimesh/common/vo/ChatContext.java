package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.User;

public record ChatContext(
        User user,
        Character character,
        Conversation conversation,
        String requestUuid,
        String shortTermMemoryId) {
}
