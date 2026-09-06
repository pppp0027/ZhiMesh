package com.pppp.zhimesh.common.openrouter;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCandidate;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCatalogModel;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterEndpointMetrics;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OpenRouterEligibilityService {

    private final ZhiMeshProperties properties;

    public OpenRouterEligibilityService(ZhiMeshProperties properties) {
        this.properties = properties;
    }

    public OpenRouterCandidate evaluateCatalog(OpenRouterCatalogModel model) {
        OpenRouterCandidate candidate = OpenRouterCandidate.builder()
                .catalogModel(model)
                .catalogStatus("REJECTED")
                .responseFormatTypes("text")
                .build();
        if (model == null || StringUtils.isBlank(model.getId())) {
            return reject(candidate, "MODEL_ID_MISSING", "Model id is missing");
        }
        if (model.getId().length() > 255) {
            return reject(candidate, "MODEL_ID_TOO_LONG", "Model id exceeds the database identity limit");
        }
        if (!model.getId().toLowerCase(Locale.ROOT).endsWith(":free")) {
            return reject(candidate, "CATALOG_NOT_FREE", "Model id is not a :free variant");
        }
        if (!isZeroPricing(model.getPricing())) {
            return reject(candidate, "CATALOG_NOT_FREE", "Catalog pricing is not completely free");
        }
        if (isExpired(model.getExpirationDate())) {
            return reject(candidate, "CATALOG_EXPIRED", "Model has expired");
        }
        int contextLength = effectiveContextLength(model);
        if (contextLength < properties.getOpenrouterSync().getMinContextTokens()) {
            return reject(candidate, "CONTEXT_TOO_SMALL", "Model context length is below the configured minimum");
        }

        Set<String> inputs = lower(model.getInputModalities());
        Set<String> outputs = lower(model.getOutputModalities());
        if (!inputs.contains("text") || inputs.stream().anyMatch(value -> !Set.of("text", "image").contains(value))) {
            return reject(candidate, "UNSUPPORTED_MODALITY", "Model input modalities are not supported");
        }
        if (!outputs.equals(Set.of("text"))) {
            return reject(candidate, "UNSUPPORTED_MODALITY", "Model output modalities are not text-only");
        }

        boolean vision = inputs.contains("image");
        Set<String> supportedParameters = lower(model.getSupportedParameters());
        candidate.setModelType(vision ? "vision" : "text");
        candidate.setInputTypes(vision ? "text,image" : "text");
        candidate.setResponseFormatTypes(
                supportedParameters.contains("response_format") || supportedParameters.contains("structured_outputs")
                        ? "text,json_object" : "text");
        candidate.setReasoner(supportedParameters.contains("reasoning"));
        candidate.setCatalogEligible(true);
        candidate.setCatalogStatus("FREE_COMPATIBLE");
        candidate.setRejectionReason("");
        return candidate;
    }

    public OpenRouterCandidate attachBestEndpoint(OpenRouterCandidate candidate,
                                                   List<OpenRouterEndpointMetrics> endpoints) {
        if (!candidate.isCatalogEligible()) {
            return candidate;
        }
        OpenRouterEndpointMetrics best = endpoints.stream()
                .filter(this::isEndpointEligible)
                .max(Comparator.comparingDouble(endpoint -> endpointScore(candidate, endpoint)))
                .orElse(null);
        if (best == null) {
            candidate.setEndpointEligible(false);
            candidate.setCatalogStatus("NO_FAST_FREE_ENDPOINT");
            candidate.setRejectionReason("No free endpoint satisfies latency, throughput, uptime, and context thresholds");
            return candidate;
        }
        candidate.setEndpoint(best);
        candidate.setEndpointEligible(true);
        candidate.setCatalogStatus("ELIGIBLE");
        candidate.setRejectionReason("");
        return candidate;
    }

    public boolean isZeroPricing(Map<String, String> pricing) {
        if (pricing == null || pricing.isEmpty()
                || !isRequiredZero(pricing.get("prompt"))
                || !isRequiredZero(pricing.get("completion"))) {
            return false;
        }
        for (String value : pricing.values()) {
            if (value == null) {
                return false;
            }
            try {
                if (new BigDecimal(value).compareTo(BigDecimal.ZERO) != 0) {
                    return false;
                }
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    public int effectiveContextLength(OpenRouterCatalogModel model) {
        Integer catalog = model.getContextLength();
        Integer provider = model.getTopProviderContextLength();
        if (catalog == null || catalog < 1) {
            return 0;
        }
        return provider == null || provider < 1 ? catalog : Math.min(catalog, provider);
    }

    private double endpointScore(OpenRouterCandidate candidate, OpenRouterEndpointMetrics endpoint) {
        double capability = Math.min(1D, effectiveContextLength(candidate.getCatalogModel()) / 131072D);
        capability = capability * 0.7D + (candidate.isReasoner() ? 0.3D : 0.1D);
        double speed = 1D - Math.min(1D, endpoint.getLatencyP50Ms() / 4000D);
        double throughput = Math.min(1D, endpoint.getThroughputP50().doubleValue() / 100D);
        double uptime = Math.min(1D, endpoint.getUptime1d().doubleValue() / 100D);
        return capability * 0.45D + speed * 0.30D + throughput * 0.15D + uptime * 0.10D;
    }

    private boolean isEndpointEligible(OpenRouterEndpointMetrics endpoint) {
        if (endpoint == null || endpoint.getLatencyP50Ms() == null || endpoint.getThroughputP50() == null
                || endpoint.getUptime1d() == null || endpoint.getContextLength() == null) {
            return false;
        }
        // OpenRouter's `/models/{id}/endpoints` endpoint describes the
        // underlying model providers, so its pricing can be non-zero even
        // when the catalog identity is the explicitly free `:free` variant.
        // Free-ness is therefore established from the catalog model pricing;
        // endpoint metadata is used here only for performance and availability.
        ZhiMeshProperties.OpenRouterSync settings = properties.getOpenrouterSync();
        return endpoint.getContextLength() >= settings.getMinContextTokens()
                && endpoint.getLatencyP50Ms() <= settings.getMaxCatalogP50LatencyMs()
                && endpoint.getThroughputP50().compareTo(BigDecimal.valueOf(settings.getMinCatalogThroughputTps())) >= 0
                && endpoint.getUptime1d().compareTo(BigDecimal.valueOf(settings.getMinCatalogUptime1d())) >= 0;
    }

    private boolean isRequiredZero(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            return new BigDecimal(value).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private boolean isExpired(String expirationDate) {
        if (StringUtils.isBlank(expirationDate)) {
            return false;
        }
        try {
            return Instant.parse(expirationDate).isBefore(Instant.now());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(expirationDate).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                        .isBefore(Instant.now());
            } catch (DateTimeParseException error) {
                return true;
            }
        }
    }

    private Set<String> lower(List<String> values) {
        Set<String> result = new HashSet<>();
        if (values != null) {
            values.stream().filter(StringUtils::isNotBlank)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .forEach(result::add);
        }
        return result;
    }

    private OpenRouterCandidate reject(OpenRouterCandidate candidate, String status, String reason) {
        candidate.setCatalogEligible(false);
        candidate.setEndpointEligible(false);
        candidate.setCatalogStatus(status);
        candidate.setRejectionReason(reason);
        return candidate;
    }
}
