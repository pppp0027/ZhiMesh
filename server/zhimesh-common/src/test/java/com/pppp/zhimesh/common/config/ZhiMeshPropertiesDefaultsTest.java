package com.pppp.zhimesh.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the Wave 1 performance defaults: raised graph extraction
 * concurrency (the three knobs must stay in lockstep) and the dedicated AGE
 * pool settings. Deployments override via ZHIMESH_* env variables.
 */
class ZhiMeshPropertiesDefaultsTest {

    @Test
    void graphExtractionConcurrencyDefaultsAreRaisedInLockstep() {
        ZhiMeshProperties.Indexing indexing = new ZhiMeshProperties().getIndexing();
        assertEquals(2, indexing.getGraphConcurrency());
        assertEquals(4, indexing.getGraphSegmentConcurrency());
        assertEquals(4, indexing.getGraphRequestConcurrency());
    }

    @Test
    void graphStorePoolDefaults() {
        ZhiMeshProperties.GraphStore graphStore = new ZhiMeshProperties().getGraphStore();
        assertTrue(graphStore.isPoolEnabled());
        assertEquals(4, graphStore.getPoolMaxSize());
        assertEquals(1, graphStore.getPoolMinIdle());
        assertEquals(8000L, graphStore.getPoolConnectionTimeoutMs());
        assertEquals(1800000L, graphStore.getPoolMaxLifetimeMs());
    }

    /**
     * Agent collaboration defaults (migration 044): agentic stays the product
     * default with a config-only rollback switch, MCP guardrails stay off until
     * rollout, and the suspension budgets keep their pinned values.
     */
    @Test
    void agentCollaborationDefaults() {
        ZhiMeshProperties.Agent agent = new ZhiMeshProperties().getAgent();
        assertEquals(8, agent.getMaxToolIterations());
        assertEquals(60000L, agent.getToolTimeoutMs());
        assertEquals(4000, agent.getToolResultMaxChars());
        assertTrue(agent.isDefaultAgenticEnabled());
        assertFalse(agent.isMcpGuardrailsEnabled());
        assertEquals(24, agent.getPendingTtlHours());
        assertEquals(3, agent.getMaxSuspensions());
    }
}
