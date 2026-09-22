package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 收敛崩溃或重启进程遗留的 DOING 索引状态（向量、图谱、全文三类），并重试
 * 索引失败后未完成的图谱清理。
 * Converges embedding, graphical and fulltext statuses left in DOING by a
 * crashed or restarted process, and retries graph cleanups that failed right
 * after an indexing error. Without the status recovery, such an item blocks
 * re-indexing forever because the indexing path deliberately skips items that
 * are already DOING; without the cleanup retry, half-written graph data would
 * linger with nobody re-attempting its removal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexingRecoveryJob {

    private final KnowledgeBaseItemService knowledgeBaseItemService;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void recoverAbandonedIndexing() {
        // Each recovery runs in its own try/catch: a database outage while
        // failing one index kind must not skip the other two, and must never
        // take down the scheduler — the next tick retries.
        try {
            knowledgeBaseItemService.failTimedOutEmbeddingIndexing();
        } catch (Exception exception) {
            log.error("Embedding indexing recovery failed", exception);
        }
        try {
            knowledgeBaseItemService.failTimedOutGraphIndexing();
        } catch (Exception exception) {
            log.error("Graph indexing recovery failed", exception);
        }
        try {
            knowledgeBaseItemService.failTimedOutFulltextIndexing();
        } catch (Exception exception) {
            log.error("Fulltext indexing recovery failed", exception);
        }
        try {
            knowledgeBaseItemService.retryPendingGraphCleanups();
        } catch (Exception exception) {
            log.error("Pending graph cleanup retry failed", exception);
        }
    }

    /**
     * Startup recovery: anything still graph-DOING once the application is
     * ready was left behind by the previous process, so it flips to FAIL at
     * startup instead of waiting out the configured DOING timeout (up to 60
     * minutes of blind window). Rows whose graph status changed after the
     * boundary belong to this process and stay untouched.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverGraphIndexingLeftByPreviousProcess() {
        try {
            int recovered = knowledgeBaseItemService.failGraphIndexingStartedBefore(LocalDateTime.now());
            if (recovered > 0) {
                log.warn("Recovered {} item(s) from graph indexing abandoned before startup", recovered);
            }
        } catch (Exception exception) {
            log.error("Startup graph indexing recovery failed", exception);
        }
    }
}
