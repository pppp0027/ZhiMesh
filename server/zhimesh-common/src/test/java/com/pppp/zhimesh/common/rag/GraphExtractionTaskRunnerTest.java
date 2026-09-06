package com.pppp.zhimesh.common.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExtractionTaskRunnerTest {

    @Test
    void neverRunsMoreThanTwoTasksAtOnce() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch releaseFirstTwo = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<Integer>> result = caller.submit(() ->
                    GraphExtractionTaskRunner.mapOrdered(List.of(1, 2, 3, 4, 5), 2, value -> {
                        int activeNow = active.incrementAndGet();
                        maximumActive.accumulateAndGet(activeNow, Math::max);
                        firstTwoStarted.countDown();
                        try {
                            if (!releaseFirstTwo.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test tasks did not receive release signal");
                            }
                            return value;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } finally {
                            active.decrementAndGet();
                        }
                    }));

            assertTrue(firstTwoStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, maximumActive.get());
            releaseFirstTwo.countDown();
            assertEquals(List.of(1, 2, 3, 4, 5), result.get(3, TimeUnit.SECONDS));
            assertEquals(2, maximumActive.get());
        } finally {
            releaseFirstTwo.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void keepsInputOrderWhenTasksFinishOutOfOrder() {
        List<Integer> result = GraphExtractionTaskRunner.mapOrdered(
                List.of(1, 2, 3), 2, value -> {
                    if (value == 1) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    return value * 10;
                });

        assertEquals(List.of(10, 20, 30), result);
    }

    @Test
    void propagatesOriginalRuntimeFailureAndCancelsRemainingTasks() throws Exception {
        IllegalArgumentException expected = new IllegalArgumentException("extraction failed");
        CountDownLatch slowTaskStarted = new CountDownLatch(1);
        CountDownLatch slowTaskInterrupted = new CountDownLatch(1);
        AtomicBoolean thirdTaskRan = new AtomicBoolean();

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, () ->
                GraphExtractionTaskRunner.mapOrdered(List.of(1, 2, 3), 2, value -> {
                    if (value == 1) {
                        slowTaskStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException e) {
                            slowTaskInterrupted.countDown();
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    if (value == 2) {
                        try {
                            if (!slowTaskStarted.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("slow task did not start");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        throw expected;
                    }
                    thirdTaskRan.set(true);
                    return value;
                }));

        assertSame(expected, actual);
        assertTrue(slowTaskInterrupted.await(2, TimeUnit.SECONDS));
        assertTrue(!thirdTaskRan.get());
    }

    @Test
    void handlesEmptyAndSingleTaskInputs() {
        assertTrue(GraphExtractionTaskRunner.mapOrdered(List.<Integer>of(), 2,
                value -> value).isEmpty());
        assertEquals(List.of("only"), GraphExtractionTaskRunner.mapOrdered(
                List.of("only"), 2, value -> value));
    }
}
