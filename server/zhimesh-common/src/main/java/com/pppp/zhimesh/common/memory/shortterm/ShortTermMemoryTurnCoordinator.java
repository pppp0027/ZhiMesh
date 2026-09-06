package com.pppp.zhimesh.common.memory.shortterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a renewable distributed lock for the complete stateful chat turn.
 */
@Slf4j
@Component
public class ShortTermMemoryTurnCoordinator {

    private static final RedisScript<Long> RENEW_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ZhiMeshProperties properties;
    private final ScheduledExecutorService renewExecutor;
    private final Map<String, TurnLease> requestLeases = new ConcurrentHashMap<>();

    public ShortTermMemoryTurnCoordinator(StringRedisTemplate redisTemplate,
                                          ZhiMeshProperties properties,
                                          @Qualifier("lockRenewScheduler") ScheduledExecutorService renewExecutor) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.renewExecutor = renewExecutor;
    }

    public TurnLease acquire(String memoryId, String requestId) {
        if (memoryId == null || memoryId.isBlank()) {
            return new TurnLease(null, null, Duration.ZERO, true);
        }
        Duration lease = positive(properties.getConversation().getShortMemoryTurnLockLease(),
                Duration.ofSeconds(30));
        Duration wait = nonNegative(properties.getConversation().getShortMemoryTurnLockWait(),
                Duration.ofSeconds(2));
        String key = lockKey(memoryId);
        String owner = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        boolean acquired;
        do {
            acquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, owner, lease));
            if (acquired) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                throw new ShortTermMemoryBusyException();
            }
            try {
                Thread.sleep(Math.min(50L,
                        Math.max(10L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ShortTermMemoryBusyException();
            }
        } while (true);

        TurnLease turnLease = new TurnLease(key, owner, lease);
        turnLease.startRenewal();
        if (requestId != null && !requestId.isBlank()) {
            TurnLease previous = requestLeases.put(requestId, turnLease);
            if (previous != null) {
                previous.close();
            }
            turnLease.requestId = requestId;
        }
        return turnLease;
    }

    public void releaseRequest(String requestId) {
        if (requestId == null) {
            return;
        }
        TurnLease lease = requestLeases.remove(requestId);
        if (lease != null) {
            lease.close();
        }
    }

    private String lockKey(String memoryId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(memoryId.getBytes(StandardCharsets.UTF_8));
        return RedisChatMemoryStore.PREFIX + "lock:" + encoded;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration nonNegative(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }

    @PreDestroy
    public void destroy() {
        requestLeases.values().forEach(TurnLease::close);
        requestLeases.clear();
    }

    public final class TurnLease implements AutoCloseable {

        private final String key;
        private final String owner;
        private final Duration lease;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private ScheduledFuture<?> renewal;
        private String requestId;
        private final boolean noop;

        private TurnLease(String key, String owner, Duration lease) {
            this(key, owner, lease, false);
        }

        private TurnLease(String key, String owner, Duration lease, boolean noop) {
            this.key = key;
            this.owner = owner;
            this.lease = lease;
            this.noop = noop;
        }

        private void startRenewal() {
            long periodMs = Math.max(100L, lease.toMillis() / 3L);
            renewal = renewExecutor.scheduleAtFixedRate(this::renew,
                    periodMs, periodMs, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (closed.get() || lost.get()) {
                return;
            }
            try {
                Long result = redisTemplate.execute(RENEW_SCRIPT,
                        Collections.singletonList(key), owner,
                        Long.toString(lease.toMillis()));
                if (result == null || result == 0L) {
                    lost.set(true);
                    log.error("Short-memory turn lock was lost, key:{}", key);
                }
            } catch (RuntimeException exception) {
                lost.set(true);
                log.error("Short-memory turn lock renewal failed, key:{}", key, exception);
            }
        }

        public void requireValid() {
            if (!noop && (closed.get() || lost.get())) {
                throw new IllegalStateException("Short-term memory turn lock is no longer valid");
            }
        }

        public boolean isValid() {
            return noop || !closed.get() && !lost.get();
        }

        @Override
        public void close() {
            if (noop || !closed.compareAndSet(false, true)) {
                return;
            }
            if (renewal != null) {
                renewal.cancel(false);
            }
            if (requestId != null) {
                requestLeases.remove(requestId, this);
            }
            try {
                redisTemplate.execute(RELEASE_SCRIPT,
                        Collections.singletonList(key), owner);
            } catch (RuntimeException exception) {
                // Lease expiry is the final safety net when Redis is temporarily unavailable.
                log.error("Short-memory turn lock release failed, key:{}", key, exception);
            }
        }
    }

}
