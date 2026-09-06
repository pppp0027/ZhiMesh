package com.pppp.zhimesh.common.rag.bm25;

/** Result of atomically publishing one item's FULLTEXT build. */
public record Bm25IndexBuildResult(String indexBuildUuid,
                                   String chunkSetUuid,
                                   int documentCount,
                                   int postingCount,
                                   String analyzerVersion) {
}
