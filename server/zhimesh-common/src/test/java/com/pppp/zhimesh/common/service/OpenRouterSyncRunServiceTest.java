package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.mapper.OpenRouterSyncRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterSyncRunServiceTest {

    @Test
    void expiresAnAbandonedRunningAuditSoFutureSchedulesCanProceed() {
        OpenRouterSyncRunMapper mapper = mock(OpenRouterSyncRunMapper.class);
        OpenRouterSyncRunService service = new OpenRouterSyncRunService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        OpenRouterSyncRun stale = new OpenRouterSyncRun();
        stale.setId(91L);
        stale.setUuid("stale-run");
        stale.setStatus("RUNNING");
        stale.setStartedAt(LocalDateTime.now().minusMinutes(40));
        when(mapper.selectList(any())).thenReturn(List.of(stale));
        when(mapper.selectOne(any())).thenReturn(stale);

        int expired = service.expireStaleActiveRuns(35);

        assertThat(expired).isEqualTo(1);
        ArgumentCaptor<OpenRouterSyncRun> update = ArgumentCaptor.forClass(OpenRouterSyncRun.class);
        verify(mapper).updateById(update.capture());
        assertThat(update.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(update.getValue().getErrorCode()).isEqualTo("STALE_RUN_RECOVERED");
    }
}
