package com.pppp.zhimesh.common.memory.shortterm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

final class ShortTermMemoryMessageCodec {

    private ShortTermMemoryMessageCodec() {
    }

    static List<ChatMessage> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<ChatMessage> messages = messagesFromJson(json);
        return messages == null ? List.of() : messages;
    }

    static String normalizeToJson(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<ChatMessage> copy = new ArrayList<>(messages);
        if (!copy.isEmpty() && copy.get(0) instanceof AiMessage) {
            copy.remove(0);
        }
        if (copy.isEmpty()) {
            return null;
        }
        List<ChatMessage> normalized = new ArrayList<>();
        int index = 0;
        if (copy.get(0) instanceof SystemMessage) {
            normalized.add(copy.get(0));
            index = 1;
        }
        for (int i = index; i < copy.size(); i++) {
            ChatMessage message = copy.get(i);
            if (!(message instanceof SystemMessage)) {
                normalized.add(message);
            }
        }
        return normalized.isEmpty() ? null : messagesToJson(normalized);
    }
}
