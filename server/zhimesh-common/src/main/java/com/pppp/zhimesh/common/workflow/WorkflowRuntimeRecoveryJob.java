package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.mapper.WorkflowRunMapper;
import com.pppp.zhimesh.common.mapper.WorkflowRuntimeNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Keeps live workflow leases fresh and converges abandoned database rows after a crash/restart. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowRuntimeRecoveryJob {

    static final int EXECUTION_STALE_SECONDS = 180;
    static final int WAITING_INPUT_STALE_SECONDS = 11 * 60;
    static final int CANCELLATION_STALE_SECONDS = 180;

    private final WorkflowRuntimeExecutionRegistry executionRegistry;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowRuntimeNodeMapper workflowRuntimeNodeMapper;

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void maintainRuntimeStates() {
        try {
            Set<Long> activeRuntimeIds = executionRegistry.snapshot();
            if (!activeRuntimeIds.isEmpty()) {
                workflowRunMapper.heartbeatActive(activeRuntimeIds);
            }

            int failed = workflowRunMapper.reconcileFailedRuntimes();
            int succeeded = workflowRunMapper.reconcileCompletedRuntimes();
            int interrupted = workflowRunMapper.failStaleRuntimes(EXECUTION_STALE_SECONDS);
            int interruptedNodes = workflowRuntimeNodeMapper.failNodesOfStaleRuntimes();
            int expiredWaiting = workflowRunMapper.expireStaleWaitingInput(WAITING_INPUT_STALE_SECONDS);
            int cancelled = workflowRunMapper.finalizeStaleCancellations(CANCELLATION_STALE_SECONDS);
            int cancelledNodes = workflowRuntimeNodeMapper.cancelNodesOfCancelledRuntimes();
            InterruptedFlow.cleanUp();

            if (failed + succeeded + interrupted + interruptedNodes + expiredWaiting + cancelled + cancelledNodes > 0) {
                log.info("Workflow runtime reconciliation: failed={}, succeeded={}, interrupted={}, interruptedNodes={}, expiredWaiting={}, cancelled={}, cancelledNodes={}",
                        failed, succeeded, interrupted, interruptedNodes, expiredWaiting, cancelled, cancelledNodes);
            }
        } catch (Exception e) {
            // A temporary database outage must not take down the scheduler; the next tick retries.
            log.error("Workflow runtime reconciliation failed", e);
        }
    }
}
