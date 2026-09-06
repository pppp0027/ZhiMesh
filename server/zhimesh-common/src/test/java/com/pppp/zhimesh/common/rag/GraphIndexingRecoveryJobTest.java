package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphIndexingRecoveryJobTest {

    @Test
    void delegatesRecoveryToTheItemService() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        GraphIndexingRecoveryJob job = new GraphIndexingRecoveryJob(itemService);

        job.recoverAbandonedGraphIndexing();

        verify(itemService).failTimedOutGraphIndexing();
    }

    @Test
    void swallowsRecoveryFailuresSoTheNextTickRetries() {
        KnowledgeBaseItemService itemService = mock(KnowledgeBaseItemService.class);
        when(itemService.failTimedOutGraphIndexing()).thenThrow(new RuntimeException("db down"));
        GraphIndexingRecoveryJob job = new GraphIndexingRecoveryJob(itemService);

        assertDoesNotThrow(job::recoverAbandonedGraphIndexing);
    }
}
