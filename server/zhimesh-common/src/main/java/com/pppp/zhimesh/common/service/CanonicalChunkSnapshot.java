package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;
import java.util.Objects;

/** Immutable list containers for one active canonical chunk-set snapshot. */
public record CanonicalChunkSnapshot(
        KnowledgeBaseChunkSet chunkSet,
        List<KnowledgeBaseChunk> chunks,
        List<TextSegment> segments) {

    public CanonicalChunkSnapshot {
        Objects.requireNonNull(chunkSet, "chunkSet");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (chunks.size() != segments.size()) {
            throw new IllegalArgumentException("chunks and segments must have the same size");
        }
    }
}
