package com.pppp.zhimesh.common.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.pppp.zhimesh.common.enums.ModelHealthStatus;
import org.apache.commons.lang3.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 基于真实调用结果的模型健康状态服务。
 * <p>
 * 本服务不主动发送探测请求。只有用户真实调用的成功或失败会更新短期状态，
 * 因而不会额外消耗供应商额度。不同平台下的同名模型分别统计。
 * </p>
 */
@Slf4j
@Service
public class ModelHealthService {

    static final int FAILURE_THRESHOLD = 5;

    /**
     * 模型健康缓存：key = platform::modelName，2 分钟过期。
     * 短暂网络故障不应让模型在整个长批次中持续不可用。
     * <p>
     * Model health cache: key = platform::modelName, expires after 2 minutes.
     * </p>
     */
    private final Cache<String, HealthCheckResult> healthCache = CacheBuilder.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .build();

    /**
     * 查询模型健康状态
     * <p>
     * 未发生过真实调用失败的模型默认为 HEALTHY。
     * </p>
     *
     * @param platform 平台名称 / Platform name
     * @param modelName 模型名称 / Model name
     * @return 健康状态 / Health status
     */
    public ModelHealthStatus getStatus(String platform, String modelName) {
        return getResult(platform, modelName).getStatus();
    }

    public HealthCheckResult getResult(String platform, String modelName) {
        HealthCheckResult result = healthCache.getIfPresent(cacheKey(platform, modelName));
        if (result != null) {
            return result;
        }
        return HealthCheckResult.builder()
                .status(ModelHealthStatus.HEALTHY)
                .consecutiveFailures(0)
                .lastCheckTime(0)
                .build();
    }

    /**
     * 模型是否健康（供 LLMContext 调用）
     * <p>
     * Check if model is healthy (for LLMContext filtering).
     * Models default to healthy before the first real invocation result.
     * </p>
     *
     * @param platform 平台名称 / Platform name
     * @param modelName 模型名称 / Model name
     * @return true if healthy, false if confirmed unhealthy
     */
    public boolean isHealthy(String platform, String modelName) {
        return getStatus(platform, modelName) != ModelHealthStatus.UNHEALTHY;
    }

    /**
     * Record a failed invocation for a model, triggered when a user's real
     * LLM call fails (network error, API error, etc.).
     * <p>
     * After {@link #FAILURE_THRESHOLD} consecutive failures without a
     * successful real invocation, the model is marked UNHEALTHY.
     * </p>
     *
     * @param platform   Platform name
     * @param modelName  Model name
     * @param failReason Failure reason (exception message)
     */
    public void recordFailure(String platform, String modelName, String failReason) {
        String key = cacheKey(platform, modelName);
        HealthCheckResult previous = healthCache.getIfPresent(key);
        int consecutiveFailures = (previous != null ? previous.getConsecutiveFailures() : 0) + 1;
        ModelHealthStatus status;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            status = ModelHealthStatus.UNHEALTHY;
            log.warn("Model {} on {} marked UNHEALTHY after {} consecutive failures, reason:{}",
                    modelName, platform, consecutiveFailures, summarizeFailure(failReason));
        } else {
            status = previous != null ? previous.getStatus() : ModelHealthStatus.HEALTHY;
            log.info("Model {} on {} invocation failed ({} of {}), reason:{}",
                    modelName, platform, consecutiveFailures, FAILURE_THRESHOLD, summarizeFailure(failReason));
        }
        healthCache.put(key, HealthCheckResult.builder()
                .status(status)
                .consecutiveFailures(consecutiveFailures)
                .lastCheckTime(System.currentTimeMillis())
                .failReason(summarizeFailure(failReason))
                .build());
    }

    /**
     * Record a successful real invocation.
     * Any successful response proves that the model has recovered, so stale
     * failure counters must be cleared immediately instead of waiting for the
     * next scheduled probe or cache expiration.
     *
     * @param platform platform name
     * @param modelName model name
     */
    public void recordSuccess(String platform, String modelName) {
        String key = cacheKey(platform, modelName);
        HealthCheckResult previous = healthCache.getIfPresent(key);
        if (previous == null || previous.getConsecutiveFailures() == 0) {
            return;
        }
        healthCache.put(key, HealthCheckResult.builder()
                .status(ModelHealthStatus.HEALTHY)
                .consecutiveFailures(0)
                .lastCheckTime(System.currentTimeMillis())
                .build());
        log.info("Model {} on {} recovered after a successful invocation", modelName, platform);
    }

    /**
     * Keep the provider's useful response compact without retaining a stack trace.
     */
    private String summarizeFailure(String error) {
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(error, "Invocation failed"), 500);
    }

    private String cacheKey(String platform, String modelName) {
        return StringUtils.defaultString(platform) + "::" + StringUtils.defaultString(modelName);
    }

    // ==================== 内部类 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthCheckResult {
        /**
         * 健康状态 / Health status
         */
        private ModelHealthStatus status;
        /**
         * 连续失败次数 / Consecutive failure count
         */
        private int consecutiveFailures;
        /**
         * 上次真实调用结果时间（毫秒时间戳）/ Last real invocation result time
         */
        private long lastCheckTime;
        /**
         * 失败原因 / Failure reason
         */
        private String failReason;
    }
}
