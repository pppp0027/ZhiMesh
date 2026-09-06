package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.rag.GraphRag;
import com.pppp.zhimesh.common.rag.GraphStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class KnowledgeBaseItemServiceGraphWipeTest {

    private static final class RecordingGraphRag extends GraphRag {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<String> kbUuid = new AtomicReference<>();
        private final AtomicInteger permitsAtWipe = new AtomicInteger(-1);
        private final Semaphore ingestSemaphore;
        private final RuntimeException failure;

        RecordingGraphRag(Semaphore ingestSemaphore, RuntimeException failure) {
            super("test-kb-graph", mock(GraphStore.class));
            this.ingestSemaphore = ingestSemaphore;
            this.failure = failure;
        }

        @Override
        public void cleanupKnowledgeBase(String kbUuid) {
            invocations.incrementAndGet();
            this.kbUuid.set(kbUuid);
            // During the wipe no document build may hold a permit.
            permitsAtWipe.set(ingestSemaphore.availablePermits());
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ServiceWithRag {
        final KnowledgeBaseItemService service;
        final Semaphore semaphore;
        final AtomicReference<RecordingGraphRag> rag = new AtomicReference<>();

        ServiceWithRag(int graphConcurrency, RuntimeException failure) {
            service = new KnowledgeBaseItemService() {
                @Override
                GraphRag knowledgeBaseGraphRag() {
                    return rag.get();
                }
            };
            ZhiMeshProperties properties = new ZhiMeshProperties();
            properties.getIndexing().setGraphConcurrency(graphConcurrency);
            ReflectionTestUtils.setField(service, "adiProperties", properties);
            service.initializeIndexingConcurrency();
            semaphore = (Semaphore) ReflectionTestUtils.getField(service, "graphIngestSemaphore");
            rag.set(new RecordingGraphRag(semaphore, failure));
        }
    }

    @Test
    void wipeWaitsForInFlightBuildsThenRunsFullyDrainedAndReleasesPermits() throws Exception {
        ServiceWithRag harness = new ServiceWithRag(2, null);
        assertEquals(2, harness.semaphore.availablePermits());

        // One in-flight document build holds a permit.
        harness.semaphore.acquire();

        Thread worker = new Thread(() -> harness.service.cleanupKnowledgeBaseGraph("kb-1"));
        worker.start();
        // The wipe must block on the drain instead of deleting live-build data.
        worker.join(300);
        assertTrue(worker.isAlive(), "wipe must wait while a graph build holds a permit");
        assertEquals(0, harness.rag.get().invocations.get());

        // The in-flight build finishes; the wipe then runs and releases the drain.
        harness.semaphore.release();
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertEquals(1, harness.rag.get().invocations.get());
        assertEquals("kb-1", harness.rag.get().kbUuid.get());
        assertEquals(0, harness.rag.get().permitsAtWipe.get(), "wipe must observe a fully drained semaphore");
        assertEquals(2, harness.semaphore.availablePermits());
    }

    @Test
    void wipeFailureStillReleasesAllDrainedPermits() {
        ServiceWithRag harness = new ServiceWithRag(1, new RuntimeException("wipe failed"));

        assertThrows(RuntimeException.class, () -> harness.service.cleanupKnowledgeBaseGraph("kb-2"));
        assertEquals(1, harness.semaphore.availablePermits());
    }

    @Test
    void wipeWithSinglePermitConfigurationDrainsItself() {
        ServiceWithRag harness = new ServiceWithRag(1, null);

        harness.service.cleanupKnowledgeBaseGraph("kb-3");

        assertEquals(1, harness.rag.get().invocations.get());
        assertEquals(0, harness.rag.get().permitsAtWipe.get());
        assertEquals(1, harness.semaphore.availablePermits());
    }
}
