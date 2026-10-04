package com.pppp.zhimesh.common.rag.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 意图路由激活统计（B3 数据插桩，调参前先有账）：每条消息的路由决策在
 * {@link IntentRoutingPolicy#plan} 记一次（意图 + 激活路由 + 是否兜底），
 * 累计满 {@link #SUMMARY_EVERY} 次输出一行 INFO 汇总并清零。RAGAS 实测回退率 92%
 * 之后再调回退宽度/阈值时，以此对账而不是靠猜；进程内计数器，无持久化、无配置。
 * <p>
 * Intent-route activation stats (B3 instrumentation, ledger before tuning):
 * every message's routing decision is recorded once in
 * {@link IntentRoutingPolicy#plan} (intent + activated routes + fallback
 * flag); every {@link #SUMMARY_EVERY} decisions one INFO summary line is
 * logged and the counters reset. When the RAGAS-measured 92% fallback rate
 * calls for retuning the fallback width/thresholds, reconcile against this
 * instead of guessing. In-process counters: no persistence, no configuration.
 */
final class RouteActivationStats {

    private static final Logger log = LoggerFactory.getLogger(RouteActivationStats.class);

    /** 汇总输出粒度：每 N 次路由决策打一行并清零 / Summary granularity: one line per N decisions */
    static final int SUMMARY_EVERY = 100;

    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final LongAdder TOTAL = new LongAdder();

    private RouteActivationStats() {
    }

    static void record(String key) {
        COUNTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        TOTAL.increment();
        if (TOTAL.sum() >= SUMMARY_EVERY) {
            // 到达汇总边界：输出后清零；并发越界的线程最多多打一行部分计数
            // Summary boundary: log then reset; a thread racing past the
            // boundary logs at most one extra partial line
            log.info("INTENT_ROUTE_STATS decisions:{} {}", TOTAL.sum(), snapshot());
            reset();
        }
    }

    /** 当前窗口的计数快照（按 key 字典序），测试与日志共用 */
    static Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        COUNTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.put(entry.getKey(), entry.getValue().sum()));
        return out;
    }

    static void reset() {
        COUNTS.clear();
        TOTAL.reset();
    }
}
