package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Converges graphical statuses left in DOING by a crashed or restarted
 * process. Without this, such an item blocks re-indexing forever because the
 * indexing path deliberately skips items that are already DOING.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphIndexingRecoveryJob {

    private final KnowledgeBaseItemService knowledgeBaseItemService;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void recoverAbandonedGraphIndexing() {
        try {
            knowledgeBaseItemService.failTimedOutGraphIndexing();
        } catch (Exception exception) {
            // A temporary database outage must not take down the scheduler; the next tick retries.
            log.error("Graph indexing recovery failed", exception);
        }
    }
}
