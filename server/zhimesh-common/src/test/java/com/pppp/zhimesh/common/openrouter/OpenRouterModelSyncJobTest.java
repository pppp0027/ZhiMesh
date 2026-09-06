package com.pppp.zhimesh.common.openrouter;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.service.OpenRouterSyncRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterModelSyncJobTest {

    private OpenRouterModelSyncService syncService;
    private OpenRouterSyncRunService runService;
    private OpenRouterModelSyncJob job;

    @BeforeEach
    void setUp() {
        syncService = mock(OpenRouterModelSyncService.class);
        runService = mock(OpenRouterSyncRunService.class);
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getOpenrouterSync().setCatchUpAfterHours(26);
        job = new OpenRouterModelSyncJob(syncService, runService, properties, mock(TaskScheduler.class));
    }

    @Test
    void runsStartupCatchUpWhenTheLatestSuccessIsStale() {
        OpenRouterSyncRun stale = successfulRun(LocalDateTime.now().minusHours(27));
        when(runService.latestSuccessfulCatalogSync()).thenReturn(stale);

        job.runStartupCatchUpIfStale();

        verify(syncService).queueScheduled("STARTUP_CATCH_UP");
    }

    @Test
    void skipsStartupCatchUpAfterARecentSuccess() {
        when(runService.latestSuccessfulCatalogSync()).thenReturn(successfulRun(LocalDateTime.now().minusHours(2)));

        job.runStartupCatchUpIfStale();

        verify(syncService, never()).queueScheduled("STARTUP_CATCH_UP");
    }

    @Test
    void skipsStartupCatchUpWhileAnotherRunIsActive() {
        when(runService.active()).thenReturn(new OpenRouterSyncRun());

        job.runStartupCatchUpIfStale();

        verify(syncService, never()).queueScheduled("STARTUP_CATCH_UP");
        verify(runService, never()).latestSuccessfulCatalogSync();
    }

    @Test
    void queuesLightweightHealthCheckOnItsDedicatedSchedule() {
        job.runHealthCheck();

        verify(syncService).queueHealthCheck();
    }

    @Test
    void queuesTheLongDailySyncOnTheBackgroundExecutor() {
        job.runDaily();

        verify(syncService).queueScheduled("SCHEDULED");
    }

    @Test
    void startupInventoryShortfallOverridesARecentCatalogSync() {
        when(syncService.needsInventoryRecovery()).thenReturn(true);
        when(runService.latestSuccessfulCatalogSync())
                .thenReturn(successfulRun(LocalDateTime.now().minusHours(1)));

        job.runStartupCatchUpIfStale();

        verify(syncService).queueScheduled("STARTUP_RECOVERY");
        verify(runService, never()).latestSuccessfulCatalogSync();
    }

    private OpenRouterSyncRun successfulRun(LocalDateTime completedAt) {
        OpenRouterSyncRun run = new OpenRouterSyncRun();
        run.setUuid("completed-run");
        run.setCompletedAt(completedAt);
        return run;
    }
}
