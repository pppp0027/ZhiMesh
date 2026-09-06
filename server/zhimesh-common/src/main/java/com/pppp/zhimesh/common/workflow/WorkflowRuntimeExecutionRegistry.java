package com.pppp.zhimesh.common.workflow;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local lease registry for workflow runs that are actively executing.
 * The recovery job heartbeats these ids; after a process crash the set disappears,
 * allowing the new process to converge abandoned READY/DOING rows to a terminal state.
 */
@Component
public class WorkflowRuntimeExecutionRegistry {

    private final Set<Long> activeRuntimeIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancellationRequests = ConcurrentHashMap.newKeySet();

    public void register(Long runtimeId) {
        if (runtimeId != null) {
            activeRuntimeIds.add(runtimeId);
        }
    }

    public void unregister(Long runtimeId) {
        if (runtimeId != null) {
            activeRuntimeIds.remove(runtimeId);
            cancellationRequests.remove(runtimeId);
        }
    }

    /**
     * Records a cooperative cancellation request. The flag can be written just before a worker
     * registers, closing the small create/register race without interrupting arbitrary threads.
     *
     * @return true when this JVM currently owns an active execution for the runtime
     */
    public boolean requestCancellation(Long runtimeId) {
        if (runtimeId == null) {
            return false;
        }
        cancellationRequests.add(runtimeId);
        return activeRuntimeIds.contains(runtimeId);
    }

    public boolean isCancellationRequested(Long runtimeId) {
        return runtimeId != null && cancellationRequests.contains(runtimeId);
    }

    public Set<Long> snapshot() {
        return Set.copyOf(activeRuntimeIds);
    }
}
