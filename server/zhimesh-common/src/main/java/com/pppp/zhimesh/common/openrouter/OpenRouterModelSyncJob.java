package com.pppp.zhimesh.common.openrouter;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.service.OpenRouterSyncRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhimesh.openrouter-sync", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpenRouterModelSyncJob {

    static final long STARTUP_CATCH_UP_DELAY_SECONDS = 60;

    private final OpenRouterModelSyncService syncService;
    private final OpenRouterSyncRunService runService;
    private final ZhiMeshProperties properties;
    private final TaskScheduler taskScheduler;
    private final LocalDateTime applicationStartedAt;

    public OpenRouterModelSyncJob(OpenRouterModelSyncService syncService,
                                  OpenRouterSyncRunService runService,
                                  ZhiMeshProperties properties,
                                  @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.syncService = syncService;
        this.runService = runService;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.applicationStartedAt = LocalDateTime.now();
    }

    @Scheduled(cron = "${zhimesh.openrouter-sync.cron:0 0 4 * * *}",
            zone = "${zhimesh.openrouter-sync.zone:Asia/Shanghai}")
    public void runDaily() {
        log.info("Queueing scheduled OpenRouter free-model synchronization");
        syncService.queueScheduled("SCHEDULED");
    }

    /**
     * Lightweight checks run after the daily catalog sync. The service queues
     * the work on the background executor so a slow provider never occupies a
     * scheduler thread for the duration of the probes.
     */
    @Scheduled(cron = "${zhimesh.openrouter-sync.health-check-cron:0 0 0,9,14,19 * * *}",
            zone = "${zhimesh.openrouter-sync.zone:Asia/Shanghai}")
    public void runHealthCheck() {
        if (!properties.getOpenrouterSync().isHealthCheckEnabled()) {
            return;
        }
        log.info("Queueing scheduled OpenRouter lightweight model health check");
        syncService.queueHealthCheck();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleStartupCatchUp() {
        try {
            taskScheduler.schedule(this::runStartupCatchUpIfStale,
                    Instant.now().plusSeconds(STARTUP_CATCH_UP_DELAY_SECONDS));
        } catch (RejectedExecutionException error) {
            log.warn("OpenRouter startup catch-up could not be scheduled", error);
        }
    }

    void runStartupCatchUpIfStale() {
        int abandoned = runService.expireActiveRunsStartedBefore(applicationStartedAt);
        if (abandoned > 0) {
            log.warn("Recovered {} OpenRouter task(s) abandoned by the previous single-instance process", abandoned);
        }
        syncService.expireStaleRuns();
        if (runService.active() != null) {
            log.info("Skipping OpenRouter startup catch-up because a sync is already active");
            return;
        }
        if (syncService.needsInventoryRecovery()) {
            log.warn("OpenRouter enabled free-model inventory is below its safety floor; queueing startup recovery");
            syncService.queueScheduled("STARTUP_RECOVERY");
            return;
        }
        OpenRouterSyncRun latestSuccessful = runService.latestSuccessfulCatalogSync();
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusHours(properties.getOpenrouterSync().getCatchUpAfterHours());
        if (latestSuccessful != null && latestSuccessful.getCompletedAt() != null
                && !latestSuccessful.getCompletedAt().isBefore(staleBefore)) {
            log.info("OpenRouter startup catch-up is not needed; latest successful run:{}",
                    latestSuccessful.getUuid());
            return;
        }
        log.info("Starting OpenRouter startup catch-up synchronization");
        syncService.queueScheduled("STARTUP_CATCH_UP");
    }
}
