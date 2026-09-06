package com.pppp.zhimesh.common.rag.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Redis cache with bounded online wait and a fail-open circuit breaker. */
@Slf4j
@Component
public class KnowledgeRouteProfileCache {

    private static final String KEY_PREFIX = "zhimesh:kb-route-profile:v1:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZhiMeshProperties properties;
    private final AsyncTaskExecutor readExecutor;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilMs = new AtomicLong();

    public KnowledgeRouteProfileCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      ZhiMeshProperties properties,
                                      @Qualifier("ragRetrievalExecutor") AsyncTaskExecutor readExecutor) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.readExecutor = readExecutor;
    }

    public Map<String, KnowledgeRouteProfileBundle> getAll(Map<String, Long> generationsByKb) {
        if (generationsByKb == null || generationsByKb.isEmpty()) return Map.of();
        long now = System.currentTimeMillis();
        if (circuitOpenUntilMs.get() > now) return Map.of();

        List<Map.Entry<String, Long>> entries = generationsByKb.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                .toList();
        if (entries.isEmpty()) return Map.of();
        List<String> keys = entries.stream().map(entry -> key(entry.getKey(), entry.getValue())).toList();
        CompletableFuture<List<String>> future = null;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> redis.opsForValue().multiGet(keys), readExecutor);
            long timeoutMs = Math.max(1L, properties.getKnowledgeScopeGate().getRedisTimeoutMs());
            List<String> values = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            consecutiveFailures.set(0);
            if (values == null) return Map.of();
            Map<String, KnowledgeRouteProfileBundle> result = new HashMap<>();
            for (int i = 0; i < Math.min(entries.size(), values.size()); i++) {
                String json = values.get(i);
                if (json == null || json.isBlank()) continue;
                try {
                    KnowledgeRouteProfileBundle bundle = objectMapper.readValue(json, KnowledgeRouteProfileBundle.class);
                    result.put(entries.get(i).getKey(), bundle);
                } catch (JsonProcessingException exception) {
                    log.warn("Ignoring malformed route-profile cache payload, kbUuid:{}",
                            entries.get(i).getKey(), exception);
                }
            }
            return Map.copyOf(result);
        } catch (TimeoutException exception) {
            if (future != null) future.cancel(true);
            onFailure("timeout");
            return Map.of();
        } catch (Exception exception) {
            onFailure(exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    public boolean put(KnowledgeRouteProfileBundle bundle) {
        try {
            String json = objectMapper.writeValueAsString(bundle);
            long ttlHours = Math.max(1L, properties.getKnowledgeScopeGate().getRedisTtlHours());
            long jitterMinutes = Math.floorMod(bundle.kbUuid().hashCode(), 60);
            redis.opsForValue().set(key(bundle.kbUuid(), bundle.generation()), json,
                    Duration.ofHours(ttlHours).plusMinutes(jitterMinutes));
            return true;
        } catch (Exception exception) {
            log.warn("Unable to publish knowledge route profile to Redis, kbUuid:{}, generation:{}",
                    bundle.kbUuid(), bundle.generation(), exception);
            return false;
        }
    }

    public void delete(String kbUuid, long generation) {
        if (kbUuid == null || generation <= 0) return;
        try {
            redis.delete(key(kbUuid, generation));
        } catch (RuntimeException exception) {
            log.debug("Unable to delete expired route-profile cache key, kbUuid:{}, generation:{}",
                    kbUuid, generation, exception);
        }
    }

    private void onFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        int threshold = Math.max(1, properties.getKnowledgeScopeGate().getCacheFailureThreshold());
        if (failures >= threshold) {
            long openMs = Math.max(1000L, properties.getKnowledgeScopeGate().getCacheCircuitOpenMs());
            circuitOpenUntilMs.set(System.currentTimeMillis() + openMs);
            consecutiveFailures.set(0);
            log.warn("Knowledge route-profile Redis circuit opened for {} ms after {}", openMs, reason);
        }
    }

    public static String key(String kbUuid, long generation) {
        return KEY_PREFIX + kbUuid + ":" + generation;
    }

}
