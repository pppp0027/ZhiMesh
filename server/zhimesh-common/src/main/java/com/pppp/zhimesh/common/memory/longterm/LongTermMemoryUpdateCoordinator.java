package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.CHARACTER_MEMORY_UPDATE_LOCK;

/**
 * Serializes the read/decide/write semantic-memory merge for one character across
 * application instances. The renewable lease may safely cover the analysis-model
 * call because this work is off the user response path.
 */
@Slf4j
@Component
public class LongTermMemoryUpdateCoordinator {

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

    public LongTermMemoryUpdateCoordinator(StringRedisTemplate redisTemplate,
                                           ZhiMeshProperties properties,
                                           @Qualifier("lockRenewScheduler") ScheduledExecutorService renewExecutor) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.renewExecutor = renewExecutor;
    }

    public Lease acquire(Long characterId) {
        if (characterId == null) {
            throw new IllegalArgumentException("characterId is required for long-term memory updates");
        }
        long leaseMs = Math.max(1000L, properties.getMemory().getUpdateLockLeaseMs());
        long waitMs = Math.max(0L, properties.getMemory().getUpdateLockWaitMs());
        String key = MessageFormat.format(CHARACTER_MEMORY_UPDATE_LOCK, characterId);
        String owner = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        do {
            if (Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, owner, Duration.ofMillis(leaseMs)))) {
                Lease lease = new Lease(key, owner, leaseMs);
                lease.startRenewal();
                return lease;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for the long-term memory update lock");
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(10L,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the long-term memory update lock",
                        exception);
            }
        } while (true);
    }

    public final class Lease implements AutoCloseable {

        private final String key;
        private final String owner;
        private final long leaseMs;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private ScheduledFuture<?> renewal;

        private Lease(String key, String owner, long leaseMs) {
            this.key = key;
            this.owner = owner;
            this.leaseMs = leaseMs;
        }

        private void startRenewal() {
            long periodMs = Math.max(250L, leaseMs / 3L);
            renewal = renewExecutor.scheduleAtFixedRate(this::renew,
                    periodMs, periodMs, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (closed.get() || lost.get()) return;
            try {
                Long result = redisTemplate.execute(RENEW_SCRIPT,
                        Collections.singletonList(key), owner, Long.toString(leaseMs));
                if (result == null || result == 0L) {
                    lost.set(true);
                    log.error("Long-term memory update lock was lost, key:{}", key);
                }
            } catch (RuntimeException exception) {
                lost.set(true);
                log.error("Long-term memory update lock renewal failed, key:{}", key, exception);
            }
        }

        public void requireValid() {
            if (closed.get() || lost.get()) {
                throw new IllegalStateException("Long-term memory update lock is no longer valid");
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (renewal != null) renewal.cancel(false);
            try {
                redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key), owner);
            } catch (RuntimeException exception) {
                log.error("Long-term memory update lock release failed, key:{}", key, exception);
            }
        }
    }
}
