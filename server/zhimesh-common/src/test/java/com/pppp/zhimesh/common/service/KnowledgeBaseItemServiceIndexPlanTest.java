package com.pppp.zhimesh.common.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseItemServiceIndexPlanTest {

    /**
     * Every combination of the three index types resolves to exactly the requested
     * branches, and with canonical chunks enabled all of them share one snapshot
     * so vector, BM25 and graph stay aligned on the same chunk UUIDs.
     */
    @ParameterizedTest
    @CsvSource(value = {
            "embedding                  | true  | false | false",
            "graphical                  | false | false | true",
            "fulltext                   | false | true  | false",
            "embedding,graphical        | true  | false | true",
            "embedding,fulltext         | true  | true  | false",
            "graphical,fulltext         | false | true  | true",
            "embedding,graphical,fulltext| true  | true  | true",
    }, delimiterString = "|")
    void everyIndexTypeCombinationRunsExactlyTheRequestedBranchesOnOneSnapshot(
            String indexTypes, boolean embedding, boolean bm25, boolean graph) {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of(indexTypes.split(",")), false, false, false, true, false, true);

        assertThat(plan.embeddingRequested()).isEqualTo(embedding);
        assertThat(plan.bm25Requested()).isEqualTo(bm25);
        assertThat(plan.graphRequested()).isEqualTo(graph);
        assertThat(plan.canonicalChunksRequired()).isTrue();
    }

    @Test
    void bm25BranchForcesCanonicalChunksEvenWhenTheToggleIsOff() {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of("fulltext"), false, false, false, true, false, false);

        assertThat(plan.bm25Requested()).isTrue();
        assertThat(plan.canonicalChunksRequired()).isTrue();
    }

    @Test
    void graphOnlyWithoutCanonicalToggleKeepsTheLegacySplittingPath() {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of("graphical"), false, false, false, true, false, false);

        assertThat(plan.graphRequested()).isTrue();
        assertThat(plan.canonicalChunksRequired()).isFalse();
    }

    @Test
    void fulltextIsSkippedWhenBm25IsDisabled() {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of("fulltext"), false, false, false, false, false, true);

        assertThat(plan.bm25Requested()).isFalse();
        assertThat(plan.canonicalChunksRequired()).isFalse();
    }

    @Test
    void autoIndexRebuildsBm25AlongsideOtherBranches() {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of("embedding"), false, false, false, true, true, true);

        assertThat(plan.embeddingRequested()).isTrue();
        assertThat(plan.bm25Requested()).isTrue();
    }

    @Test
    void branchAlreadyInProgressIsSkippedButSiblingsStillRun() {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of("embedding", "graphical", "fulltext"), true, false, true, true, false, true);

        assertThat(plan.embeddingRequested()).isFalse();
        assertThat(plan.bm25Requested()).isFalse();
        assertThat(plan.graphRequested()).isTrue();
        assertThat(plan.canonicalChunksRequired()).isTrue();
    }

    @Test
    void emptyRequestRunsNothing() {
        KnowledgeBaseItemService.IndexRequestPlan plan = KnowledgeBaseItemService.planIndexRequests(
                List.of(), false, false, false, true, false, true);

        assertThat(plan.embeddingRequested()).isFalse();
        assertThat(plan.bm25Requested()).isFalse();
        assertThat(plan.graphRequested()).isFalse();
        assertThat(plan.canonicalChunksRequired()).isFalse();
    }
}
