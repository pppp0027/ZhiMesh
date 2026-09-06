package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExtractionRequestExecutorTest {

    @Test
    void canBeCreatedBySpringWithItsConfigurationDependency() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ZhiMeshProperties.class);
            context.registerBean(GraphExtractionRequestExecutor.class);
            context.refresh();

            assertNotNull(context.getBean(GraphExtractionRequestExecutor.class));
        }
    }

    @Test
    void limitsRequestsAcrossConcurrentCallers() throws Exception {
        GraphExtractionRequestExecutor requestExecutor =
                new GraphExtractionRequestExecutor(2, 1, 0, 0);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int value = 0; value < 4; value++) {
                int result = value;
                futures.add(callers.submit(() -> requestExecutor.execute("segment", "extract", () -> {
                    int activeNow = active.incrementAndGet();
                    maximumActive.accumulateAndGet(activeNow, Math::max);
                    firstTwoStarted.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test requests did not receive release signal");
                        }
                        return result;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    } finally {
                        active.decrementAndGet();
                    }
                })));
            }

            assertTrue(firstTwoStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, maximumActive.get());
            release.countDown();
            for (int index = 0; index < futures.size(); index++) {
                assertEquals(index, futures.get(index).get(2, TimeUnit.SECONDS));
            }
            assertEquals(2, maximumActive.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void retriesTransientTimeoutAndEventuallyReturns() {
        GraphExtractionRequestExecutor requestExecutor =
                new GraphExtractionRequestExecutor(2, 3, 1, 2);
        AtomicInteger attempts = new AtomicInteger();

        String result = requestExecutor.execute("segment", "extract", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException(new HttpTimeoutException("request timed out"));
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryNonTransientFailure() {
        GraphExtractionRequestExecutor requestExecutor =
                new GraphExtractionRequestExecutor(2, 3, 0, 0);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () ->
                requestExecutor.execute("segment", "extract", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("invalid response");
                }));

        assertEquals(1, attempts.get());
    }
}
