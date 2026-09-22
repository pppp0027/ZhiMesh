package com.pppp.zhimesh.common.dto.evaluation;

import com.pppp.zhimesh.common.rag.intent.LegacyRetrievalModeAdapter;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.util.Set;

public record RagEvaluationAskReq(
        @NotBlank String questionId,
        @NotBlank String question,
        @NotNull @Positive Long answerModelId,
        @DecimalMin("0.0") @DecimalMax("2.0")
        Double temperature,
        RetrievalMode retrievalMode,
        Boolean useReranker,
        Boolean retrievalOnly,
        Boolean includeQueryEmbedding,
        Set<RetrievalRoute> retrievalRoutes,
        Boolean intentRouting
) {
    /** Source-compatible constructor for existing Java callers and tests. */
    public RagEvaluationAskReq(
            String questionId, String question, Long answerModelId, Double temperature,
            RetrievalMode retrievalMode, Boolean useReranker, Boolean retrievalOnly,
            Boolean includeQueryEmbedding, Set<RetrievalRoute> retrievalRoutes) {
        this(questionId, question, answerModelId, temperature, retrievalMode, useReranker,
                retrievalOnly, includeQueryEmbedding, retrievalRoutes, null);
    }

    /** Legacy constructor without explicit routes, kept for older callers. */
    public RagEvaluationAskReq(
            String questionId, String question, Long answerModelId, Double temperature,
            RetrievalMode retrievalMode, Boolean useReranker, Boolean retrievalOnly,
            Boolean includeQueryEmbedding) {
        this(questionId, question, answerModelId, temperature, retrievalMode, useReranker,
                retrievalOnly, includeQueryEmbedding, null, null);
    }

    public double effectiveTemperature() {
        return temperature == null ? 0D : temperature;
    }

    public RetrievalMode effectiveRetrievalMode() {
        return retrievalMode == null ? RetrievalMode.HYBRID : retrievalMode;
    }

    public Set<RetrievalRoute> effectiveRetrievalRoutes() {
        if (retrievalRoutes != null && !retrievalRoutes.isEmpty()) {
            return Set.copyOf(retrievalRoutes);
        }
        return Set.copyOf(LegacyRetrievalModeAdapter.toRoutes(effectiveRetrievalMode()));
    }

    public boolean effectiveRetrievalOnly() {
        return Boolean.TRUE.equals(retrievalOnly);
    }

    public boolean effectiveIncludeQueryEmbedding() {
        return Boolean.TRUE.equals(includeQueryEmbedding);
    }

    public boolean effectiveIntentRouting() {
        return Boolean.TRUE.equals(intentRouting);
    }
}
