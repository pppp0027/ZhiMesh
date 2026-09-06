package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lazily loads and validates a frozen-embedding linear routing classifier. */
@Component
public class RoutingClassifierModelCatalog {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final ZhiMeshProperties properties;
    private volatile RoutingClassifierModel cached;

    public RoutingClassifierModelCatalog(ObjectMapper objectMapper,
                                         ResourceLoader resourceLoader,
                                         ZhiMeshProperties properties) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    public RoutingClassifierModel get() {
        RoutingClassifierModel result = cached;
        if (result == null) {
            synchronized (this) {
                result = cached;
                if (result == null) cached = result = load();
            }
        }
        return result;
    }

    private RoutingClassifierModel load() {
        String location = properties.getIntentRouting().getClassifierResource();
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Routing classifier resource does not exist: " + location);
        }
        byte[] bytes;
        try (InputStream input = resource.getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read routing classifier resource: " + location, exception);
        }
        String expectedSha256 = StringUtils.trimToNull(
                properties.getIntentRouting().getClassifierSha256());
        String actualSha256 = sha256Hex(bytes);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new IllegalStateException("Routing classifier SHA-256 does not match configuration");
        }

        ModelDocument document;
        try {
            document = objectMapper.readValue(bytes, ModelDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot parse routing classifier resource: " + location, exception);
        }
        validateMetadata(document);
        EnumMap<RoutingCapability, LinearHead> heads = new EnumMap<>(RoutingCapability.class);
        document.outputs().forEach((name, head) -> {
            RoutingCapability capability = parseCapability(name);
            if (heads.containsKey(capability)) {
                throw new IllegalStateException("Duplicate routing classifier output: " + name);
            }
            heads.put(capability, validateHead(capability, head, document.embeddingDimension()));
        });
        for (RoutingCapability capability : RoutingCapability.values()) {
            if (!heads.containsKey(capability)) {
                throw new IllegalStateException("Routing classifier is missing output: " + capability);
            }
        }
        if (!heads.get(RoutingCapability.VECTOR_SUFFICIENT).enabled()
                || !heads.get(RoutingCapability.GRAPH_REQUIRED).enabled()
                || !heads.get(RoutingCapability.BM25_REQUIRED).enabled()) {
            throw new IllegalStateException(
                    "Routing classifier v1 must enable vectorSufficient, graphRequired and bm25Required");
        }
        return new RoutingClassifierModel(document.modelVersion(), document.embeddingModel(),
                document.embeddingDimension(), document.normalization(), Map.copyOf(heads), actualSha256);
    }

    private void validateMetadata(ModelDocument document) {
        if (document == null || document.schemaVersion() != SUPPORTED_SCHEMA_VERSION
                || StringUtils.isBlank(document.modelVersion())
                || StringUtils.isBlank(document.embeddingModel())
                || document.embeddingDimension() <= 0
                || !"l2".equalsIgnoreCase(document.normalization())
                || document.outputs() == null) {
            throw new IllegalStateException("Routing classifier metadata is invalid");
        }
        String runtimeModel = properties.getEmbeddingModel();
        if (!document.embeddingModel().equalsIgnoreCase(StringUtils.defaultString(runtimeModel))) {
            throw new IllegalStateException("Routing classifier embedding model does not match runtime model");
        }
    }

    private static LinearHead validateHead(RoutingCapability capability, HeadDocument head, int dimension) {
        if (head == null) throw new IllegalStateException("Routing head is null: " + capability);
        if (!head.enabled()) return new LinearHead(false, new double[0], 0D, 1D);
        if (head.weights() == null || head.weights().size() != dimension
                || !Double.isFinite(head.bias()) || !Double.isFinite(head.threshold())
                || head.threshold() < 0D || head.threshold() > 1D) {
            throw new IllegalStateException("Routing head is invalid: " + capability);
        }
        double[] weights = new double[dimension];
        for (int index = 0; index < dimension; index++) {
            Double value = head.weights().get(index);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalStateException("Routing head contains invalid weight: " + capability);
            }
            weights[index] = value;
        }
        return new LinearHead(true, weights, head.bias(), head.threshold());
    }

    private static RoutingCapability parseCapability(String value) {
        String snakeCase = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
        try {
            return RoutingCapability.valueOf(snakeCase);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown routing classifier output: " + value, exception);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelDocument(int schemaVersion, String modelVersion, String embeddingModel,
                         int embeddingDimension, String normalization,
                         Map<String, HeadDocument> outputs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HeadDocument(boolean enabled, List<Double> weights, double bias, double threshold) {
    }

    public record RoutingClassifierModel(String modelVersion, String embeddingModel,
                                         int embeddingDimension, String normalization,
                                         Map<RoutingCapability, LinearHead> heads,
                                         String sha256) {
    }

    public record LinearHead(boolean enabled, double[] weights, double bias, double threshold) {
        public LinearHead {
            weights = weights == null ? new double[0] : weights.clone();
        }

        @Override
        public double[] weights() {
            return weights.clone();
        }
    }
}
