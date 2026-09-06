package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.mapper.OpenRouterSyncRunMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OpenRouterSyncRunService extends ServiceImpl<OpenRouterSyncRunMapper, OpenRouterSyncRun> {

    public OpenRouterSyncRun create(String triggerType, String status) {
        OpenRouterSyncRun run = new OpenRouterSyncRun();
        run.setUuid(UuidUtil.createShort());
        run.setTriggerType(triggerType);
        run.setStatus(status);
        run.setCatalogCount(0);
        run.setFreeCount(0);
        run.setEligibleCount(0);
        run.setProbedCount(0);
        run.setAddedCount(0);
        run.setUpdatedCount(0);
        run.setEnabledCount(0);
        run.setDisabledCount(0);
        run.setSkippedCount(0);
        run.setStartedAt(LocalDateTime.now());
        run.setErrorCode("");
        run.setErrorMessage("");
        run.setSummary(JsonUtil.createObjectNode());
        save(run);
        return getByUuid(run.getUuid());
    }

    public OpenRouterSyncRun getByUuid(String uuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(OpenRouterSyncRun::getUuid, uuid)
                .one();
    }

    public OpenRouterSyncRun latest() {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .orderByDesc(OpenRouterSyncRun::getId)
                .last("LIMIT 1")
                .one();
    }

    public OpenRouterSyncRun latestSuccessful() {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .in(OpenRouterSyncRun::getStatus, List.of("SUCCESS", "PARTIAL_RATE_LIMIT"))
                .orderByDesc(OpenRouterSyncRun::getCompletedAt)
                .last("LIMIT 1")
                .one();
    }

    /**
     * A lightweight health check proves only the configured models, not that a
     * fresh provider catalog was discovered. Startup catch-up must therefore
     * consider only executions that actually loaded the OpenRouter catalog.
     */
    public OpenRouterSyncRun latestSuccessfulCatalogSync() {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .in(OpenRouterSyncRun::getStatus, List.of("SUCCESS", "PARTIAL_RATE_LIMIT"))
                .in(OpenRouterSyncRun::getTriggerType, List.of(
                        "SCHEDULED", "MANUAL", "STARTUP_CATCH_UP",
                        "STARTUP_RECOVERY", "HEALTH_CHECK_RECOVERY"))
                .orderByDesc(OpenRouterSyncRun::getCompletedAt)
                .last("LIMIT 1")
                .one();
    }

    public OpenRouterSyncRun active() {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .in(OpenRouterSyncRun::getStatus, List.of("QUEUED", "RUNNING"))
                .orderByDesc(OpenRouterSyncRun::getId)
                .last("LIMIT 1")
                .one();
    }

    /**
     * Recover audit rows left active by a killed JVM or server reboot. The
     * timeout is deliberately longer than the 30-minute Redis lease, so a row
     * is never reclaimed while it could still legitimately own the job lock.
     */
    public int expireStaleActiveRuns(int timeoutMinutes) {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(timeoutMinutes);
        return expireActiveRunsStartedBefore(staleBefore);
    }

    public int expireActiveRunsStartedBefore(LocalDateTime cutoff) {
        List<OpenRouterSyncRun> staleRuns = ChainWrappers.lambdaQueryChain(baseMapper)
                .in(OpenRouterSyncRun::getStatus, List.of("QUEUED", "RUNNING"))
                .lt(OpenRouterSyncRun::getStartedAt, cutoff)
                .list();
        for (OpenRouterSyncRun run : staleRuns) {
            markTerminal(run.getUuid(), "FAILED", "STALE_RUN_RECOVERED",
                    "OpenRouter task was recovered after its execution lease expired");
        }
        return staleRuns.size();
    }

    public void markRunning(String uuid) {
        OpenRouterSyncRun run = getByUuid(uuid);
        if (run == null) {
            return;
        }
        OpenRouterSyncRun update = new OpenRouterSyncRun();
        update.setId(run.getId());
        update.setStatus("RUNNING");
        updateById(update);
    }

    public void markTerminal(String uuid, String status, String errorCode, String errorMessage) {
        OpenRouterSyncRun run = getByUuid(uuid);
        if (run == null) {
            return;
        }
        OpenRouterSyncRun update = new OpenRouterSyncRun();
        update.setId(run.getId());
        update.setStatus(status);
        update.setCompletedAt(LocalDateTime.now());
        update.setErrorCode(StringUtils.abbreviate(StringUtils.defaultString(errorCode), 64));
        update.setErrorMessage(StringUtils.abbreviate(StringUtils.defaultString(errorMessage), 1000));
        updateById(update);
    }
}
