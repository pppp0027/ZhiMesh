package com.pppp.zhimesh.common.rag.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RouteActivationStats} 计数与汇总边界的行为验证：key 计数正确、
 * 满 SUMMARY_EVERY 次后清零开新窗口、reset 清空全部状态。
 */
class RouteActivationStatsTest {

    @BeforeEach
    void resetStats() {
        RouteActivationStats.reset();
    }

    @Test
    void countsPerKeyAndExposesSnapshot() {
        RouteActivationStats.record("intent=KNOWLEDGE_LOOKUP;routes=VECTOR;fallback=false");
        RouteActivationStats.record("intent=KNOWLEDGE_LOOKUP;routes=VECTOR;fallback=false");
        RouteActivationStats.record("intent=UNCERTAIN;routes=VECTOR;fallback=true");

        Map<String, Long> snapshot = RouteActivationStats.snapshot();
        assertThat(snapshot).containsExactly(
                Map.entry("intent=KNOWLEDGE_LOOKUP;routes=VECTOR;fallback=false", 2L),
                Map.entry("intent=UNCERTAIN;routes=VECTOR;fallback=true", 1L));
    }

    @Test
    void summaryBoundaryResetsWindowAndStartsANewOne() {
        for (int i = 0; i < RouteActivationStats.SUMMARY_EVERY; i++) {
            RouteActivationStats.record("intent=RELATIONSHIP;routes=GRAPH+VECTOR;fallback=false");
        }
        // 汇总已输出并清零：窗口重启，旧计数不残留
        // Summary logged and counters reset: the window restarts without stale counts
        assertThat(RouteActivationStats.snapshot()).isEmpty();

        RouteActivationStats.record("intent=KNOWLEDGE_LOOKUP;routes=VECTOR;fallback=false");
        assertThat(RouteActivationStats.snapshot())
                .containsOnlyKeys("intent=KNOWLEDGE_LOOKUP;routes=VECTOR;fallback=false")
                .containsValue(1L);
    }

    @Test
    void resetClearsEverything() {
        RouteActivationStats.record("intent=NO_RAG;routes=none;fallback=false");
        RouteActivationStats.reset();
        assertThat(RouteActivationStats.snapshot()).isEmpty();
    }
}
