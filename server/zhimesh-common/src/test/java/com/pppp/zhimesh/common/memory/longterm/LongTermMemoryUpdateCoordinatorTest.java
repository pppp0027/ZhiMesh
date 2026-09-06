package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryUpdateCoordinatorTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;
    private ZhiMeshProperties properties;
    private LongTermMemoryUpdateCoordinator coordinator;
    private ScheduledExecutorService renewExecutor;

    @BeforeEach
    void setUp() {
        properties = new ZhiMeshProperties();
        properties.getMemory().setUpdateLockWaitMs(0L);
        properties.getMemory().setUpdateLockLeaseMs(60_000L);
        when(redis.opsForValue()).thenReturn(values);
        renewExecutor = Executors.newScheduledThreadPool(2);
        coordinator = new LongTermMemoryUpdateCoordinator(redis, properties, renewExecutor);
    }

    @AfterEach
    void tearDown() {
        renewExecutor.shutdownNow();
    }

    @Test
    void scopesTheDistributedLockByCharacter() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        try (LongTermMemoryUpdateCoordinator.Lease ignored = coordinator.acquire(42L)) {
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(values).setIfAbsent(key.capture(), anyString(), any(Duration.class));
            assertThat(key.getValue()).isEqualTo("character:memory:update-lock:42");
        }
    }

    @Test
    void failsClosedWhenTheCharacterLockCannotBeAcquired() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> coordinator.acquire(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out");
    }
}
