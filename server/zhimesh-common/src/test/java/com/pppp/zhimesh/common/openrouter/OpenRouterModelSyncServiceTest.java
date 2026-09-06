package com.pppp.zhimesh.common.openrouter;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCatalogModel;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterEndpointMetrics;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterProbeResult;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterSyncDecision;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterSyncPlan;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.service.OpenRouterModelStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterModelSyncServiceTest {

    private ZhiMeshProperties properties;
    private AiModelService aiModelService;
    private OpenRouterModelStateService stateService;
    private OpenRouterModelSyncService service;
    private ModelPlatform platform;
    private OpenRouterClient.Session session;

    @BeforeEach
    void setUp() {
        properties = new ZhiMeshProperties();
        properties.getOpenrouterSync().setProbeIntervalMs(0);
        aiModelService = mock(AiModelService.class);
        stateService = mock(OpenRouterModelStateService.class);
        OpenRouterEligibilityService eligibilityService = new OpenRouterEligibilityService(properties);
        service = new OpenRouterModelSyncService(properties, null, aiModelService, stateService,
                null, null, eligibilityService, null, null, null, null);
        platform = new ModelPlatform();
        platform.setName("OpenRouter");
        session = mock(OpenRouterClient.Session.class);
        when(stateService.listByPlatform("OpenRouter")).thenReturn(List.of());
    }

    @Test
    void rateLimitStopsFurtherProbesAndPreservesUnprobedExistingModels() {
        AiModel first = existing("vendor/a:free", true, true);
        AiModel second = existing("vendor/b:free", true, true);
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(List.of(first, second));
        OpenRouterCatalogModel firstCatalog = catalog(first.getName());
        OpenRouterCatalogModel secondCatalog = catalog(second.getName());
        when(session.listEndpoints(firstCatalog)).thenReturn(List.of(endpoint(100)));
        when(session.listEndpoints(secondCatalog)).thenReturn(List.of(endpoint(200)));
        when(session.probe(firstCatalog)).thenReturn(probe("RATE_LIMITED", 429));

        OpenRouterSyncPlan plan = service.buildPlan(platform, session, List.of(firstCatalog, secondCatalog));

        assertThat(plan.isRateLimited()).isTrue();
        assertThat(plan.getProbedCount()).isEqualTo(1);
        assertThat(plan.getDecisions()).allMatch(decision -> "KEEP".equals(decision.getAction()));
        verify(session, times(1)).probe(any());
    }

    @Test
    void doesNotTakeOwnershipOfPaidOpenRouterModels() {
        // Existing OpenRouter rows are validated too: a non-:free model is
        // automatically disabled unless it is explicitly protected.
        AiModel paid = existing("vendor/paid", true, true);
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(List.of(paid));

        OpenRouterSyncPlan plan = service.buildPlan(platform, session, List.of(catalog("vendor/paid")));

        OpenRouterSyncDecision decision = plan.getDecisions().get(0);
        assertThat(decision.isManaged()).isTrue();
        assertThat(decision.getAction()).isEqualTo("DISABLE");
        verify(session, never()).probe(any());
    }

    @Test
    void classifiesEndpointAndProbeFailuresWithoutChurningOnTransientMetadataErrors() {
        // A definitive failure is retained when it is the last configured
        // free model; removal is deferred until replacements are verified.
        assertThat(decisionForEndpointFailure(404).getAction()).isEqualTo("KEEP");
        assertThat(decisionForEndpointFailure(500).getAction()).isEqualTo("KEEP");
        assertThat(decisionForEndpointFailure(0).getAction()).isEqualTo("KEEP");
        assertThatThrownBy(() -> decisionForEndpointFailure(401))
                .isInstanceOf(OpenRouterClientException.class);
        assertThatThrownBy(() -> decisionForEndpointFailure(403))
                .isInstanceOf(OpenRouterClientException.class);
        OpenRouterSyncDecision rateLimited = decisionForEndpointFailure(429);
        assertThat(rateLimited.getAction()).isEqualTo("KEEP");
    }

    @Test
    void preservesTheLastFreeModelUntilAReplacementIsReady() {
        AiModel existing = existing("vendor/unavailable:free", true, true);
        OpenRouterCatalogModel catalog = catalog(existing.getName());
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(List.of(existing));
        when(session.listEndpoints(catalog)).thenReturn(List.of(endpoint(100)));
        when(session.probe(catalog)).thenReturn(probe("FAILED", 500));

        OpenRouterSyncPlan plan = service.buildPlan(platform, session, List.of(catalog));

        assertThat(decisionsByName(plan).get(existing.getName()).getAction()).isEqualTo("KEEP");
    }

    @Test
    void disablesAFailedModelWhenTheSafetyFloorStillHasHealthyModels() {
        List<AiModel> existingModels = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> existing("vendor/existing-" + index + ":free", true, true))
                .toList();
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(existingModels);
        List<OpenRouterCatalogModel> catalog = existingModels.stream()
                .map(model -> catalog(model.getName()))
                .toList();
        for (int index = 0; index < catalog.size(); index++) {
            OpenRouterCatalogModel model = catalog.get(index);
            when(session.listEndpoints(model)).thenReturn(List.of(endpoint(100)));
            when(session.probe(model)).thenReturn(index == 0
                    ? probe("FAILED", 500) : probe("SUCCESS", 200));
        }

        OpenRouterSyncPlan plan = service.buildPlan(platform, session, catalog);

        assertThat(decisionsByName(plan).get(existingModels.get(0).getName()).getAction())
                .isEqualTo("DISABLE");
    }

    @Test
    void doesNotDiscoverWhenExistingVerifiedFreeModelsAlreadyReachTheMinimum() {
        List<AiModel> existingModels = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> existing("vendor/existing-" + index + ":free", true, true))
                .toList();
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(existingModels);
        List<OpenRouterCatalogModel> catalog = existingModels.stream()
                .map(model -> catalog(model.getName()))
                .toList();
        for (OpenRouterCatalogModel model : catalog) {
            when(session.listEndpoints(model)).thenReturn(List.of(endpoint(100)));
            when(session.probe(model)).thenReturn(probe("SUCCESS", 200));
        }
        OpenRouterCatalogModel newModel = catalog("vendor/new:free");

        OpenRouterSyncPlan plan = service.buildPlan(platform, session,
                java.util.stream.Stream.concat(catalog.stream(), java.util.stream.Stream.of(newModel)).toList());

        assertThat(plan.getAvailableAfterSync()).isEqualTo(10);
        assertThat(plan.getDecisions()).noneMatch(decision -> "ADD_ENABLE".equals(decision.getAction()));
        verify(session, never()).listEndpoints(newModel);
        verify(session, never()).probe(newModel);
    }

    @Test
    void fillsOnlyTheShortfallAndReenablesPreviouslyDisabledModels() {
        List<AiModel> existingModels = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> existing("vendor/existing-" + index + ":free", index != 8, true))
                .toList();
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(existingModels);
        List<OpenRouterCatalogModel> existingCatalog = existingModels.stream()
                .map(model -> catalog(model.getName()))
                .toList();
        for (OpenRouterCatalogModel model : existingCatalog) {
            when(session.listEndpoints(model)).thenReturn(List.of(endpoint(100)));
            when(session.probe(model)).thenReturn(probe("SUCCESS", 200));
        }
        OpenRouterCatalogModel slowNew = catalog("vendor/slow-new:free");
        OpenRouterCatalogModel fastNew = catalog("vendor/fast-new:free");
        when(session.listEndpoints(slowNew)).thenReturn(List.of(endpoint(1500)));
        when(session.listEndpoints(fastNew)).thenReturn(List.of(endpoint(100)));
        when(session.probe(fastNew)).thenReturn(probe("SUCCESS", 200));
        when(session.probe(slowNew)).thenReturn(probe("SUCCESS", 200));

        OpenRouterSyncPlan plan = service.buildPlan(platform, session,
                java.util.stream.Stream.of(existingCatalog, List.of(slowNew, fastNew))
                        .flatMap(List::stream).toList());

        assertThat(plan.getAvailableAfterSync()).isEqualTo(10);
        assertThat(decisionsByName(plan).get(existingModels.get(8).getName()).getAction())
                .isEqualTo("UPDATE_ENABLE");
        assertThat(decisionsByName(plan).get(fastNew.getId()).getAction()).isEqualTo("ADD_ENABLE");
        assertThat(decisionsByName(plan).get(slowNew.getId()).getAction())
                .isEqualTo("DISCOVER_ONLY");
        verify(session, never()).probe(slowNew);
    }

    @Test
    void coalescesRateLimitedRecoveryIntoOneDelayedTask() {
        properties.getOpenrouterSync().setRateLimitRetryEnabled(true);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ReflectionTestUtils.setField(service, "taskScheduler", scheduler);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        service.scheduleRateLimitedRecovery();
        service.scheduleRateLimitedRecovery();

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void allowsOnlyOneBackoffRetryForARateLimitedCatalogRecovery() {
        OpenRouterSyncPlan rateLimited = OpenRouterSyncPlan.builder().rateLimited(true).build();
        OpenRouterSyncRun firstAttempt = new OpenRouterSyncRun();
        firstAttempt.setTriggerType("STARTUP_RECOVERY");
        OpenRouterSyncRun retry = new OpenRouterSyncRun();
        retry.setTriggerType("HEALTH_CHECK_RECOVERY");

        assertThat(service.shouldScheduleOneRateLimitRetry(rateLimited, firstAttempt, 4)).isTrue();
        assertThat(service.shouldScheduleOneRateLimitRetry(rateLimited, retry, 4)).isFalse();
        assertThat(service.shouldScheduleOneRateLimitRetry(rateLimited, firstAttempt, 5)).isFalse();
    }

    @Test
    void rejectsAnOverlongProviderIdInsteadOfTruncatingTheRuntimeIdentity() {
        String overlongId = "vendor/" + "x".repeat(260) + ":free";
        OpenRouterCatalogModel model = catalog(overlongId);
        OpenRouterSyncPlan plan = service.buildPlan(platform, session, List.of(model));

        assertThat(plan.getDecisions()).singleElement()
                .extracting(OpenRouterSyncDecision::getAction).isEqualTo("DISCOVER_ONLY");
        assertThat(plan.getDecisions()).singleElement()
                .extracting(OpenRouterSyncDecision::getReason)
                .asString().contains("database identity limit");
    }

    private OpenRouterSyncDecision decisionForEndpointFailure(int status) {
        AiModel existing = existing("vendor/endpoint:free", true, true);
        OpenRouterCatalogModel catalog = catalog(existing.getName());
        when(aiModelService.listByPlatform("OpenRouter")).thenReturn(List.of(existing));
        doThrow(new OpenRouterClientException(status, "HTTP_" + status, "endpoint lookup failed"))
                .when(session).listEndpoints(catalog);
        OpenRouterSyncPlan plan = service.buildPlan(platform, session, List.of(catalog));
        return decisionsByName(plan).get(existing.getName());
    }

    private Map<String, OpenRouterSyncDecision> decisionsByName(OpenRouterSyncPlan plan) {
        return plan.getDecisions().stream()
                .collect(Collectors.toMap(OpenRouterSyncDecision::getModelName, Function.identity()));
    }

    private AiModel existing(String name, boolean enabled, boolean free) {
        AiModel model = new AiModel();
        model.setId((long) Math.abs(name.hashCode()));
        model.setName(name);
        model.setTitle(name);
        model.setPlatform("OpenRouter");
        model.setIsEnable(enabled);
        model.setIsFree(free);
        return model;
    }

    private OpenRouterCatalogModel catalog(String id) {
        return OpenRouterCatalogModel.builder()
                .id(id)
                .displayName(id)
                .contextLength(16384)
                .topProviderContextLength(16384)
                .inputModalities(List.of("text"))
                .outputModalities(List.of("text"))
                .supportedParameters(List.of("max_tokens"))
                .pricing(zeroPricing())
                .build();
    }

    private OpenRouterEndpointMetrics endpoint(int latency) {
        return OpenRouterEndpointMetrics.builder()
                .contextLength(16384)
                .latencyP50Ms(latency)
                .throughputP50(BigDecimal.valueOf(30))
                .uptime1d(BigDecimal.valueOf(99.9))
                .pricing(zeroPricing())
                .build();
    }

    private OpenRouterProbeResult probe(String status, int httpStatus) {
        return OpenRouterProbeResult.builder()
                .status(status)
                .httpStatus(httpStatus)
                .ttftMs(status.equals("FAILED") ? null : 100)
                .totalLatencyMs(200)
                .errorCode(status)
                .errorMessage(status)
                .build();
    }

    private Map<String, String> zeroPricing() {
        return Map.of("prompt", "0", "completion", "0", "request", "0");
    }
}
