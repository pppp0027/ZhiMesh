package com.pppp.zhimesh.common.openrouter;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCandidate;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCatalogModel;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterEndpointMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterEligibilityServiceTest {

    private OpenRouterEligibilityService service;

    @BeforeEach
    void setUp() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        service = new OpenRouterEligibilityService(properties);
    }

    @Test
    void requiresPromptCompletionAndEveryPresentPriceDimensionToBeZero() {
        assertThat(service.isZeroPricing(Map.of("prompt", "0", "completion", "0.000", "image", "0e-9")))
                .isTrue();
        assertThat(service.isZeroPricing(Map.of("prompt", "0", "completion", "0", "request", "0.01")))
                .isFalse();
        assertThat(service.isZeroPricing(Map.of("prompt", "0"))).isFalse();
        assertThat(service.isZeroPricing(Map.of("prompt", "free", "completion", "0"))).isFalse();
    }

    @Test
    void mapsTextAndVisionChatModelsConservatively() {
        OpenRouterCatalogModel text = model("vendor/text:free", List.of("text"), List.of("text"));
        text.setSupportedParameters(List.of("response_format", "reasoning"));
        OpenRouterCandidate textCandidate = service.evaluateCatalog(text);

        assertThat(textCandidate.isCatalogEligible()).isTrue();
        assertThat(textCandidate.getModelType()).isEqualTo("text");
        assertThat(textCandidate.getInputTypes()).isEqualTo("text");
        assertThat(textCandidate.getResponseFormatTypes()).isEqualTo("text,json_object");
        assertThat(textCandidate.isReasoner()).isTrue();

        OpenRouterCatalogModel vision = model("vendor/vision:free",
                List.of("text", "image"), List.of("text"));
        OpenRouterCandidate visionCandidate = service.evaluateCatalog(vision);
        assertThat(visionCandidate.isCatalogEligible()).isTrue();
        assertThat(visionCandidate.getModelType()).isEqualTo("vision");
        assertThat(visionCandidate.getInputTypes()).isEqualTo("text,image");

        OpenRouterCatalogModel unsupported = model("vendor/audio:free",
                List.of("text", "audio"), List.of("text"));
        assertThat(service.evaluateCatalog(unsupported).getCatalogStatus())
                .isEqualTo("UNSUPPORTED_MODALITY");
    }

    @Test
    void rejectsExpiredAndSmallContextModels() {
        OpenRouterCatalogModel expired = model("vendor/expired:free", List.of("text"), List.of("text"));
        expired.setExpirationDate(Instant.now().minus(1, ChronoUnit.DAYS).toString());
        assertThat(service.evaluateCatalog(expired).getCatalogStatus()).isEqualTo("CATALOG_EXPIRED");

        OpenRouterCatalogModel small = model("vendor/small:free", List.of("text"), List.of("text"));
        small.setContextLength(4096);
        small.setTopProviderContextLength(4096);
        assertThat(service.evaluateCatalog(small).getCatalogStatus()).isEqualTo("CONTEXT_TOO_SMALL");
    }

    @Test
    void selectsOnlyAFreeEndpointThatPassesAllPerformanceThresholds() {
        OpenRouterCandidate candidate = service.evaluateCatalog(
                model("vendor/fast:free", List.of("text"), List.of("text")));
        OpenRouterEndpointMetrics slow = endpoint(5000, "50", "100");
        OpenRouterEndpointMetrics lowThroughput = endpoint(1000, "5", "100");
        OpenRouterEndpointMetrics fast = endpoint(1200, "25", "99.5");

        service.attachBestEndpoint(candidate, List.of(slow, lowThroughput, fast));

        assertThat(candidate.isEndpointEligible()).isTrue();
        assertThat(candidate.getEndpoint()).isSameAs(fast);
        assertThat(candidate.getCatalogStatus()).isEqualTo("ELIGIBLE");
    }

    @Test
    void doesNotRejectFreeCatalogVariantWhenProviderMetadataShowsBaseModelPricing() {
        OpenRouterCandidate candidate = service.evaluateCatalog(
                model("vendor/free-route:free", List.of("text"), List.of("text")));
        OpenRouterEndpointMetrics provider = endpoint(350, "40", "99.9");
        provider.setPricing(Map.of("prompt", "0.0000001", "completion", "0.0000002"));

        service.attachBestEndpoint(candidate, List.of(provider));

        assertThat(candidate.isEndpointEligible()).isTrue();
        assertThat(candidate.getEndpoint()).isSameAs(provider);
    }

    @Test
    void acceptsBothLegacySecondsAndCurrentMillisecondsLatencyMetrics() {
        assertThat(OpenRouterClient.latencyMillis(new BigDecimal("0.294"))).isEqualTo(294);
        assertThat(OpenRouterClient.latencyMillis(new BigDecimal("294"))).isEqualTo(294);
    }

    private OpenRouterCatalogModel model(String id, List<String> inputs, List<String> outputs) {
        return OpenRouterCatalogModel.builder()
                .id(id)
                .displayName(id)
                .contextLength(32768)
                .topProviderContextLength(16384)
                .inputModalities(inputs)
                .outputModalities(outputs)
                .supportedParameters(List.of())
                .pricing(zeroPricing())
                .build();
    }

    private OpenRouterEndpointMetrics endpoint(int latencyMs, String throughput, String uptime) {
        return OpenRouterEndpointMetrics.builder()
                .contextLength(16384)
                .latencyP50Ms(latencyMs)
                .throughputP50(new BigDecimal(throughput))
                .uptime1d(new BigDecimal(uptime))
                .pricing(zeroPricing())
                .build();
    }

    private Map<String, String> zeroPricing() {
        return Map.of("prompt", "0", "completion", "0", "request", "0");
    }
}
