package com.pppp.zhimesh.common.rag;

import dev.langchain4j.rag.content.Content;

import java.util.List;

public record RetrievalRouteResult(
        String route,
        RetrievalRouteStatus status,
        List<Content> contents,
        long durationMs,
        String errorType,
        String errorMessage
) {
    public static RetrievalRouteResult completed(String route, List<Content> contents, long durationMs) {
        List<Content> safeContents = contents == null ? List.of() : List.copyOf(contents);
        RetrievalRouteStatus status = safeContents.isEmpty()
                ? RetrievalRouteStatus.EMPTY
                : RetrievalRouteStatus.SUCCESS;
        return new RetrievalRouteResult(route, status, safeContents, durationMs, null, null);
    }

    public static RetrievalRouteResult timeout(String route, long durationMs) {
        return new RetrievalRouteResult(route, RetrievalRouteStatus.TIMEOUT, List.of(), durationMs,
                "TimeoutException", "Retrieval route timed out");
    }

    public static RetrievalRouteResult error(String route, long durationMs, Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return new RetrievalRouteResult(route, RetrievalRouteStatus.ERROR, List.of(), durationMs,
                throwable == null ? null : throwable.getClass().getSimpleName(), message);
    }

    public boolean hasUsableContent() {
        return status == RetrievalRouteStatus.SUCCESS && !contents.isEmpty();
    }

    public boolean failed() {
        return status == RetrievalRouteStatus.ERROR || status == RetrievalRouteStatus.TIMEOUT;
    }
}
