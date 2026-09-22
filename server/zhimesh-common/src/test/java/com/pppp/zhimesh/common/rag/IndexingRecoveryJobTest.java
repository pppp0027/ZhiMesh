package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingRecoveryJobTest {

    @Test
    void delegatesGraphicalRecoveryToTheItemService() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        job.recoverAbandonedIndexing();

        verify(itemService).failTimedOutGraphIndexing();
    }

    @Test
    void delegatesEmbeddingAndFulltextRecoveryToTheItemService() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        job.recoverAbandonedIndexing();

        verify(itemService).failTimedOutEmbeddingIndexing();
        verify(itemService).failTimedOutFulltextIndexing();
    }

    @Test
    void alsoRetriesPendingGraphCleanupsOnEveryTick() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        job.recoverAbandonedIndexing();

        verify(itemService).retryPendingGraphCleanups();
    }

    @Test
    void cleanupRetryFailureDoesNotAffectTheStatusRecoveries() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        doThrow(new RuntimeException("redis down")).when(itemService).retryPendingGraphCleanups();
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        assertDoesNotThrow(job::recoverAbandonedIndexing);

        // The cleanup retry failed last, but the tick still ran the three
        // status recoveries before it instead of aborting the whole run.
        verify(itemService).failTimedOutEmbeddingIndexing();
        verify(itemService).failTimedOutGraphIndexing();
        verify(itemService).failTimedOutFulltextIndexing();
    }

    @Test
    void startupListenerFailsGraphIndexingStartedBeforeTheEventMoment() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        job.recoverGraphIndexingLeftByPreviousProcess();

        // Boundary = the moment the ready event is handled: DOING rows from the
        // previous process flip immediately instead of waiting out the timeout.
        verify(itemService).failGraphIndexingStartedBefore(any(LocalDateTime.class));
    }

    @Test
    void startupListenerFailureDoesNotBreakStartup() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        when(itemService.failGraphIndexingStartedBefore(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db down"));
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        assertDoesNotThrow(job::recoverGraphIndexingLeftByPreviousProcess);
    }

    @Test
    void swallowsRecoveryFailuresSoTheNextTickRetries() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        when(itemService.failTimedOutGraphIndexing()).thenThrow(new RuntimeException("db down"));
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        assertDoesNotThrow(job::recoverAbandonedIndexing);
    }

    @Test
    void oneFailingRecoveryStillRunsTheRemainingKinds() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        when(itemService.failTimedOutEmbeddingIndexing()).thenThrow(new RuntimeException("db down"));
        IndexingRecoveryJob job = new IndexingRecoveryJob(itemService);

        assertDoesNotThrow(job::recoverAbandonedIndexing);

        // The first kind failed, but the scheduler tick still reaches graphical
        // and fulltext recovery instead of aborting the whole run.
        verify(itemService).failTimedOutGraphIndexing();
        verify(itemService).failTimedOutFulltextIndexing();
    }
}

