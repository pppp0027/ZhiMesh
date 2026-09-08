package com.pppp.zhimesh.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
