package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.exception.RetriableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Process-wide admission control and retry policy for graph-extraction LLM calls.
 * Per-document worker pools are intentionally not trusted as a global limit: when
 * several documents are submitted together each document can own its own workers.
 */
@Slf4j
@Component
public class GraphExtractionRequestExecutor {

    private final Semaphore permits;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    @Autowired
    public GraphExtractionRequestExecutor(ZhiMeshProperties properties) {
        this(properties.getIndexing().getGraphRequestConcurrency(),
                properties.getIndexing().getGraphRequestMaxAttempts(),
                properties.getIndexing().getGraphRetryInitialBackoffMs(),
                properties.getIndexing().getGraphRetryMaxBackoffMs());
    }

    GraphExtractionRequestExecutor(int concurrency, int maxAttempts,
                                   long initialBackoffMs, long maxBackoffMs) {
        this.permits = new Semaphore(Math.max(1, concurrency), true);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(0L, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
    }

    <T> T execute(String segmentId, String stage, Supplier<T> request) {
        Objects.requireNonNull(request, "request");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return invokeOnce(request);
            } catch (RuntimeException failure) {
                if (!isRetriable(failure) || attempt == maxAttempts) {
                    throw failure;
                }
                long backoffMs = retryBackoffMs(attempt);
                log.warn("Transient graph LLM request failure; retrying, segmentId:{}, stage:{}, "
                                + "attempt:{}/{}, backoffMs:{}, errorType:{}, message:{}",
                        segmentId, stage, attempt, maxAttempts, backoffMs,
                        failure.getClass().getSimpleName(), failure.getMessage());
                waitBeforeRetry(backoffMs);
            }
        }
        throw new IllegalStateException("Graph extraction retry loop completed without a result");
    }

    private <T> T invokeOnce(Supplier<T> request) {
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            return request.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a graph LLM request permit", exception);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private boolean isRetriable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            // RetriableException covers langchain4j's own transient classes:
            // TimeoutException, RateLimitException and InternalServerException.
            // The latter is how 5xx gateway replies (502/503 from upstream
            // relays) surface, and one such reply must not fail the whole
            // document — the exponential backoff below is exactly for them.
            if (current instanceof RetriableException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long retryBackoffMs(int failedAttempt) {
        if (initialBackoffMs == 0L) {
            return 0L;
        }
        int shift = Math.min(30, Math.max(0, failedAttempt - 1));
        long multiplier = 1L << shift;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(maxBackoffMs, initialBackoffMs * multiplier);
    }

    private void waitBeforeRetry(long backoffMs) {
        if (backoffMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off a graph LLM request", exception);
        }
    }
}
