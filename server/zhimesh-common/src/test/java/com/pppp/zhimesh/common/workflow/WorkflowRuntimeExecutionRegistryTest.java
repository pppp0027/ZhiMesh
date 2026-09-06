package com.pppp.zhimesh.common.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkflowRuntimeExecutionRegistryTest {

    @Test
    void tracksOnlyCurrentlyActiveRuntimeIds() {
        WorkflowRuntimeExecutionRegistry registry = new WorkflowRuntimeExecutionRegistry();

        registry.register(11L);
        registry.register(12L);
        registry.register(null);
        registry.unregister(11L);

        assertEquals(Set.of(12L), registry.snapshot());
    }

    @Test
    void snapshotDoesNotChangeWhenRegistryChangesLater() {
        WorkflowRuntimeExecutionRegistry registry = new WorkflowRuntimeExecutionRegistry();
        registry.register(21L);
        Set<Long> snapshot = registry.snapshot();

        registry.unregister(21L);

        assertEquals(Set.of(21L), snapshot);
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void cancellationRequestSurvivesTheCreateRegisterRaceAndIsClearedOnExit() {
        WorkflowRuntimeExecutionRegistry registry = new WorkflowRuntimeExecutionRegistry();

        assertFalse(registry.requestCancellation(31L));
        registry.register(31L);

        assertTrue(registry.isCancellationRequested(31L));
        assertTrue(registry.requestCancellation(31L));

        registry.unregister(31L);
        assertFalse(registry.isCancellationRequested(31L));
    }
}
