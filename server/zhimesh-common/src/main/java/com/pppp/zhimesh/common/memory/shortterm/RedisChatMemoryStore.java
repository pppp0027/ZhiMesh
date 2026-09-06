package com.pppp.zhimesh.common.memory.shortterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Redis-backed LangChain4j chat-memory store.
 *
 * <p>The value deliberately remains LangChain4j's JSON representation so tool,
 * thinking and multimodal message variants survive a round trip.</p>
 */
@Slf4j
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    static final String PREFIX = "zhimesh:short-memory:v1:";
    private static final String CONVERSATION_PREFIX = "conversation:";

    private final StringRedisTemplate redisTemplate;
    private final ZhiMeshProperties properties;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, ZhiMeshProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = key(memoryId);
        String json = redisTemplate.opsForValue().get(key);
        try {
            return ShortTermMemoryMessageCodec.fromJson(json);
        } catch (RuntimeException exception) {
            log.error("Invalid Redis short-memory JSON, key:{}", key, exception);
            throw new IllegalStateException("Short-term memory data is invalid", exception);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        write(key(memoryId), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    String key(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId must not be null");
        }
        String raw = memoryId.toString();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        if (raw.startsWith(CONVERSATION_PREFIX)) {
            return PREFIX + "conversation:" + encode(raw.substring(CONVERSATION_PREFIX.length()));
        }
        return PREFIX + "memory:" + encode(raw);
    }

    private void write(String key, List<ChatMessage> messages) {
        String json = checkedJson(messages);
        if (json == null) {
            return;
        }
        writeJson(key, json);
    }

    private String checkedJson(List<ChatMessage> messages) {
        String json = ShortTermMemoryMessageCodec.normalizeToJson(messages);
        if (json == null) {
            return null;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        int limit = properties.getConversation().getShortMemoryMaxValueBytes();
        if (limit > 0 && bytes > limit) {
            throw new IllegalStateException("Short-term memory exceeds configured byte limit");
        }
        return json;
    }

    private void writeJson(String key, String json) {
        Duration ttl = ttl();
        if (ttl.isZero()) {
            redisTemplate.opsForValue().set(key, json);
        } else {
            redisTemplate.opsForValue().set(key, json, ttl);
        }
    }

    private Duration ttl() {
        Duration configured = properties.getConversation().getShortMemoryTtl();
        return configured == null || configured.isNegative() || configured.isZero()
                ? Duration.ZERO : configured;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
