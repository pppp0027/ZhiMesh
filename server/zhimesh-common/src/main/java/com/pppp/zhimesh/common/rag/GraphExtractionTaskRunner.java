package com.pppp.zhimesh.common.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Runs independent graph extraction tasks with a bounded, private thread pool.
 * Results retain input order even when individual tasks finish out of order.
 */
final class GraphExtractionTaskRunner {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private GraphExtractionTaskRunner() {
    }

    static <T, R> List<R> mapOrdered(List<T> tasks, int concurrency,
                                     Function<T, R> function) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(function, "function");
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be greater than zero");
        }
        if (tasks.isEmpty()) {
            return List.of();
        }

        int poolSize = Math.min(concurrency, tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, threadFactory());
        CompletionService<IndexedResult<R>> completionService =
                new ExecutorCompletionService<>(executor);
        List<Future<IndexedResult<R>>> futures = new ArrayList<>(tasks.size());
        try {
            for (int index = 0; index < poolSize; index++) {
                int taskIndex = index;
                futures.add(completionService.submit(() ->
                        new IndexedResult<>(taskIndex, function.apply(tasks.get(taskIndex)))));
            }

            List<R> orderedResults = new ArrayList<>(
                    Collections.nCopies(tasks.size(), null));
            int nextTaskIndex = poolSize;
            for (int completed = 0; completed < tasks.size(); completed++) {
                try {
                    IndexedResult<R> result = completionService.take().get();
                    orderedResults.set(result.index(), result.value());
                    if (nextTaskIndex < tasks.size()) {
                        int taskIndex = nextTaskIndex++;
                        futures.add(completionService.submit(() ->
                                new IndexedResult<>(taskIndex, function.apply(tasks.get(taskIndex)))));
                    }
                } catch (InterruptedException e) {
                    cancelAll(futures);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while extracting graph segments", e);
                } catch (ExecutionException e) {
                    cancelAll(futures);
                    throw propagate(e.getCause());
                } catch (CancellationException e) {
                    cancelAll(futures);
                    throw new IllegalStateException("Graph segment extraction was cancelled", e);
                }
            }
            return Collections.unmodifiableList(orderedResults);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ThreadFactory threadFactory() {
        return task -> {
            Thread thread = new Thread(task,
                    "graph-extract-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Graph segment extraction failed", cause);
    }

    private record IndexedResult<R>(int index, R value) {
    }
}
