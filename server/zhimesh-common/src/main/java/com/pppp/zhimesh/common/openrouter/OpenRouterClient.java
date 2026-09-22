package com.pppp.zhimesh.common.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterCatalogModel;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterEndpointMetrics;
import com.pppp.zhimesh.common.openrouter.data.OpenRouterProbeResult;
import com.pppp.zhimesh.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class OpenRouterClient {

    private static final int MAX_JSON_BYTES = 10 * 1024 * 1024;
    private static final int LOCK_SAFETY_MINUTES = 30;

    private final ZhiMeshProperties properties;

    public OpenRouterClient(ZhiMeshProperties properties) {
        this.properties = properties;
    }

    public Session openSession(ModelPlatform platform) {
        validateSettings();
        if (platform == null) {
            throw new OpenRouterClientException("PLATFORM_MISSING", "OpenRouter platform is not configured", null);
        }
        if (StringUtils.isBlank(platform.getApiKey())) {
            throw new OpenRouterClientException("API_KEY_MISSING", "OpenRouter API key is not configured", null);
        }
        if (StringUtils.isBlank(platform.getBaseUrl())) {
            throw new OpenRouterClientException("BASE_URL_MISSING", "OpenRouter base URL is not configured", null);
        }

        URI baseUri;
        try {
            baseUri = URI.create(StringUtils.removeEnd(platform.getBaseUrl().trim(), "/"));
        } catch (IllegalArgumentException error) {
            throw new OpenRouterClientException("BASE_URL_INVALID", "OpenRouter base URL is invalid", error);
        }
        requireOpenRouterHost(baseUri);
        if (!"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new OpenRouterClientException("BASE_URL_INSECURE", "OpenRouter base URL must use HTTPS", null);
        }

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getOpenrouterSync().getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (Boolean.TRUE.equals(platform.getIsProxyEnable())) {
            ZhiMeshProperties.Proxy proxy = properties.getProxy();
            if (proxy == null || !proxy.isEnable() || StringUtils.isBlank(proxy.getHost()) || proxy.getHttpPort() < 1) {
                throw new OpenRouterClientException("PROXY_INVALID",
                        "OpenRouter platform requires a proxy, but zhimesh.proxy is not valid", null);
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getHttpPort())));
        }
        return new Session(platform, baseUri, builder.build());
    }

    private void validateSettings() {
        ZhiMeshProperties.OpenRouterSync settings = properties.getOpenrouterSync();
        if (settings.getConnectTimeoutMs() < 1 || settings.getRequestTimeoutMs() < 1
                || settings.getMaxProbeTotalMs() < 1 || settings.getMaxProbeTtftMs() < 1
                || settings.getMinContextTokens() < 1 || settings.getMaxCatalogP50LatencyMs() < 1
                || settings.getMinCatalogThroughputTps() < 0 || settings.getMinCatalogUptime1d() < 0
                || settings.getMinCatalogUptime1d() > 100 || settings.getMaxProbesPerRun() < 1
                || settings.getMinActiveModels() < 1
                || settings.getHealthCheckMinActiveModels() < 1
                || settings.getHealthCheckMaxProbesPerRun() < 1
                || settings.getActiveRunTimeoutMinutes() <= LOCK_SAFETY_MINUTES
                || settings.getRateLimitBackoffMinutes() < 1
                || settings.getProbeIntervalMs() < 3000 || settings.getCatchUpAfterHours() < 1
                || settings.getRequestTimeoutMs() <= settings.getMaxProbeTotalMs()) {
            throw new IllegalStateException("Invalid zhimesh.openrouter-sync configuration");
        }
    }

    private void requireOpenRouterHost(URI uri) {
        String host = StringUtils.defaultString(uri.getHost()).toLowerCase(Locale.ROOT);
        if (!host.equals("openrouter.ai") && !host.endsWith(".openrouter.ai")) {
            throw new OpenRouterClientException("HOST_NOT_ALLOWED", "Only openrouter.ai endpoints are allowed", null);
        }
    }

    public final class Session {
        private final ModelPlatform platform;
        private final URI baseUri;
        private final URI originUri;
        private final HttpClient httpClient;

        private Session(ModelPlatform platform, URI baseUri, HttpClient httpClient) {
            this.platform = platform;
            this.baseUri = baseUri;
            this.originUri = URI.create(baseUri.getScheme() + "://" + baseUri.getAuthority());
            this.httpClient = httpClient;
        }

        public void validateCurrentKey() {
            JsonNode root = getJson(pathUri("key"));
            if (!root.path("data").isObject()) {
                throw new OpenRouterClientException("KEY_RESPONSE_INVALID",
                        "OpenRouter key response does not contain data", null);
            }
        }

        public List<OpenRouterCatalogModel> listUserModels() {
            Map<String, OpenRouterCatalogModel> models = new LinkedHashMap<>();
            JsonNode root = getJson(pathUri("models/user"));
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new OpenRouterClientException("CATALOG_INVALID",
                        "OpenRouter model catalog does not contain an array", null);
            }
            if (data.isEmpty()) {
                throw new OpenRouterClientException("CATALOG_EMPTY",
                        "OpenRouter model catalog is unexpectedly empty", null);
            }
            for (JsonNode item : data) {
                OpenRouterCatalogModel model = parseCatalogModel(item);
                if (StringUtils.isBlank(model.getId())) {
                    throw new OpenRouterClientException("CATALOG_MODEL_ID_MISSING",
                            "OpenRouter catalog contains a model without id", null);
                }
                if (models.putIfAbsent(model.getId(), model) != null) {
                    throw new OpenRouterClientException("CATALOG_DUPLICATE_ID",
                            "OpenRouter catalog contains duplicate model id", null);
                }
            }
            return new ArrayList<>(models.values());
        }

        public List<OpenRouterEndpointMetrics> listEndpoints(OpenRouterCatalogModel model) {
            if (StringUtils.isBlank(model.getDetailsPath())) {
                throw new OpenRouterClientException("DETAILS_PATH_MISSING",
                        "OpenRouter model has no endpoint details path", null);
            }
            URI uri = detailsUri(model.getDetailsPath());
            JsonNode root = getJson(uri);
            JsonNode endpoints = root.path("data").path("endpoints");
            if (!endpoints.isArray()) {
                throw new OpenRouterClientException("ENDPOINTS_INVALID",
                        "OpenRouter endpoint response does not contain an array", null);
            }
            List<OpenRouterEndpointMetrics> result = new ArrayList<>();
            for (JsonNode endpoint : endpoints) {
                if (!endpoint.isObject()) {
                    continue;
                }
                BigDecimal latencyValue = decimal(endpoint.path("latency_last_30m").path("p50"));
                Integer latencyMs = latencyMillis(latencyValue);
                result.add(OpenRouterEndpointMetrics.builder()
                        .providerName(endpoint.path("provider_name").asText(""))
                        .contextLength(nullableInt(endpoint.get("context_length")))
                        .latencyP50Ms(latencyMs)
                        .throughputP50(decimal(endpoint.path("throughput_last_30m").path("p50")))
                        .uptime1d(decimal(endpoint.get("uptime_last_1d")))
                        .pricing(pricing(endpoint.get("pricing")))
                        .rawMetadata(((ObjectNode) endpoint).deepCopy())
                        .build());
            }
            return result;
        }

        public OpenRouterProbeResult probe(OpenRouterCatalogModel model) {
            ObjectNode body = JsonUtil.createObjectNode();
            body.put("model", model.getId());
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "user").put("content", "Reply with OK only.");
            Set<String> parameters = new HashSet<>(model.getSupportedParameters());
            if (parameters.contains("max_tokens")) {
                body.put("max_tokens", 8);
            }
            if (parameters.contains("temperature")) {
                body.put("temperature", 0);
            }
            body.put("stream", true);

            long startedNanos = System.nanoTime();
            HttpRequest request = requestBuilder(pathUri("chat/completions"))
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<ProbeBody> response = httpClient.sendAsync(request, responseInfo -> {
                            ProbeLineSubscriber subscriber = new ProbeLineSubscriber(startedNanos);
                            return HttpResponse.BodySubscribers.fromLineSubscriber(
                                    subscriber,
                                    ProbeLineSubscriber::finish,
                                    StandardCharsets.UTF_8,
                                    null);
                        })
                        .orTimeout(properties.getOpenrouterSync().getRequestTimeoutMs(), TimeUnit.MILLISECONDS)
                        .join();
                int totalMs = elapsedMillis(startedNanos);
                ProbeBody probeBody = response.body();
                if (isAuthenticationFailure(response.statusCode(), probeBody.errorCode)) {
                    throw new OpenRouterClientException(response.statusCode(),
                            StringUtils.defaultIfBlank(probeBody.errorCode,
                                    "HTTP_" + response.statusCode()),
                            StringUtils.defaultIfBlank(probeBody.errorMessage,
                                    "OpenRouter rejected the configured API key"));
                }
                if (response.statusCode() == 429 || probeBody.rateLimited) {
                    boolean upstreamPool = StringUtils.startsWith(probeBody.limitSource, "upstream");
                    return result("RATE_LIMITED", response.statusCode(), probeBody.ttftMs, totalMs,
                            StringUtils.defaultIfBlank(probeBody.errorCode, "RATE_LIMITED"),
                            StringUtils.defaultIfBlank(probeBody.errorMessage, "OpenRouter rate limit exceeded"),
                            upstreamPool);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return result("FAILED", response.statusCode(), probeBody.ttftMs, totalMs,
                            StringUtils.defaultIfBlank(probeBody.errorCode, "HTTP_" + response.statusCode()),
                            StringUtils.defaultIfBlank(probeBody.errorMessage, "OpenRouter probe failed"),
                            false);
                }
                if (StringUtils.isNotBlank(probeBody.errorCode)) {
                    return result("FAILED", response.statusCode(), probeBody.ttftMs, totalMs,
                            probeBody.errorCode, probeBody.errorMessage, false);
                }
                if (!probeBody.sawOutput || probeBody.ttftMs == null) {
                    return result("FAILED", response.statusCode(), null, totalMs,
                            "EMPTY_RESPONSE", "OpenRouter probe returned no content or reasoning delta",
                            false);
                }
                return result("SUCCESS", response.statusCode(), probeBody.ttftMs, totalMs, "", "", false);
            } catch (CompletionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof TimeoutException) {
                    return result("FAILED", 0, null, elapsedMillis(startedNanos),
                            "TIMEOUT", "OpenRouter probe timed out", false);
                }
                return result("FAILED", 0, null, elapsedMillis(startedNanos),
                        "NETWORK_ERROR", safeMessage(cause), false);
            }
        }

        private OpenRouterProbeResult result(String status, int httpStatus, Integer ttftMs,
                                             int totalMs, String code, String message,
                                             boolean upstreamRateLimited) {
            return OpenRouterProbeResult.builder()
                    .status(status)
                    .httpStatus(httpStatus)
                    .ttftMs(ttftMs)
                    .totalLatencyMs(totalMs)
                    .errorCode(StringUtils.abbreviate(StringUtils.defaultString(code), 64))
                    .errorMessage(StringUtils.abbreviate(StringUtils.defaultString(message), 1000))
                    .upstreamRateLimited(upstreamRateLimited)
                    .build();
        }

        private boolean isAuthenticationFailure(int httpStatus, String errorCode) {
            if (httpStatus == 401 || httpStatus == 403) {
                return true;
            }
            String normalized = StringUtils.defaultString(errorCode).toUpperCase(Locale.ROOT);
            return normalized.equals("401") || normalized.equals("403")
                    || normalized.contains("UNAUTHORIZED") || normalized.contains("FORBIDDEN")
                    || normalized.contains("INVALID_API_KEY");
        }

        private JsonNode getJson(URI uri) {
            HttpRequest request = requestBuilder(uri).GET().build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String body = StringUtils.defaultString(response.body());
                if (body.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                    throw new OpenRouterClientException("RESPONSE_TOO_LARGE",
                            "OpenRouter JSON response exceeded 10 MB", null);
                }
                JsonNode root;
                try {
                    root = JsonUtil.getObjectMapper().readTree(body);
                } catch (Exception error) {
                    throw new OpenRouterClientException(response.statusCode(), "JSON_INVALID",
                            "OpenRouter returned invalid JSON");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw errorFrom(response.statusCode(), root);
                }
                return root;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new OpenRouterClientException("INTERRUPTED", "OpenRouter request was interrupted", error);
            } catch (OpenRouterClientException error) {
                throw error;
            } catch (Exception error) {
                throw new OpenRouterClientException("NETWORK_ERROR", safeMessage(error), error);
            }
        }

        private HttpRequest.Builder requestBuilder(URI uri) {
            requireOpenRouterHost(uri);
            return HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getOpenrouterSync().getRequestTimeoutMs()))
                    .header("Authorization", "Bearer " + platform.getApiKey())
                    .header("Content-Type", "application/json");
        }

        private URI pathUri(String path) {
            return URI.create(StringUtils.removeEnd(baseUri.toString(), "/") + "/" + path);
        }

        private URI detailsUri(String detailsPath) {
            URI uri;
            try {
                URI supplied = URI.create(detailsPath);
                uri = supplied.isAbsolute() ? supplied : originUri.resolve(supplied);
            } catch (IllegalArgumentException error) {
                throw new OpenRouterClientException("DETAILS_PATH_INVALID",
                        "OpenRouter endpoint details path is invalid", error);
            }
            requireOpenRouterHost(uri);
            if (!uri.getPath().startsWith("/api/v1/models/") || !uri.getPath().endsWith("/endpoints")) {
                throw new OpenRouterClientException("DETAILS_PATH_NOT_ALLOWED",
                        "OpenRouter endpoint details path is not allowed", null);
            }
            return uri;
        }
    }

    private OpenRouterCatalogModel parseCatalogModel(JsonNode item) {
        JsonNode architecture = item.path("architecture");
        return OpenRouterCatalogModel.builder()
                .id(item.path("id").asText(""))
                .canonicalSlug(item.path("canonical_slug").asText(""))
                .displayName(item.path("name").asText(""))
                .description(item.path("description").asText(""))
                .contextLength(nullableInt(item.get("context_length")))
                .topProviderContextLength(nullableInt(item.path("top_provider").get("context_length")))
                .expirationDate(item.path("expiration_date").isTextual()
                        ? item.path("expiration_date").asText() : null)
                .detailsPath(item.path("links").path("details").asText(""))
                .inputModalities(strings(architecture.get("input_modalities")))
                .outputModalities(strings(architecture.get("output_modalities")))
                .supportedParameters(strings(item.get("supported_parameters")))
                .pricing(pricing(item.get("pricing")))
                .rawMetadata(item.isObject() ? ((ObjectNode) item).deepCopy() : JsonUtil.createObjectNode())
                .build();
    }

    private List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    private Map<String, String> pricing(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText(null)));
        }
        return result;
    }

    private Integer nullableInt(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToInt() ? null : node.intValue();
    }

    /**
     * OpenRouter has returned this metric in seconds historically, while the
     * current endpoint API returns milliseconds (for example, {@code 294}).
     * Values above 20 are unambiguously outside a useful seconds-scale latency
     * range, so treat those as milliseconds and retain the legacy conversion
     * for small second-scale values such as {@code 0.294}.
     */
    static Integer latencyMillis(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal millis = value.compareTo(BigDecimal.valueOf(20)) <= 0
                ? value.multiply(BigDecimal.valueOf(1000))
                : value;
        return millis.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private OpenRouterClientException errorFrom(int status, JsonNode root) {
        JsonNode error = root.path("error");
        String code = error.path("code").asText("HTTP_" + status);
        String message = StringUtils.abbreviate(error.path("message").asText("OpenRouter request failed"), 1000);
        return new OpenRouterClientException(status, code, message);
    }

    private int elapsedMillis(long startedNanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, millis));
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "OpenRouter network request failed";
        }
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName()), 1000);
    }

    private final class ProbeLineSubscriber implements Flow.Subscriber<String> {
        private final long startedNanos;
        private Flow.Subscription subscription;
        private Integer ttftMs;
        private boolean sawOutput;
        private boolean rateLimited;
        private String limitSource;
        private String errorCode;
        private String errorMessage;

        private ProbeLineSubscriber(long startedNanos) {
            this.startedNanos = startedNanos;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            if (StringUtils.isBlank(line)) {
                return;
            }
            String payload = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if ("[DONE]".equals(payload) || !payload.startsWith("{")) {
                return;
            }
            try {
                JsonNode node = JsonUtil.getObjectMapper().readTree(payload);
                JsonNode error = node.path("error");
                if (error.isObject()) {
                    errorCode = error.path("code").asText("PROBE_ERROR");
                    errorMessage = StringUtils.abbreviate(error.path("message").asText("OpenRouter probe failed"), 1000);
                    rateLimited = "429".equals(errorCode)
                            || "rate_limit_exceeded".equals(error.path("metadata").path("error_type").asText());
                    limitSource = error.path("metadata").path("limit_source").asText("");
                    return;
                }
                JsonNode choices = node.path("choices");
                if (!choices.isArray()) {
                    return;
                }
                for (JsonNode choice : choices) {
                    JsonNode delta = choice.path("delta");
                    String content = delta.path("content").asText("");
                    String reasoning = delta.path("reasoning").asText("");
                    if (StringUtils.isNotEmpty(content) || StringUtils.isNotEmpty(reasoning)) {
                        sawOutput = true;
                        if (ttftMs == null) {
                            ttftMs = elapsedMillis(startedNanos);
                        }
                    }
                }
            } catch (Exception error) {
                log.debug("Ignoring an unparseable OpenRouter probe stream line");
            }
        }

        @Override
        public void onError(Throwable throwable) {
            errorCode = "STREAM_ERROR";
            errorMessage = safeMessage(throwable);
        }

        @Override
        public void onComplete() {
            // The body subscriber finisher reads the accumulated fields.
        }

        private ProbeBody finish() {
            return new ProbeBody(ttftMs, sawOutput, rateLimited, limitSource, errorCode, errorMessage);
        }
    }

    private record ProbeBody(Integer ttftMs, boolean sawOutput, boolean rateLimited, String limitSource,
                             String errorCode, String errorMessage) {
    }
}
