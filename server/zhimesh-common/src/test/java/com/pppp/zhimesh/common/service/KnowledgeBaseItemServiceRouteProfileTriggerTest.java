package com.pppp.zhimesh.common.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseItemServiceRouteProfileTriggerTest {

    @Test
    void batchCompletionFiresOnlyAfterSubmissionAndEveryTaskFinish() {
        AtomicInteger completions = new AtomicInteger();
        KnowledgeBaseItemService.IndexBatchCompletion batch =
                new KnowledgeBaseItemService.IndexBatchCompletion(completions::incrementAndGet);

        batch.taskScheduled();
        batch.taskScheduled();
        batch.taskCompleted();
        assertThat(completions).hasValue(0);
        batch.submissionFinished();
        assertThat(completions).hasValue(0);
        batch.taskCompleted();
        assertThat(completions).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedding", "graphical", "fulltext"})
    void everyExplicitDerivedIndexOperationTriggersAProfileGeneration(String indexType) {
        assertThat(KnowledgeBaseItemService.isRouteProfileIndexOperation(
                List.of(indexType), false, false)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadOrContentMutationWithoutIndexingDoesNotTriggerAProfileGeneration(boolean bm25Enabled) {
        assertThat(KnowledgeBaseItemService.isRouteProfileIndexOperation(
                List.of(), bm25Enabled, false)).isFalse();
    }
}
