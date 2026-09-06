package com.pppp.zhimesh.common.openrouter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterSyncPersistenceServiceTest {

    @Test
    void keepsPreviousMeasurementsWhenRateLimitLeavesAnExistingModelUnprobed() {
        AiModelMapper modelMapper = mock(AiModelMapper.class);
        OpenRouterModelStateMapper stateMapper = mock(OpenRouterModelStateMapper.class);
        OpenRouterSyncRunMapper runMapper = mock(OpenRouterSyncRunMapper.class);
        OpenRouterEligibilityService eligibility = new OpenRouterEligibilityService(new ZhiMeshProperties());
        OpenRouterSyncPersistenceService service = new OpenRouterSyncPersistenceService(
                modelMapper, stateMapper, runMapper, eligibility);

        OpenRouterSyncRun run = new OpenRouterSyncRun();
        run.setId(11L);
        run.setUuid("run-rate-limited");
        when(runMapper.selectOne(any())).thenReturn(run);

        AiModel existing = new AiModel();
        existing.setId(101L);
        existing.setName("vendor/model:free");
        existing.setPlatform("OpenRouter");
        existing.setIsEnable(true);

        OpenRouterModelState state = new OpenRouterModelState();
        state.setId(201L);
        state.setPlatform("OpenRouter");
        state.setModelName(existing.getName());
        state.setModelId(existing.getId());
        state.setIsManaged(true);
        state.setLifecycleStatus("ENABLED");
        state.setCatalogStatus("ELIGIBLE");
        state.setProbeStatus("SUCCESS");
        state.setCatalogLatencyP50Ms(320);
        state.setCatalogThroughputP50(BigDecimal.valueOf(25));
        state.setCatalogUptime1d(BigDecimal.valueOf(99.7));
        state.setActualTtftMs(410);
        state.setActualTotalLatencyMs(760);
        state.setConsecutiveFailures(0);
        state.setLastErrorCode("");
        state.setLastErrorMessage("");

        OpenRouterCandidate candidate = OpenRouterCandidate.builder()
                .catalogModel(OpenRouterCatalogModel.builder().id(existing.getName()).build())
                .catalogStatus("ENDPOINT_RATE_LIMITED")
                .catalogEligible(true)
                .endpointEligible(false)
                .build();
        OpenRouterSyncDecision decision = OpenRouterSyncDecision.builder()
                .platform("OpenRouter")
                .modelName(existing.getName())
                .action("KEEP")
                .reason("OpenRouter rate limited the run")
                .managed(true)
                .existingModel(existing)
                .existingState(state)
                .candidate(candidate)
                .build();
        OpenRouterSyncPlan plan = OpenRouterSyncPlan.builder()
                .platform("OpenRouter")
                .decisions(List.of(decision))
                .build();

        service.apply(run.getUuid(), plan);

        assertThat(state.getCatalogStatus()).isEqualTo("ELIGIBLE");
        assertThat(state.getProbeStatus()).isEqualTo("SUCCESS");
        assertThat(state.getCatalogLatencyP50Ms()).isEqualTo(320);
        assertThat(state.getActualTtftMs()).isEqualTo(410);
        assertThat(state.getActualTotalLatencyMs()).isEqualTo(760);
        verify(modelMapper, never()).updateById(any(AiModel.class));
        verify(stateMapper).updateById(state);
    }

    @Test
    void doesNotRewriteAnUnchangedRuntimeModel() {
        AiModelMapper modelMapper = mock(AiModelMapper.class);
        OpenRouterModelStateMapper stateMapper = mock(OpenRouterModelStateMapper.class);
        OpenRouterSyncRunMapper runMapper = mock(OpenRouterSyncRunMapper.class);
        OpenRouterEligibilityService eligibility = new OpenRouterEligibilityService(new ZhiMeshProperties());
        OpenRouterSyncPersistenceService service = new OpenRouterSyncPersistenceService(
                modelMapper, stateMapper, runMapper, eligibility);

        OpenRouterSyncRun run = new OpenRouterSyncRun();
        run.setId(10L);
        run.setUuid("run-1");
        when(runMapper.selectOne(any())).thenReturn(run);

        OpenRouterCatalogModel catalog = OpenRouterCatalogModel.builder()
                .id("vendor/model:free")
                .canonicalSlug("vendor/model")
                .displayName("Model Display")
                .description("Model description")
                .contextLength(16384)
                .topProviderContextLength(16384)
                .inputModalities(List.of("text"))
                .outputModalities(List.of("text"))
                .supportedParameters(List.of())
                .pricing(zeroPricing())
                .build();
        OpenRouterCandidate candidate = OpenRouterCandidate.builder()
                .catalogModel(catalog)
                .endpoint(OpenRouterEndpointMetrics.builder()
                        .contextLength(16384)
                        .latencyP50Ms(250)
                        .throughputP50(BigDecimal.valueOf(30))
                        .uptime1d(BigDecimal.valueOf(99.9))
                        .pricing(zeroPricing())
                        .build())
                .probe(OpenRouterProbeResult.builder()
                        .status("SUCCESS")
                        .httpStatus(200)
                        .ttftMs(300)
                        .totalLatencyMs(500)
                        .build())
                .catalogEligible(true)
                .endpointEligible(true)
                .catalogStatus("ELIGIBLE")
                .modelType("text")
                .inputTypes("text")
                .responseFormatTypes("text")
                .build();

        AiModel existing = matchingRuntimeModel(catalog);
        OpenRouterModelState state = new OpenRouterModelState();
        state.setId(20L);
        state.setConsecutiveFailures(0);
        OpenRouterSyncDecision decision = OpenRouterSyncDecision.builder()
                .platform("OpenRouter")
                .modelName(catalog.getId())
                .action("UPDATE_ENABLE")
                .reason("passed")
                .managed(true)
                .existingModel(existing)
                .existingState(state)
                .candidate(candidate)
                .build();
        OpenRouterSyncPlan plan = OpenRouterSyncPlan.builder()
                .platform("OpenRouter")
                .catalogCount(1)
                .freeCount(1)
                .eligibleCount(1)
                .probedCount(1)
                .decisions(List.of(decision))
                .build();

        service.apply(run.getUuid(), plan);

        verify(modelMapper, never()).insert(any(AiModel.class));
        verify(modelMapper, never()).updateById(any(AiModel.class));
        verify(stateMapper, times(1)).updateById(state);
        verify(runMapper, times(1)).updateById(any(OpenRouterSyncRun.class));
    }

    @Test
    void appliesHealthCheckChangesOnlyAfterTheProbeBatchCompletes() {
        AiModelMapper modelMapper = mock(AiModelMapper.class);
        OpenRouterModelStateMapper stateMapper = mock(OpenRouterModelStateMapper.class);
        OpenRouterSyncRunMapper runMapper = mock(OpenRouterSyncRunMapper.class);
        OpenRouterEligibilityService eligibility = new OpenRouterEligibilityService(new ZhiMeshProperties());
        OpenRouterSyncPersistenceService service = new OpenRouterSyncPersistenceService(
                modelMapper, stateMapper, runMapper, eligibility);

        OpenRouterSyncRun run = new OpenRouterSyncRun();
        run.setId(12L);
        run.setUuid("health-run");
        when(runMapper.selectOne(any())).thenReturn(run);

        AiModel model = new AiModel();
        model.setId(102L);
        model.setName("vendor/model:free");
        model.setPlatform("OpenRouter");
        model.setType("text");
        model.setIsFree(true);
        model.setIsEnable(true);

        OpenRouterModelState state = new OpenRouterModelState();
        state.setId(202L);
        state.setPlatform("OpenRouter");
        state.setModelName(model.getName());
        state.setModelId(model.getId());
        state.setIsManaged(true);
        state.setLifecycleStatus("ENABLED");
        state.setConsecutiveFailures(0);
        when(stateMapper.selectOne(any())).thenReturn(state);

        OpenRouterProbeResult probe = OpenRouterProbeResult.builder()
                .status("FAILED")
                .httpStatus(503)
                .totalLatencyMs(800)
                .errorCode("HTTP_503")
                .errorMessage("provider unavailable")
                .build();
        OpenRouterModelHealthCheck check = OpenRouterModelHealthCheck.builder()
                .model(model)
                .probe(probe)
                .healthy(false)
                .reason("provider unavailable")
                .build();

        service.applyHealthChecks(run.getUuid(), "OpenRouter", List.of(check), false);

        verify(modelMapper).updateById(any(AiModel.class));
        assertThat(state.getLifecycleStatus()).isEqualTo("DISABLED");
        assertThat(state.getProbeStatus()).isEqualTo("FAILED");
        assertThat(state.getActualTotalLatencyMs()).isEqualTo(800);
        verify(stateMapper).updateById(state);
        verify(runMapper, times(1)).updateById(any(OpenRouterSyncRun.class));
    }

    @Test
    void defersAvailabilityChangesWhenRecoveryMustPrepareReplacements() {
        AiModelMapper modelMapper = mock(AiModelMapper.class);
        OpenRouterModelStateMapper stateMapper = mock(OpenRouterModelStateMapper.class);
        OpenRouterSyncRunMapper runMapper = mock(OpenRouterSyncRunMapper.class);
        OpenRouterEligibilityService eligibility = new OpenRouterEligibilityService(new ZhiMeshProperties());
        OpenRouterSyncPersistenceService service = new OpenRouterSyncPersistenceService(
                modelMapper, stateMapper, runMapper, eligibility);

        OpenRouterSyncRun run = new OpenRouterSyncRun();
        run.setId(13L);
        run.setUuid("deferred-health-run");
        when(runMapper.selectOne(any())).thenReturn(run);

        AiModel model = new AiModel();
        model.setId(103L);
        model.setName("vendor/last-model:free");
        model.setIsEnable(true);
        OpenRouterModelHealthCheck check = OpenRouterModelHealthCheck.builder()
                .model(model)
                .probe(OpenRouterProbeResult.builder().status("FAILED").httpStatus(503).build())
                .healthy(false)
                .reason("provider unavailable")
                .build();

        service.completeDeferredHealthCheck(
                run.getUuid(), "OpenRouter", List.of(check), false, 0);

        verify(modelMapper, never()).updateById(any(AiModel.class));
        verify(stateMapper, never()).updateById(any(OpenRouterModelState.class));
        verify(stateMapper, never()).insert(any(OpenRouterModelState.class));
        verify(runMapper).updateById(any(OpenRouterSyncRun.class));
    }

    private AiModel matchingRuntimeModel(OpenRouterCatalogModel catalog) {
        AiModel model = new AiModel();
        model.setId(1L);
        model.setName(catalog.getId());
        model.setTitle(catalog.getDisplayName());
        model.setType("text");
        model.setRemark(catalog.getDescription());
        model.setPlatform("OpenRouter");
        model.setMaxInputTokens(16384);
        model.setInputTypes("text");
        model.setResponseFormatTypes("text");
        model.setIsSupportWebSearch(false);
        model.setIsReasoner(false);
        model.setIsThinkingClosable(false);
        model.setIsFree(true);
        model.setIsEnable(true);
        ObjectNode properties = JsonUtil.createObjectNode();
        properties.put("managed_by", "openrouter_sync");
        properties.put("canonical_slug", catalog.getCanonicalSlug());
        properties.putArray("supported_parameters");
        model.setProperties(properties);
        return model;
    }

    private Map<String, String> zeroPricing() {
        return Map.of("prompt", "0", "completion", "0", "request", "0");
    }
}
