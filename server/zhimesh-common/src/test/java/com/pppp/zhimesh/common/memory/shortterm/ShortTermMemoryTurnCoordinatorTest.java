package com.pppp.zhimesh.common.memory.shortterm;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortTermMemoryTurnCoordinatorTest {

    private StringRedisTemplate template;
    private ValueOperations<String, String> values;
    private ZhiMeshProperties properties;
    private ShortTermMemoryTurnCoordinator coordinator;
    private ScheduledExecutorService renewExecutor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        properties = new ZhiMeshProperties();
        properties.getConversation().setShortMemoryTurnLockLease(Duration.ofSeconds(30));
        when(template.opsForValue()).thenReturn(values);
        renewExecutor = Executors.newScheduledThreadPool(2);
        coordinator = new ShortTermMemoryTurnCoordinator(template, properties, renewExecutor);
    }

    @AfterEach
    void tearDown() {
        coordinator.destroy();
        renewExecutor.shutdownNow();
    }

    @Test
    void acquiresAndReleasesOwnedLock() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(template.execute(any(), any(), any(Object[].class))).thenReturn(1L);

        ShortTermMemoryTurnCoordinator.TurnLease lease =
                coordinator.acquire("conversation:c-1", "request-1");

        assertThat(lease.isValid()).isTrue();
        coordinator.releaseRequest("request-1");
        assertThat(lease.isValid()).isFalse();
    }

    @Test
    void rejectsConcurrentTurnAfterWait() {
        properties.getConversation().setShortMemoryTurnLockWait(Duration.ZERO);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() ->
                coordinator.acquire("conversation:c-1", "request-2"))
                .isInstanceOf(ShortTermMemoryBusyException.class)
                .extracting(exception -> ((ShortTermMemoryBusyException) exception).getCode())
                .isEqualTo("A0071");
    }

    @Test
    void blankMemoryIdDoesNotAcquireDistributedLock() {

        ShortTermMemoryTurnCoordinator.TurnLease lease =
                coordinator.acquire("", null);

        assertThat(lease.isValid()).isTrue();
        verifyNoInteractions(values);
    }

    @Test
    void everyStatefulTurnRequiresDistributedLock() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(template.execute(any(), any(), any(Object[].class))).thenReturn(1L);

        ShortTermMemoryTurnCoordinator.TurnLease lease =
                coordinator.acquire("conversation:c-1", null);

        assertThat(lease.isValid()).isTrue();
        verify(values).setIfAbsent(anyString(), anyString(), any(Duration.class));
        lease.close();
    }
}
