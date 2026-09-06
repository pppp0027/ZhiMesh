package com.pppp.zhimesh.common.openrouter;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.OpenRouterModelState;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.mapper.AiModelMapper;
import com.pppp.zhimesh.common.mapper.OpenRouterModelStateMapper;
import com.pppp.zhimesh.common.mapper.OpenRouterSyncRunMapper;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCandidate;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCatalogModel;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterEndpointMetrics;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterModelHealthCheck;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterProbeResult;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterSyncDecision;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterSyncPlan;
import com.pppp.zhimesh.common.util.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class OpenRouterSyncPersistenceService {

    private final AiModelMapper aiModelMapper;
    private final OpenRouterModelStateMapper stateMapper;
    private final OpenRouterSyncRunMapper runMapper;
    private final OpenRouterEligibilityService eligibilityService;

    public OpenRouterSyncPersistenceService(AiModelMapper aiModelMapper,
                                            OpenRouterModelStateMapper stateMapper,
                                            OpenRouterSyncRunMapper runMapper,
                                            OpenRouterEligibilityService eligibilityService) {
        this.aiModelMapper = aiModelMapper;
        this.stateMapper = stateMapper;
        this.runMapper = runMapper;
        this.eligibilityService = eligibilityService;
    }

    @Transactional
    public OpenRouterSyncRun apply(String runUuid, OpenRouterSyncPlan plan) {
        OpenRouterSyncRun run = selectRun(runUuid);
        if (run == null) {
            throw new IllegalStateException("OpenRouter sync run not found: " + runUuid);
        }

        int added = 0;
        int updated = 0;
        int enabled = 0;
        int disabled = 0;
        int skipped = 0;
        ArrayNode changes = JsonUtil.createArrayNode();

        for (OpenRouterSyncDecision decision : plan.getDecisions()) {
            AiModel before = decision.getExistingModel();
            AiModel after = before;
            boolean modelChanged = false;

            if ("ADD_ENABLE".equals(decision.getAction())) {
                after = desiredModel(decision.getCandidate(), plan.getPlatform(), null);
                aiModelMapper.insert(after);
                added++;
                enabled++;
                modelChanged = true;
            } else if ("UPDATE_ENABLE".equals(decision.getAction())) {
                after = desiredModel(decision.getCandidate(), plan.getPlatform(), before.getId());
                if (!sameRuntimeModel(before, after)) {
                    aiModelMapper.updateById(after);
                    updated++;
                    if (!Boolean.TRUE.equals(before.getIsEnable())) {
                        enabled++;
                    }
                    modelChanged = true;
                }
            } else if ("DISABLE".equals(decision.getAction()) && before != null
                    && Boolean.TRUE.equals(before.getIsEnable())) {
                AiModel update = new AiModel();
                update.setId(before.getId());
                update.setIsEnable(false);
                aiModelMapper.updateById(update);
                after = copyWithEnabled(before, false);
                updated++;
                disabled++;
                modelChanged = true;
            } else if (before == null) {
                skipped++;
            }

            upsertState(decision, after);
            if (modelChanged || "DISABLE".equals(decision.getAction())) {
                changes.add(change(decision, before, after));
            }
        }

        ObjectNode summary = JsonUtil.createObjectNode();
        summary.put("platform", plan.getPlatform());
        summary.put("rateLimited", plan.isRateLimited());
        summary.put("targetActiveCount", plan.getTargetActiveCount());
        summary.put("availableAfterSync", plan.getAvailableAfterSync());
        summary.set("changes", changes);

        OpenRouterSyncRun update = new OpenRouterSyncRun();
        update.setId(run.getId());
        update.setStatus(plan.isRateLimited() ? "PARTIAL_RATE_LIMIT" : "SUCCESS");
        update.setCatalogCount(plan.getCatalogCount());
        update.setFreeCount(plan.getFreeCount());
        update.setEligibleCount(plan.getEligibleCount());
        update.setProbedCount(plan.getProbedCount());
        update.setAddedCount(added);
        update.setUpdatedCount(updated);
        update.setEnabledCount(enabled);
        update.setDisabledCount(disabled);
        update.setSkippedCount(skipped);
        update.setCompletedAt(LocalDateTime.now());
        update.setErrorCode("");
        update.setErrorMessage("");
        update.setSummary(summary);
        runMapper.updateById(update);
        return selectRun(runUuid);
    }

    /**
     * Apply a completed lightweight health-check batch atomically. Probes are
     * performed before this method is called, so users keep using the previous
     * runtime model set while the batch is in flight. Unprobed models after a
     * provider rate limit are intentionally left untouched.
     */
    @Transactional
    public OpenRouterSyncRun applyHealthChecks(String runUuid, String platform,
                                               List<OpenRouterModelHealthCheck> checks,
                                               boolean rateLimited) {
        OpenRouterSyncRun run = selectRun(runUuid);
        if (run == null) {
            throw new IllegalStateException("OpenRouter health-check run not found: " + runUuid);
        }

        int updated = 0;
        int enabled = 0;
        int disabled = 0;
        int healthyCount = 0;
        ArrayNode changes = JsonUtil.createArrayNode();
        for (OpenRouterModelHealthCheck check : checks) {
            AiModel before = check.getModel();
            OpenRouterProbeResult probe = check.getProbe();
            OpenRouterModelState state = selectState(platform, before.getName());
            boolean managed = state == null || !Boolean.FALSE.equals(state.getIsManaged());
            boolean modelChanged = false;

            if (check.isHealthy()) {
                healthyCount++;
            }
            if (managed && check.isHealthy() != Boolean.TRUE.equals(before.getIsEnable())) {
                AiModel update = new AiModel();
                update.setId(before.getId());
                update.setIsEnable(check.isHealthy());
                aiModelMapper.updateById(update);
                modelChanged = true;
                updated++;
                if (check.isHealthy()) {
                    enabled++;
                } else {
                    disabled++;
                }
            }

            upsertHealthState(platform, before, state, check, managed);
            if (modelChanged) {
                changes.add(healthChange(before, check));
            }
        }

        ObjectNode summary = JsonUtil.createObjectNode();
        summary.put("type", "LIGHTWEIGHT_HEALTH_CHECK");
        summary.put("platform", platform);
        summary.put("rateLimited", rateLimited);
        summary.put("healthyProbes", healthyCount);
        summary.set("changes", changes);

        OpenRouterSyncRun update = new OpenRouterSyncRun();
        update.setId(run.getId());
        update.setStatus(rateLimited ? "PARTIAL_RATE_LIMIT" : "SUCCESS");
        update.setCatalogCount(0);
        update.setFreeCount(checks.size());
        update.setEligibleCount(healthyCount);
        update.setProbedCount(checks.size());
        update.setAddedCount(0);
        update.setUpdatedCount(updated);
        update.setEnabledCount(enabled);
        update.setDisabledCount(disabled);
        update.setSkippedCount(0);
        update.setCompletedAt(LocalDateTime.now());
        update.setErrorCode("");
        update.setErrorMessage("");
        update.setSummary(summary);
        runMapper.updateById(update);
        return selectRun(runUuid);
    }

    /**
     * Finish the lightweight audit without changing model availability. This
     * path is used when applying probe failures immediately would take the
     * usable inventory below its safety floor; the following full catalog run
     * will commit replacements and removals together.
     */
    @Transactional
    public OpenRouterSyncRun completeDeferredHealthCheck(
            String runUuid, String platform, List<OpenRouterModelHealthCheck> checks,
            boolean rateLimited, int projectedAvailable) {
        OpenRouterSyncRun run = selectRun(runUuid);
        if (run == null) {
            throw new IllegalStateException("OpenRouter health-check run not found: " + runUuid);
        }
        int healthyCount = (int) checks.stream().filter(OpenRouterModelHealthCheck::isHealthy).count();
        ObjectNode summary = JsonUtil.createObjectNode();
        summary.put("type", "LIGHTWEIGHT_HEALTH_CHECK");
        summary.put("platform", platform);
        summary.put("rateLimited", rateLimited);
        summary.put("healthyProbes", healthyCount);
        summary.put("projectedAvailable", projectedAvailable);
        summary.put("recoveryQueued", true);
        summary.put("availabilityChangesDeferred", true);

        OpenRouterSyncRun update = new OpenRouterSyncRun();
        update.setId(run.getId());
        update.setStatus(rateLimited ? "PARTIAL_RATE_LIMIT" : "SUCCESS");
        update.setCatalogCount(0);
        update.setFreeCount(checks.size());
        update.setEligibleCount(healthyCount);
        update.setProbedCount(checks.size());
        update.setAddedCount(0);
        update.setUpdatedCount(0);
        update.setEnabledCount(0);
        update.setDisabledCount(0);
        update.setSkippedCount(0);
        update.setCompletedAt(LocalDateTime.now());
        update.setErrorCode("");
        update.setErrorMessage("");
        update.setSummary(summary);
        runMapper.updateById(update);
        return selectRun(runUuid);
    }

    private void upsertHealthState(String platform, AiModel model, OpenRouterModelState state,
                                   OpenRouterModelHealthCheck check, boolean managed) {
        boolean insert = state == null;
        if (insert) {
            state = new OpenRouterModelState();
            state.setPlatform(platform);
            state.setModelName(model.getName());
            state.setModelId(model.getId());
            state.setIsManaged(true);
        }
        OpenRouterProbeResult probe = check.getProbe();
        state.setLastDecision(!managed || check.isHealthy() ? "KEEP" : "DISABLE");
        state.setDisableReason(StringUtils.abbreviate(StringUtils.defaultString(check.getReason()), 1000));
        if (!managed) {
            state.setLifecycleStatus("PROTECTED");
        } else {
            state.setLifecycleStatus(check.isHealthy() ? "ENABLED" : "DISABLED");
        }
        state.setProbeStatus(probe == null ? "NOT_RUN" : probe.getStatus());
        state.setActualTtftMs(probe == null ? null : probe.getTtftMs());
        state.setActualTotalLatencyMs(probe == null ? null : probe.getTotalLatencyMs());
        state.setLastProbeAt(LocalDateTime.now());
        if (probe != null && probe.isSuccess() && check.isHealthy()) {
            state.setConsecutiveFailures(0);
            state.setLastSuccessAt(LocalDateTime.now());
            state.setLastErrorCode("");
            state.setLastErrorMessage("");
        } else if (probe != null) {
            state.setConsecutiveFailures(Objects.requireNonNullElse(state.getConsecutiveFailures(), 0) + 1);
            state.setLastErrorCode(StringUtils.abbreviate(StringUtils.defaultString(probe.getErrorCode()), 64));
            state.setLastErrorMessage(StringUtils.abbreviate(StringUtils.defaultString(probe.getErrorMessage()), 1000));
            if (managed && !check.isHealthy()) {
                state.setLastDisabledAt(LocalDateTime.now());
            }
        }
        state.setCreateTime(null);
        state.setUpdateTime(null);
        if (insert) {
            stateMapper.insert(state);
        } else {
            stateMapper.updateById(state);
        }
    }

    private OpenRouterModelState selectState(String platform, String modelName) {
        return stateMapper.selectOne(Wrappers.<OpenRouterModelState>lambdaQuery()
                .eq(OpenRouterModelState::getPlatform, platform)
                .eq(OpenRouterModelState::getModelName, modelName)
                .last("LIMIT 1"));
    }

    private ObjectNode healthChange(AiModel before, OpenRouterModelHealthCheck check) {
        ObjectNode node = JsonUtil.createObjectNode();
        node.put("modelId", before.getId());
        node.put("modelName", before.getName());
        node.put("action", check.isHealthy() ? "UPDATE_ENABLE" : "DISABLE");
        node.put("reason", StringUtils.abbreviate(StringUtils.defaultString(check.getReason()), 500));
        ObjectNode beforeNode = node.putObject("before");
        beforeNode.put("exists", true);
        beforeNode.put("enabled", Boolean.TRUE.equals(before.getIsEnable()));
        ObjectNode afterNode = node.putObject("after");
        afterNode.put("exists", true);
        afterNode.put("enabled", check.isHealthy());
        return node;
    }

    private void upsertState(OpenRouterSyncDecision decision, AiModel model) {
        OpenRouterModelState state = decision.getExistingState();
        boolean insert = state == null;
        if (insert) {
            state = new OpenRouterModelState();
            state.setPlatform(decision.getPlatform());
            state.setModelName(decision.getModelName());
        }
        state.setModelId(model == null ? null : model.getId());
        state.setIsManaged(decision.isManaged());
        state.setLastDecision(decision.getAction());
        state.setDisableReason(StringUtils.abbreviate(StringUtils.defaultString(decision.getReason()), 1000));

        OpenRouterCandidate candidate = decision.getCandidate();
        OpenRouterProbeResult probe = candidate == null ? null : candidate.getProbe();
        // A KEEP decision with no completed probe is deliberately non-destructive.
        // This is used after a 429, a temporary endpoint metadata failure, or when
        // the shortfall has already been filled. Do not replace the last known
        // catalog/probe measurements with NOT_RUN/null values in that case.
        if (!insert && "KEEP".equals(decision.getAction()) && probe == null) {
            stateMapper.updateById(state);
            return;
        }
        if (!decision.isManaged()) {
            state.setLifecycleStatus("PROTECTED");
        } else if ("ADD_ENABLE".equals(decision.getAction()) || "UPDATE_ENABLE".equals(decision.getAction())) {
            state.setLifecycleStatus("ENABLED");
        } else if ("DISABLE".equals(decision.getAction())) {
            state.setLifecycleStatus("DISABLED");
            state.setLastDisabledAt(LocalDateTime.now());
        } else if (model == null) {
            state.setLifecycleStatus("DISCOVERED");
        } else {
            state.setLifecycleStatus(Boolean.TRUE.equals(model.getIsEnable()) ? "ENABLED" : "DISABLED");
        }
        state.setCatalogStatus(candidate == null ? "MISSING" : candidate.getCatalogStatus());
        state.setProbeStatus(probe == null ? "NOT_RUN" : probe.getStatus());

        OpenRouterEndpointMetrics endpoint = candidate == null ? null : candidate.getEndpoint();
        state.setCatalogLatencyP50Ms(endpoint == null ? null : endpoint.getLatencyP50Ms());
        state.setCatalogThroughputP50(endpoint == null ? null : endpoint.getThroughputP50());
        state.setCatalogUptime1d(endpoint == null ? null : endpoint.getUptime1d());
        state.setActualTtftMs(probe == null ? null : probe.getTtftMs());
        state.setActualTotalLatencyMs(probe == null ? null : probe.getTotalLatencyMs());
        if (probe != null && probe.isSuccess()) {
            state.setConsecutiveFailures(0);
            state.setLastSuccessAt(LocalDateTime.now());
            state.setLastErrorCode("");
            state.setLastErrorMessage("");
        } else if (probe != null && !probe.isRateLimited()) {
            state.setConsecutiveFailures(Objects.requireNonNullElse(state.getConsecutiveFailures(), 0) + 1);
            state.setLastErrorCode(StringUtils.abbreviate(StringUtils.defaultString(probe.getErrorCode()), 64));
            state.setLastErrorMessage(StringUtils.abbreviate(StringUtils.defaultString(probe.getErrorMessage()), 1000));
        }
        if (candidate != null && candidate.getCatalogModel() != null) {
            state.setLastSeenAt(LocalDateTime.now());
        }
        if (probe != null) {
            state.setLastProbeAt(LocalDateTime.now());
        }
        state.setRawMetadata(rawMetadata(candidate));
        state.setCreateTime(null);
        state.setUpdateTime(null);
        if (insert) {
            stateMapper.insert(state);
        } else {
            stateMapper.updateById(state);
        }
    }

    private AiModel desiredModel(OpenRouterCandidate candidate, String platform, Long id) {
        OpenRouterCatalogModel catalog = candidate.getCatalogModel();
        AiModel model = new AiModel();
        model.setId(id);
        // The exact provider ID is the runtime request identity. Eligibility
        // rejects IDs longer than the database column instead of truncating it.
        model.setName(catalog.getId());
        model.setTitle(truncate(StringUtils.defaultIfBlank(catalog.getDisplayName(), catalog.getId()), 255));
        model.setType(candidate.getModelType());
        model.setRemark(truncate(catalog.getDescription(), 1000));
        model.setPlatform(platform);
        model.setMaxInputTokens(eligibilityService.effectiveContextLength(catalog));
        model.setInputTypes(candidate.getInputTypes());
        model.setResponseFormatTypes(candidate.getResponseFormatTypes());
        model.setIsSupportWebSearch(false);
        model.setIsReasoner(candidate.isReasoner());
        model.setIsThinkingClosable(false);
        model.setIsFree(true);
        model.setIsEnable(true);
        ObjectNode properties = JsonUtil.createObjectNode();
        properties.put("managed_by", "openrouter_sync");
        properties.put("canonical_slug", StringUtils.defaultString(catalog.getCanonicalSlug()));
        ArrayNode supported = properties.putArray("supported_parameters");
        if (catalog.getSupportedParameters() != null) {
            catalog.getSupportedParameters().forEach(supported::add);
        }
        model.setProperties(properties);
        return model;
    }

    private boolean sameRuntimeModel(AiModel before, AiModel after) {
        return Objects.equals(before.getName(), after.getName())
                && Objects.equals(before.getTitle(), after.getTitle())
                && Objects.equals(before.getType(), after.getType())
                && Objects.equals(before.getRemark(), after.getRemark())
                && Objects.equals(before.getPlatform(), after.getPlatform())
                && Objects.equals(before.getMaxInputTokens(), after.getMaxInputTokens())
                && Objects.equals(before.getInputTypes(), after.getInputTypes())
                && Objects.equals(before.getResponseFormatTypes(), after.getResponseFormatTypes())
                && Objects.equals(before.getIsSupportWebSearch(), after.getIsSupportWebSearch())
                && Objects.equals(before.getIsReasoner(), after.getIsReasoner())
                && Objects.equals(before.getIsThinkingClosable(), after.getIsThinkingClosable())
                && Objects.equals(before.getIsFree(), after.getIsFree())
                && Objects.equals(before.getIsEnable(), after.getIsEnable())
                && Objects.equals(before.getProperties(), after.getProperties());
    }

    private ObjectNode rawMetadata(OpenRouterCandidate candidate) {
        ObjectNode raw = JsonUtil.createObjectNode();
        if (candidate != null && candidate.getCatalogModel() != null) {
            raw.set("catalog", candidate.getCatalogModel().getRawMetadata());
        }
        if (candidate != null && candidate.getEndpoint() != null) {
            raw.set("endpoint", candidate.getEndpoint().getRawMetadata());
        }
        return raw;
    }

    private ObjectNode change(OpenRouterSyncDecision decision, AiModel before, AiModel after) {
        ObjectNode node = JsonUtil.createObjectNode();
        node.put("modelId", after != null ? after.getId() : before == null ? null : before.getId());
        node.put("modelName", decision.getModelName());
        node.put("action", decision.getAction());
        node.put("reason", StringUtils.abbreviate(StringUtils.defaultString(decision.getReason()), 500));
        ObjectNode beforeNode = node.putObject("before");
        beforeNode.put("exists", before != null);
        beforeNode.put("enabled", before != null && Boolean.TRUE.equals(before.getIsEnable()));
        beforeNode.put("free", before != null && Boolean.TRUE.equals(before.getIsFree()));
        ObjectNode afterNode = node.putObject("after");
        afterNode.put("exists", after != null);
        afterNode.put("enabled", after != null && Boolean.TRUE.equals(after.getIsEnable()));
        afterNode.put("free", after != null && Boolean.TRUE.equals(after.getIsFree()));
        return node;
    }

    private AiModel copyWithEnabled(AiModel source, boolean enabled) {
        AiModel copy = new AiModel();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setTitle(source.getTitle());
        copy.setType(source.getType());
        copy.setRemark(source.getRemark());
        copy.setPlatform(source.getPlatform());
        copy.setMaxInputTokens(source.getMaxInputTokens());
        copy.setInputTypes(source.getInputTypes());
        copy.setResponseFormatTypes(source.getResponseFormatTypes());
        copy.setIsSupportWebSearch(source.getIsSupportWebSearch());
        copy.setIsReasoner(source.getIsReasoner());
        copy.setIsThinkingClosable(source.getIsThinkingClosable());
        copy.setIsFree(source.getIsFree());
        copy.setIsEnable(enabled);
        copy.setProperties(source.getProperties());
        return copy;
    }

    private OpenRouterSyncRun selectRun(String uuid) {
        return runMapper.selectOne(Wrappers.<OpenRouterSyncRun>lambdaQuery()
                .eq(OpenRouterSyncRun::getUuid, uuid)
                .last("LIMIT 1"));
    }

    private String truncate(String value, int maxLength) {
        return StringUtils.abbreviate(StringUtils.defaultString(value), maxLength);
    }
}
