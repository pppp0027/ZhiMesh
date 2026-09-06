package com.pppp.zhimesh.common.rag.bm25;

/** Explicit fail-closed signal for a requested but unavailable BM25 route. */
public class Bm25UnavailableException extends IllegalStateException {
    public Bm25UnavailableException(String message) {
        super(message);
    }
}
