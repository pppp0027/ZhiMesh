package com.pppp.zhimesh.common.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.OpenRouterModelState;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterModelHealthCheck;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCandidate;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCatalogModel;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterEndpointMetrics;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterProbeResult;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterSyncDecision;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterSyncPlan;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.service.ModelPlatformService;
import com.pppp.zhimesh.common.service.OpenRouterModelStateService;
import com.pppp.zhimesh.common.service.OpenRouterSyncRunService;
import com.pppp.zhimesh.common.util.RedisTemplateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenRouterModelSyncService {

    private static final String LOCK_KEY = "zhimesh:job:openrouter-model-sync";
    private static final String RATE_LIMIT_COOLDOWN_KEY = "zhimesh:job:openrouter-model-sync:rate-limit-cooldown";
    private static final int LOCK_SECONDS = 1800;

    private final ZhiMeshProperties properties;
    private final ModelPlatformService platformService;
    private final AiModelService aiModelService;
    private final OpenRouterModelStateService stateService;
    private final OpenRouterSyncRunService runService;
    private final OpenRouterClient client;
    private final OpenRouterEligibilityService eligibilityService;
    private final OpenRouterSyncPersistenceService persistenceService;
    private final RedisTemplateUtil redisTemplateUtil;
    private final AsyncTaskExecutor backgroundExecutor;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean delayedRecoveryScheduled = new AtomicBoolean();

    public OpenRouterModelSyncService(ZhiMeshProperties properties,
                                      ModelPlatformService platformService,
                                      AiModelService aiModelService,
                                      OpenRouterModelStateService stateService,
                                      OpenRouterSyncRunService runService,
                                      OpenRouterClient client,
                                      OpenRouterEligibilityService eligibilityService,
                                      OpenRouterSyncPersistenceService persistenceService,
                                      RedisTemplateUtil redisTemplateUtil,
                                      @Qualifier("backgroundExecutor") AsyncTaskExecutor backgroundExecutor,
                                      @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.properties = properties;
        this.platformService = platformService;
        this.aiModelService = aiModelService;
        this.stateService = stateService;
        this.runService = runService;
        this.client = client;
        this.eligibilityService = eligibilityService;
        this.persistenceService = persistenceService;
        this.redisTemplateUtil = redisTemplateUtil;
        this.backgroundExecutor = backgroundExecutor;
        this.taskScheduler = taskScheduler;
    }

    public synchronized OpenRouterSyncRun queueManual() {
        return queueAsync("MANUAL");
    }

    public synchronized OpenRouterSyncRun queueHealthCheck() {
        requireEnabled();
        if (!properties.getOpenrouterSync().isHealthCheckEnabled()) {
            return null;
        }
        if (isRateLimitCooldownActive()) {
            log.info("Skipping OpenRouter health check because the provider cooldown is active");
            return null;
        }
        expireStaleRuns();
        OpenRouterSyncRun active = runService.active();
        if (active != null) {
            return active;
        }
        OpenRouterSyncRun run = runService.create("HEALTH_CHECK", "QUEUED");
        try {
            backgroundExecutor.execute(() -> executeHealthCheck(run.getUuid()));
        } catch (RejectedExecutionException error) {
            runService.markTerminal(run.getUuid(), "FAILED", "EXECUTOR_REJECTED",
                    "OpenRouter health-check background executor rejected the task");
        }
        return runService.getByUuid(run.getUuid());
    }

    private OpenRouterSyncRun queueAsync(String triggerType) {
        requireEnabled();
        expireStaleRuns();
        OpenRouterSyncRun active = runService.active();
        if (active != null) {
            return active;
        }
        OpenRouterSyncRun run = runService.create(triggerType, "QUEUED");
        try {
            backgroundExecutor.execute(() -> execute(run.getUuid()));
        } catch (RejectedExecutionException error) {
            runService.markTerminal(run.getUuid(), "FAILED", "EXECUTOR_REJECTED",
                    "OpenRouter sync background executor rejected the task");
        }
        return runService.getByUuid(run.getUuid());
    }

    public synchronized OpenRouterSyncRun queueScheduled(String triggerType) {
        return queueAsync(triggerType);
    }

    public int expireStaleRuns() {
        int expired = runService.expireStaleActiveRuns(
                properties.getOpenrouterSync().getActiveRunTimeoutMinutes());
        if (expired > 0) {
            log.warn("Recovered {} stale OpenRouter task audit row(s)", expired);
        }
        return expired;
    }

    public int enabledFreeTextModelCount() {
        return countEnabledFreeTextModels(properties.getOpenrouterSync().getPlatformName());
    }

    public boolean needsInventoryRecovery() {
        return enabledFreeTextModelCount()
                < properties.getOpenrouterSync().getHealthCheckMinActiveModels();
    }

    private void executeHealthCheck(String runUuid) {
        boolean locked = false;
        boolean recoveryNeeded = false;
        boolean recoveryRateLimited = false;
        ModelPlatform platform = null;
        try {
            locked = redisTemplateUtil.lock(LOCK_KEY, runUuid, LOCK_SECONDS);
            if (!locked) {
                runService.markTerminal(runUuid, "SKIPPED_LOCKED", "LOCKED",
                        "Another OpenRouter synchronization is already running");
                return;
            }
            runService.markRunning(runUuid);
            if (isRateLimitCooldownActive()) {
                runService.markTerminal(runUuid, "SKIPPED_RATE_LIMIT_COOLDOWN", "RATE_LIMIT_COOLDOWN",
                        "OpenRouter probe cooldown is still active");
                return;
            }
            String platformName = properties.getOpenrouterSync().getPlatformName();
            platform = platformService.getByName(platformName);
            if (platform == null) {
                throw new OpenRouterClientException("PLATFORM_MISSING",
                        "Model platform not found: " + platformName, null);
            }
            if (!Boolean.TRUE.equals(platform.getIsOpenaiApiCompatible())) {
                throw new OpenRouterClientException("PLATFORM_INCOMPATIBLE",
                        "OpenRouter platform is not marked OpenAI-compatible", null);
            }

            List<AiModel> models = enabledFreeTextModels(platformName);
            OpenRouterClient.Session session = client.openSession(platform);
            session.validateCurrentKey();
            List<OpenRouterModelHealthCheck> checks = new ArrayList<>();
            boolean rateLimited = false;
            long previousProbeStarted = 0;
            int maxProbes = properties.getOpenrouterSync().getHealthCheckMaxProbesPerRun();
            List<AiModel> rotatedModels = new ArrayList<>(models);
            if (!rotatedModels.isEmpty()) {
                int offset = (int) Math.floorMod(LocalDate.now().toEpochDay(), rotatedModels.size());
                Collections.rotate(rotatedModels, -offset);
            }
            for (AiModel model : rotatedModels.stream().limit(maxProbes).toList()) {
                previousProbeStarted = waitForProbeWindow(previousProbeStarted);
                OpenRouterProbeResult probe = session.probe(healthCatalog(model));
                if (probe.isRateLimited()) {
                    if (probe.isUpstreamRateLimited()) {
                        // Shared free-pool congestion is per-model and says
                        // nothing about the account or this model's health;
                        // skip it and keep probing the rotation.
                        continue;
                    }
                    rateLimited = true;
                    markRateLimitCooldown();
                    break;
                }
                boolean healthy = isProbeHealthy(probe);
                checks.add(OpenRouterModelHealthCheck.builder()
                        .model(model)
                        .probe(probe)
                        .healthy(healthy)
                        .reason(healthReason(probe, healthy))
                        .build());
            }

            int projectedAvailable = models.size()
                    - (int) checks.stream().filter(check -> !check.isHealthy()).count();
            if (projectedAvailable < properties.getOpenrouterSync().getHealthCheckMinActiveModels()) {
                // Keep the last runtime snapshot available until the full
                // catalog run can discover replacements. That run applies
                // additions, recoveries and removals in one transaction.
                persistenceService.completeDeferredHealthCheck(
                        runUuid, platformName, checks, rateLimited, projectedAvailable);
                if (rateLimited) {
                    log.warn("OpenRouter health check found only {} usable free models and was rate limited; queueing one guarded recovery discovery attempt",
                            projectedAvailable);
                }
                recoveryRateLimited = rateLimited;
                log.warn("OpenRouter health check projected only {} usable free models; deferring removals and queueing full catalog recovery",
                        projectedAvailable);
                recoveryNeeded = true;
            } else {
                persistenceService.applyHealthChecks(runUuid, platformName, checks, rateLimited);
                aiModelService.init();
            }
        } catch (Exception error) {
            if (error instanceof OpenRouterClientException clientError
                    && clientError.getHttpStatus() == 429) {
                markRateLimitCooldown();
            }
            String code = error instanceof OpenRouterClientException clientError
                    ? clientError.getErrorCode() : "HEALTH_CHECK_FAILED";
            runService.markTerminal(runUuid, "FAILED", code, sanitize(error.getMessage(), platform));
            log.error("OpenRouter health check {} failed, code:{}", runUuid, code, error);
        } finally {
            if (locked) {
                boolean released = redisTemplateUtil.unlock(LOCK_KEY, runUuid);
                if (!released) {
                    log.warn("OpenRouter health check lock was not owned when releasing, run:{}", runUuid);
                }
            }
            if (recoveryNeeded) {
                if (recoveryRateLimited) {
                    log.warn("OpenRouter recovery is deferred until the next scheduled window because the health check was rate limited");
                } else {
                    queueAsync("HEALTH_CHECK_RECOVERY");
                }
            }
        }
    }

    void scheduleRateLimitedRecovery() {
        if (!properties.getOpenrouterSync().isRateLimitRetryEnabled()) {
            log.warn("OpenRouter rate-limit retry is disabled; recovery will wait for the next scheduled run");
            return;
        }
        if (!delayedRecoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        Instant runAt = Instant.now().plus(
                properties.getOpenrouterSync().getRateLimitBackoffMinutes(), ChronoUnit.MINUTES);
        try {
            taskScheduler.schedule(this::runDelayedRecovery, runAt);
            log.warn("Scheduled OpenRouter recovery after rate-limit backoff at {}", runAt);
        } catch (RuntimeException error) {
            delayedRecoveryScheduled.set(false);
            log.error("Could not schedule OpenRouter rate-limit recovery", error);
        }
    }

    private void runDelayedRecovery() {
        delayedRecoveryScheduled.set(false);
        expireStaleRuns();
        if (runService.active() != null) {
            log.info("Deferring OpenRouter rate-limit recovery because another task is active");
            scheduleRateLimitedRecovery();
            return;
        }
        if (isRateLimitCooldownActive()) {
            log.info("Skipping delayed OpenRouter recovery because the provider cooldown is still active");
            return;
        }
        queueAsync("HEALTH_CHECK_RECOVERY");
    }

    private List<AiModel> enabledFreeTextModels(String platformName) {
        return aiModelService.listByPlatform(platformName).stream()
                .filter(model -> Boolean.TRUE.equals(model.getIsEnable()))
                .filter(model -> Boolean.TRUE.equals(model.getIsFree()))
                .filter(model -> "text".equalsIgnoreCase(model.getType()))
                .filter(model -> StringUtils.endsWithIgnoreCase(model.getName(), ":free"))
                .sorted(Comparator.comparing(AiModel::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private int countEnabledFreeTextModels(String platformName) {
        return enabledFreeTextModels(platformName).size();
    }

    private boolean isRateLimitCooldownActive() {
        if (redisTemplateUtil == null) {
            return false;
        }
        String value = redisTemplateUtil.get(RATE_LIMIT_COOLDOWN_KEY);
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            return Instant.now().toEpochMilli() < Long.parseLong(value);
        } catch (NumberFormatException error) {
            log.warn("Ignoring malformed OpenRouter rate-limit cooldown value");
            return false;
        }
    }

    private void markRateLimitCooldown() {
        if (redisTemplateUtil == null) {
            return;
        }
        long backoffSeconds = Math.max(60L,
                properties.getOpenrouterSync().getRateLimitBackoffMinutes() * 60L);
        long until = Instant.now().plusSeconds(backoffSeconds).toEpochMilli();
        redisTemplateUtil.set(RATE_LIMIT_COOLDOWN_KEY, Long.toString(until), backoffSeconds);
        log.warn("OpenRouter rate-limit cooldown active until {}", Instant.ofEpochMilli(until));
    }

    /**
     * Scores a verified candidate without making another provider request.
     * Capability is represented by usable context and reasoning support;
     * speed/stability use the endpoint's recent routing metrics.
     */
    private double discoveryScore(OpenRouterCandidate candidate) {
        OpenRouterEndpointMetrics endpoint = candidate.getEndpoint();
        if (endpoint == null) {
            return Double.NEGATIVE_INFINITY;
        }
        double capability = Math.min(1D,
                eligibilityService.effectiveContextLength(candidate.getCatalogModel()) / 131072D);
        capability = capability * 0.7D + (candidate.isReasoner() ? 0.3D : 0.1D);
        double speed = 1D - Math.min(1D,
                endpoint.getLatencyP50Ms() == null ? 1D : endpoint.getLatencyP50Ms() / 4000D);
        double throughput = endpoint.getThroughputP50() == null ? 0D
                : Math.min(1D, endpoint.getThroughputP50().doubleValue() / 100D);
        double uptime = endpoint.getUptime1d() == null ? 0D
                : Math.min(1D, endpoint.getUptime1d().doubleValue() / 100D);
        return capability * 0.45D + speed * 0.30D + throughput * 0.15D + uptime * 0.10D;
    }

    private OpenRouterCatalogModel healthCatalog(AiModel model) {
        List<String> supportedParameters = new ArrayList<>();
        if (model.getProperties() != null) {
            JsonNode node = model.getProperties().get("supported_parameters");
            if (node != null && node.isArray()) {
                node.elements().forEachRemaining(value -> {
                    if (value.isTextual()) {
                        supportedParameters.add(value.asText());
                    }
                });
            }
        }
        return OpenRouterCatalogModel.builder()
                .id(model.getName())
                .supportedParameters(supportedParameters)
                .build();
    }

    private boolean isProbeHealthy(OpenRouterProbeResult probe) {
        if (!probe.isSuccess() || probe.getTtftMs() == null || probe.getTotalLatencyMs() == null) {
            return false;
        }
        ZhiMeshProperties.OpenRouterSync settings = properties.getOpenrouterSync();
        return probe.getTtftMs() <= settings.getMaxProbeTtftMs()
                && probe.getTotalLatencyMs() <= settings.getMaxProbeTotalMs();
    }

    private String healthReason(OpenRouterProbeResult probe, boolean healthy) {
        if (healthy) {
            return "Lightweight OpenRouter probe passed";
        }
        if (probe.getTtftMs() != null && probe.getTtftMs() > properties.getOpenrouterSync().getMaxProbeTtftMs()
                || probe.getTotalLatencyMs() != null
                && probe.getTotalLatencyMs() > properties.getOpenrouterSync().getMaxProbeTotalMs()) {
            return "Probe exceeded configured TTFT or total latency threshold";
        }
        return StringUtils.defaultIfBlank(probe.getErrorMessage(), "OpenRouter health probe failed");
    }

    public OpenRouterSyncRun runScheduled(String triggerType) {
        requireEnabled();
        OpenRouterSyncRun run = runService.create(triggerType, "RUNNING");
        execute(run.getUuid());
        return runService.getByUuid(run.getUuid());
    }

    private void execute(String runUuid) {
        boolean locked = false;
        boolean scheduleOneRateLimitRetry = false;
        ModelPlatform platform = null;
        try {
            locked = redisTemplateUtil.lock(LOCK_KEY, runUuid, LOCK_SECONDS);
            if (!locked) {
                runService.markTerminal(runUuid, "SKIPPED_LOCKED", "LOCKED",
                        "Another OpenRouter sync is already running");
                return;
            }
            runService.markRunning(runUuid);
            if (isRateLimitCooldownActive()) {
                runService.markTerminal(runUuid, "SKIPPED_RATE_LIMIT_COOLDOWN", "RATE_LIMIT_COOLDOWN",
                        "OpenRouter probe cooldown is still active");
                return;
            }
            String platformName = properties.getOpenrouterSync().getPlatformName();
            platform = platformService.getByName(platformName);
            if (platform == null) {
                throw new OpenRouterClientException("PLATFORM_MISSING",
                        "Model platform not found: " + platformName, null);
            }
            if (!Boolean.TRUE.equals(platform.getIsOpenaiApiCompatible())) {
                throw new OpenRouterClientException("PLATFORM_INCOMPATIBLE",
                        "OpenRouter platform is not marked OpenAI-compatible", null);
            }

            OpenRouterClient.Session session = client.openSession(platform);
            session.validateCurrentKey();
            List<OpenRouterCatalogModel> catalog = session.listUserModels();
            OpenRouterSyncPlan plan = buildPlan(platform, session, catalog);
            if (plan.isRateLimited()) {
                markRateLimitCooldown();
            }
            persistenceService.apply(runUuid, plan);
            try {
                aiModelService.init();
            } catch (RuntimeException refreshError) {
                runService.markTerminal(runUuid, "FAILED", "RUNTIME_REFRESH_FAILED",
                        sanitize(refreshError.getMessage(), platform));
                throw refreshError;
            }
            OpenRouterSyncRun currentRun = runService.getByUuid(runUuid);
            scheduleOneRateLimitRetry = shouldScheduleOneRateLimitRetry(
                    plan, currentRun, countEnabledFreeTextModels(platform.getName()));
        } catch (Exception error) {
            if (error instanceof OpenRouterClientException clientError
                    && clientError.getHttpStatus() == 429) {
                markRateLimitCooldown();
            }
            String code = error instanceof OpenRouterClientException clientError
                    ? clientError.getErrorCode() : "SYNC_FAILED";
            runService.markTerminal(runUuid, "FAILED", code, sanitize(error.getMessage(), platform));
            log.error("OpenRouter model sync {} failed, code:{}", runUuid, code, error);
        } finally {
            if (locked) {
                boolean released = redisTemplateUtil.unlock(LOCK_KEY, runUuid);
                if (!released) {
                    log.warn("OpenRouter model sync lock was not owned when releasing, run:{}", runUuid);
                }
            }
            if (scheduleOneRateLimitRetry) {
                log.warn("OpenRouter catalog synchronization was rate limited below the inventory safety floor; scheduling one delayed recovery attempt");
                scheduleRateLimitedRecovery();
            }
        }
    }

    boolean shouldScheduleOneRateLimitRetry(OpenRouterSyncPlan plan,
                                            OpenRouterSyncRun run,
                                            int enabledFreeModels) {
        boolean alreadyRetried = run != null
                && "HEALTH_CHECK_RECOVERY".equals(run.getTriggerType());
        return plan.isRateLimited() && !alreadyRetried
                && enabledFreeModels
                < properties.getOpenrouterSync().getHealthCheckMinActiveModels();
    }

    OpenRouterSyncPlan buildPlan(ModelPlatform platform,
                                         OpenRouterClient.Session session,
                                         List<OpenRouterCatalogModel> catalog) {
        String platformName = platform.getName();
        Map<String, AiModel> existingModels = aiModelService.listByPlatform(platformName).stream()
                .collect(Collectors.toMap(AiModel::getName, model -> model, (left, right) -> left, LinkedHashMap::new));
        Map<String, OpenRouterModelState> existingStates = stateService.listByPlatform(platformName).stream()
                .collect(Collectors.toMap(OpenRouterModelState::getModelName, state -> state,
                        (left, right) -> left, LinkedHashMap::new));
        Set<String> protectedModels = properties.getOpenrouterSync().getProtectedModels().stream()
                .filter(StringUtils::isNotBlank).map(String::trim).collect(Collectors.toSet());

        Map<String, OpenRouterCatalogModel> catalogById = catalog.stream()
                .collect(Collectors.toMap(OpenRouterCatalogModel::getId, model -> model,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, OpenRouterCandidate> catalogCandidates = new LinkedHashMap<>();
        int freeCount = 0;
        for (OpenRouterCatalogModel catalogModel : catalog) {
            boolean freeVariant = catalogModel.getId().toLowerCase(Locale.ROOT).endsWith(":free");
            if (freeVariant && eligibilityService.isZeroPricing(catalogModel.getPricing())) {
                freeCount++;
            }
            OpenRouterCandidate candidate = eligibilityService.evaluateCatalog(catalogModel);
            candidate.setExistingModel(existingModels.get(catalogModel.getId()));
            catalogCandidates.put(catalogModel.getId(), candidate);
        }

        // Every model already configured under OpenRouter is checked first,
        // including disabled rows. This lets a previously unavailable model
        // recover before any new model is imported.
        Map<String, OpenRouterCandidate> inspected = new LinkedHashMap<>();
        existingModels.keySet().stream()
                .map(catalogCandidates::get)
                .filter(candidate -> candidate != null)
                .forEach(candidate -> inspected.put(candidate.getCatalogModel().getId(), candidate));

        boolean rateLimited = false;
        int probed = 0;
        int availableAfterSync = 0;
        long previousProbeStarted = 0;
        List<AiModel> existingInPriorityOrder = existingModels.values().stream()
                .sorted(Comparator.comparingInt(this::existingProbePriority)
                        .thenComparing(AiModel::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (AiModel existingModel : existingInPriorityOrder) {
            if (probed >= properties.getOpenrouterSync().getMaxProbesPerRun()) {
                break;
            }
            if (isProtected(existingModel.getName(), existingStates.get(existingModel.getName()), protectedModels)) {
                continue;
            }
            OpenRouterCandidate candidate = catalogCandidates.get(existingModel.getName());
            if (candidate == null || !candidate.isCatalogEligible()) {
                continue;
            }
            if (attachEndpoint(candidate, session, platform)) {
                rateLimited = true;
                break;
            }
            if (!candidate.isEndpointEligible()) {
                continue;
            }
            previousProbeStarted = waitForProbeWindow(previousProbeStarted);
            OpenRouterProbeResult probe = probe(candidate, session, platform);
            probed++;
            // An account-level 429 aborts the run; upstream shared-pool
            // congestion only fails this one probe and the run moves on.
            if (probe.isRateLimited() && !probe.isUpstreamRateLimited()) {
                rateLimited = true;
                break;
            }
            if (probe.isSuccess()) {
                availableAfterSync++;
                if (availableAfterSync >= properties.getOpenrouterSync().getMinActiveModels()) {
                    break;
                }
            }
        }

        // Only fill the shortfall. New candidates are endpoint-ranked first,
        // and real probes stop as soon as the configured minimum is reached.
        if (!rateLimited && availableAfterSync < properties.getOpenrouterSync().getMinActiveModels()) {
            List<OpenRouterCandidate> discoveryCandidates = catalogCandidates.values().stream()
                    .filter(candidate -> candidate.getExistingModel() == null)
                    .filter(candidate -> candidate.getCatalogModel().getId()
                            .toLowerCase(Locale.ROOT).endsWith(":free"))
                    .toList();
            for (OpenRouterCandidate candidate : discoveryCandidates) {
                inspected.put(candidate.getCatalogModel().getId(), candidate);
            }
            int deficit = properties.getOpenrouterSync().getMinActiveModels() - availableAfterSync;
            int endpointBudget = Math.min(discoveryCandidates.size(),
                    Math.max(5, deficit + 2));
            for (OpenRouterCandidate candidate : discoveryCandidates.stream()
                    .filter(OpenRouterCandidate::isCatalogEligible)
                    .limit(endpointBudget).toList()) {
                if (attachEndpoint(candidate, session, platform)) {
                    rateLimited = true;
                    break;
                }
            }
            if (!rateLimited) {
                List<OpenRouterCandidate> probeTargets = discoveryCandidates.stream()
                        .filter(OpenRouterCandidate::isEndpointEligible)
                        .sorted(Comparator.comparingDouble(this::discoveryScore).reversed()
                                .thenComparing(candidate -> candidate.getCatalogModel().getId(),
                                        String.CASE_INSENSITIVE_ORDER))
                        .toList();
                for (OpenRouterCandidate candidate : probeTargets) {
                    if (availableAfterSync >= properties.getOpenrouterSync().getMinActiveModels()
                            || probed >= properties.getOpenrouterSync().getMaxProbesPerRun()) {
                        break;
                    }
                    previousProbeStarted = waitForProbeWindow(previousProbeStarted);
                    OpenRouterProbeResult probe = probe(candidate, session, platform);
                    probed++;
                    // Same distinction as above: upstream pool congestion on a
                    // candidate fails that candidate without stopping discovery.
                    if (probe.isRateLimited() && !probe.isUpstreamRateLimited()) {
                        rateLimited = true;
                        break;
                    }
                    if (probe.isSuccess()) {
                        availableAfterSync++;
                    }
                }
            }
        }

        Set<String> identities = new LinkedHashSet<>(inspected.keySet());
        identities.addAll(existingModels.keySet());
        identities.addAll(existingStates.keySet());

        List<OpenRouterSyncDecision> decisions = new ArrayList<>();
        for (String modelName : identities) {
            AiModel existingModel = existingModels.get(modelName);
            OpenRouterModelState existingState = existingStates.get(modelName);
            OpenRouterCandidate candidate = inspected.get(modelName);
            boolean protectedModel = isProtected(modelName, existingState, protectedModels);
            boolean initiallyManaged = existingState != null
                    ? Boolean.TRUE.equals(existingState.getIsManaged())
                    : existingModel != null;
            boolean freeCandidate = candidate != null && candidate.getCatalogModel() != null
                    && candidate.getCatalogModel().getId().toLowerCase(Locale.ROOT).endsWith(":free");
            boolean managed = !protectedModel && (initiallyManaged || freeCandidate);
            decisions.add(decide(platformName, modelName, existingModel, existingState,
                    candidate, catalogById.containsKey(modelName), managed));
        }

        if (availableAfterSync < properties.getOpenrouterSync().getHealthCheckMinActiveModels()) {
            decisions = preserveLastKnownInventory(decisions);
        }

        int eligible = (int) inspected.values().stream()
                .filter(OpenRouterCandidate::isEndpointEligible).count();
        return OpenRouterSyncPlan.builder()
                .platform(platformName)
                .catalogCount(catalog.size())
                .freeCount(freeCount)
                .eligibleCount(eligible)
                .probedCount(probed)
                .targetActiveCount(properties.getOpenrouterSync().getMinActiveModels())
                .availableAfterSync(availableAfterSync)
                .rateLimited(rateLimited)
                .decisions(decisions)
                .build();
    }

    private List<OpenRouterSyncDecision> preserveLastKnownInventory(
            List<OpenRouterSyncDecision> decisions) {
        return decisions.stream().map(decision -> {
            AiModel existing = decision.getExistingModel();
            if (!"DISABLE".equals(decision.getAction()) || existing == null
                    || !Boolean.TRUE.equals(existing.getIsEnable())
                    || !Boolean.TRUE.equals(existing.getIsFree())
                    || !StringUtils.endsWithIgnoreCase(existing.getName(), ":free")) {
                return decision;
            }
            decision.setAction("KEEP");
            decision.setReason("Disable deferred until enough verified free replacement models are ready");
            return decision;
        }).toList();
    }

    private OpenRouterSyncDecision decide(String platform, String modelName,
                                          AiModel existingModel, OpenRouterModelState existingState,
                                          OpenRouterCandidate candidate, boolean presentInCatalog,
                                          boolean managed) {
        String action;
        String reason;
        if (!managed) {
            action = "KEEP";
            reason = "Model is manually protected or outside automatic ownership";
        } else if (candidate == null) {
            action = existingModel == null ? "DISCOVER_ONLY" : "DISABLE";
            reason = presentInCatalog ? "Model is no longer a supported :free candidate"
                    : "Model is missing from the complete account catalog";
        } else if (!candidate.isCatalogEligible()) {
            action = existingModel == null ? "DISCOVER_ONLY" : "DISABLE";
            reason = candidate.getRejectionReason();
        } else if ("ENDPOINT_RATE_LIMITED".equals(candidate.getCatalogStatus())
                || "FREE_COMPATIBLE".equals(candidate.getCatalogStatus())) {
            action = existingModel == null ? "DISCOVER_ONLY" : "KEEP";
            reason = "Endpoint or real probe was not completed because the run was rate limited or the target was reached";
        } else if ("ENDPOINT_LOOKUP_FAILED".equals(candidate.getCatalogStatus())) {
            action = existingModel == null ? "DISCOVER_ONLY" : "KEEP";
            reason = "Endpoint metadata could not be verified; existing state was preserved";
        } else if (!candidate.isEndpointEligible()) {
            action = existingModel == null ? "DISCOVER_ONLY" : "DISABLE";
            reason = candidate.getRejectionReason();
        } else if (candidate.getProbe() == null) {
            action = existingModel == null ? "DISCOVER_ONLY" : "KEEP";
            reason = "Probe was not executed because the run stopped or reached its safety budget";
        } else if (candidate.getProbe().isRateLimited()) {
            action = existingModel == null ? "DISCOVER_ONLY" : "KEEP";
            reason = "OpenRouter rate limited the run; existing state was preserved";
        } else if (!candidate.getProbe().isSuccess()) {
            action = existingModel == null ? "DISCOVER_ONLY" : "DISABLE";
            reason = StringUtils.defaultIfBlank(candidate.getProbe().getErrorMessage(), "Model probe failed");
        } else {
            action = existingModel == null ? "ADD_ENABLE" : "UPDATE_ENABLE";
            reason = "Free catalog, endpoint performance, and server-side probe all passed";
        }
        return OpenRouterSyncDecision.builder()
                .platform(platform)
                .modelName(modelName)
                .action(action)
                .reason(StringUtils.abbreviate(StringUtils.defaultString(reason), 1000))
                .managed(managed)
                .existingModel(existingModel)
                .existingState(existingState)
                .candidate(candidate)
                .build();
    }

    private int existingProbePriority(AiModel model) {
        return Boolean.TRUE.equals(model.getIsEnable()) ? 0 : 1;
    }

    private boolean isProtected(String modelName, OpenRouterModelState state,
                                Set<String> protectedModels) {
        return protectedModels.contains(modelName)
                || state != null && !Boolean.TRUE.equals(state.getIsManaged());
    }

    /**
     * Loads endpoint metadata for one candidate. A 429 stops the current run,
     * while authentication failures still abort the whole run before commit.
     */
    private boolean attachEndpoint(OpenRouterCandidate candidate,
                                   OpenRouterClient.Session session,
                                   ModelPlatform platform) {
        try {
            List<OpenRouterEndpointMetrics> endpoints = session.listEndpoints(candidate.getCatalogModel());
            eligibilityService.attachBestEndpoint(candidate, endpoints);
            return false;
        } catch (OpenRouterClientException error) {
            if (error.getHttpStatus() == 401 || error.getHttpStatus() == 403) {
                throw error;
            }
            if (error.getHttpStatus() == 429) {
                candidate.setEndpointEligible(false);
                candidate.setCatalogStatus("ENDPOINT_RATE_LIMITED");
                candidate.setRejectionReason("OpenRouter rate limited endpoint metadata lookup");
                return true;
            }
            candidate.setEndpointEligible(false);
            candidate.setCatalogStatus(error.getHttpStatus() == 404
                    ? "ENDPOINT_NOT_FOUND" : "ENDPOINT_LOOKUP_FAILED");
            candidate.setRejectionReason(StringUtils.abbreviate(
                    redact(error.getMessage(), platform), 1000));
            return false;
        }
    }

    private OpenRouterProbeResult probe(OpenRouterCandidate candidate,
                                        OpenRouterClient.Session session,
                                        ModelPlatform platform) {
        OpenRouterProbeResult probe = session.probe(candidate.getCatalogModel());
        if (StringUtils.isNotBlank(probe.getErrorMessage())) {
            probe.setErrorMessage(StringUtils.abbreviate(
                    redact(probe.getErrorMessage(), platform), 1000));
        }
        if (probe.isSuccess() && isProbeTooSlow(probe)) {
            probe.setStatus("TOO_SLOW");
            probe.setErrorCode("PROBE_TOO_SLOW");
            probe.setErrorMessage("Probe exceeded configured TTFT or total latency threshold");
        }
        candidate.setProbe(probe);
        return probe;
    }

    private long waitForProbeWindow(long previousProbeStarted) {
        if (previousProbeStarted > 0) {
            long elapsedMs = (System.nanoTime() - previousProbeStarted) / 1_000_000;
            long waitMs = properties.getOpenrouterSync().getProbeIntervalMs() - elapsedMs;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new OpenRouterClientException("INTERRUPTED", "OpenRouter probe sequence was interrupted", error);
                }
            }
        }
        return System.nanoTime();
    }

    private boolean isProbeTooSlow(OpenRouterProbeResult probe) {
        ZhiMeshProperties.OpenRouterSync settings = properties.getOpenrouterSync();
        return probe.getTtftMs() == null || probe.getTtftMs() > settings.getMaxProbeTtftMs()
                || probe.getTotalLatencyMs() == null || probe.getTotalLatencyMs() > settings.getMaxProbeTotalMs();
    }

    private void requireEnabled() {
        if (!properties.getOpenrouterSync().isEnabled()) {
            throw new IllegalStateException("OpenRouter model synchronization is disabled");
        }
    }

    private String sanitize(String message, ModelPlatform platform) {
        String safe = StringUtils.defaultIfBlank(message, "OpenRouter model synchronization failed");
        return StringUtils.abbreviate(redact(safe, platform), 1000);
    }

    private String redact(String message, ModelPlatform platform) {
        String safe = StringUtils.defaultString(message);
        if (platform != null && StringUtils.isNotBlank(platform.getApiKey())) {
            safe = safe.replace(platform.getApiKey(), "[REDACTED]");
        }
        return safe;
    }
}
