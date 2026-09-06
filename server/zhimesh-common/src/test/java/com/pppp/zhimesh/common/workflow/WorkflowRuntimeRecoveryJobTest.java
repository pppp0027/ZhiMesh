package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.mapper.WorkflowRunMapper;
import com.pppp.zhimesh.common.mapper.WorkflowRuntimeNodeMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRuntimeRecoveryJobTest {

    @Test
    void heartbeatsActiveRunsBeforeReconcilingAbandonedRows() {
        WorkflowRuntimeExecutionRegistry registry = mock(WorkflowRuntimeExecutionRegistry.class);
        WorkflowRunMapper runtimeMapper = mock(WorkflowRunMapper.class);
        WorkflowRuntimeNodeMapper nodeMapper = mock(WorkflowRuntimeNodeMapper.class);
        when(registry.snapshot()).thenReturn(Set.of(31L, 32L));
        WorkflowRuntimeRecoveryJob job = new WorkflowRuntimeRecoveryJob(registry, runtimeMapper, nodeMapper);

        job.maintainRuntimeStates();

        var ordered = inOrder(runtimeMapper, nodeMapper);
        ordered.verify(runtimeMapper).heartbeatActive(Set.of(31L, 32L));
        ordered.verify(runtimeMapper).reconcileFailedRuntimes();
        ordered.verify(runtimeMapper).reconcileCompletedRuntimes();
        ordered.verify(runtimeMapper).failStaleRuntimes(WorkflowRuntimeRecoveryJob.EXECUTION_STALE_SECONDS);
        ordered.verify(nodeMapper).failNodesOfStaleRuntimes();
        ordered.verify(runtimeMapper).expireStaleWaitingInput(WorkflowRuntimeRecoveryJob.WAITING_INPUT_STALE_SECONDS);
        ordered.verify(runtimeMapper).finalizeStaleCancellations(WorkflowRuntimeRecoveryJob.CANCELLATION_STALE_SECONDS);
        ordered.verify(nodeMapper).cancelNodesOfCancelledRuntimes();
    }

    @Test
    void skipsHeartbeatWhenNoRunIsActiveButStillPerformsRecovery() {
        WorkflowRuntimeExecutionRegistry registry = mock(WorkflowRuntimeExecutionRegistry.class);
        WorkflowRunMapper runtimeMapper = mock(WorkflowRunMapper.class);
        WorkflowRuntimeNodeMapper nodeMapper = mock(WorkflowRuntimeNodeMapper.class);
        when(registry.snapshot()).thenReturn(Set.of());
        WorkflowRuntimeRecoveryJob job = new WorkflowRuntimeRecoveryJob(registry, runtimeMapper, nodeMapper);

        job.maintainRuntimeStates();

        verify(runtimeMapper, never()).heartbeatActive(Set.of());
        verify(runtimeMapper).failStaleRuntimes(WorkflowRuntimeRecoveryJob.EXECUTION_STALE_SECONDS);
        verify(runtimeMapper).finalizeStaleCancellations(WorkflowRuntimeRecoveryJob.CANCELLATION_STALE_SECONDS);
    }
}
