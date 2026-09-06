package com.pppp.zhimesh.common.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.util.SpringUtil;
import dev.langchain4j.rag.content.Content;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Calls a BGE reranker server compatible with the OpenAI-style /v1/rerank API. */
@Slf4j
public class BgeReranker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, CircuitState> CIRCUITS = new ConcurrentHashMap<>();
    private final AiModel model;
    private final ModelPlatform platform;
    private final HttpClient httpClient;

    public BgeReranker(AiModel model, ModelPlatform platform) {
        this.model = model;
        this.platform = platform;
        this.httpClient = createHttpClient();
    }

    public RerankResult rerank(String query, List<Content> candidates, int topN,
                               long timeoutMs, int failureThreshold, long circuitOpenMs) {
        return rerank(query, candidates, topN, timeoutMs, failureThreshold, circuitOpenMs, false);
    }

    public RerankResult rerank(String query, List<Content> candidates, int topN,
                               long timeoutMs, int failureThreshold, long circuitOpenMs,
                               boolean forceSingleCandidate) {
        if (candidates.isEmpty()
                || (candidates.size() == 1 && !forceSingleCandidate)) {
            return RerankResult.success(candidates.isEmpty()
                    ? List.of() : List.of(new RerankResult.RerankScore(0, 1D)), 0L);
        }
        if (!isEnabled()) {
            return RerankResult.failure(0L, "reranker_disabled");
        }
        CircuitState circuit = CIRCUITS.computeIfAbsent(circuitKey(), ignored -> new CircuitState());
        if (circuit.isOpen()) {
            log.warn("BGE reranker circuit is open, model:{}", model.getName());
            return RerankResult.openCircuitFallback();
        }
        long startedAt = System.nanoTime();
        try {
            List<String> documents = candidates.stream().map(content -> content.textSegment().text()).toList();
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model.getName());
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(topN, candidates.size()));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(platform.getBaseUrl()) + "/v1/rerank"))
                    .timeout(Duration.ofMillis(Math.max(1L, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(requestBody)));
            if (StringUtils.isNotBlank(platform.getApiKey())) {
                requestBuilder.header("Authorization", "Bearer " + platform.getApiKey());
            }
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("BGE reranker returned HTTP " + response.statusCode());
            }
            List<RerankResult.RerankScore> scores = mapResults(
                    OBJECT_MAPPER.readTree(response.body()), candidates, topN);
            circuit.success();
            return RerankResult.success(scores, elapsedMs(startedAt));
        } catch (Exception exception) {
            circuit.failure(Math.max(1, failureThreshold), Math.max(1000L, circuitOpenMs));
            log.warn("BGE reranking failed; falling back to RRF fusion", exception);
            return RerankResult.failure(elapsedMs(startedAt), exception.getClass().getSimpleName());
        }
    }

    private boolean isEnabled() {
        return model != null && Boolean.TRUE.equals(model.getIsEnable())
                && platform != null && StringUtils.isNotBlank(platform.getBaseUrl());
    }

    /**
     * Rerank requests must follow the same platform-level proxy decision as
     * chat, embedding and image requests. Previously this path always used a
     * direct connection, so a reranker configured to use a proxy could never
     * work after deployment.
     */
    private HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        if (!Boolean.TRUE.equals(platform == null ? null : platform.getIsProxyEnable())) {
            return builder.build();
        }
        try {
            ZhiMeshProperties.Proxy proxy = SpringUtil.getBean(ZhiMeshProperties.class).getProxy();
            if (proxy != null && proxy.isEnable() && StringUtils.isNotBlank(proxy.getHost())
                    && proxy.getHttpPort() > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getHttpPort())));
            } else {
                log.warn("Reranker platform {} has proxy enabled, but no valid global HTTP proxy is configured",
                        platform.getName());
            }
        } catch (Exception exception) {
            log.warn("Unable to initialise configured proxy for reranker platform {}; using direct connection",
                    platform.getName(), exception);
        }
        return builder.build();
    }

    private List<RerankResult.RerankScore> mapResults(JsonNode response, List<Content> candidates, int topN) {
        JsonNode results = response.path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("BGE reranker response does not contain results");
        }
        List<RerankResult.RerankScore> scores = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index >= 0 && index < candidates.size()) {
                JsonNode scoreNode = result.has("relevance_score")
                        ? result.path("relevance_score") : result.path("score");
                scores.add(new RerankResult.RerankScore(index, scoreNode.asDouble()));
            }
        }
        if (scores.isEmpty()) {
            throw new IllegalStateException("BGE reranker returned no valid result index");
        }
        return scores.stream().sorted(Comparator.comparingDouble(RerankResult.RerankScore::score).reversed())
                .limit(topN).toList();
    }

    private String normalizeBaseUrl(String baseUrl) {
        return StringUtils.removeEnd(baseUrl, "/");
    }

    private String circuitKey() {
        return normalizeBaseUrl(platform.getBaseUrl()) + "|" + model.getName();
    }

    private static long elapsedMs(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static class CircuitState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openUntilMs = new AtomicLong();

        boolean isOpen() {
            return System.currentTimeMillis() < openUntilMs.get();
        }

        void success() {
            consecutiveFailures.set(0);
            openUntilMs.set(0L);
        }

        void failure(int threshold, long openMs) {
            if (consecutiveFailures.incrementAndGet() >= threshold) {
                openUntilMs.set(System.currentTimeMillis() + openMs);
                consecutiveFailures.set(0);
            }
        }
    }
}
