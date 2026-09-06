package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads and embeds the small, hand-curated route-plan prototype catalog once per process. */
@Component
public class RetrievalRoutePrototypeCatalog {
    static final int MIN_PROTOTYPES_PER_PROFILE = 15;
    static final int MAX_PROTOTYPES_PER_PROFILE = 25;
    static final int MAX_TOTAL_PROTOTYPES = 100;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final EmbeddingModel embeddingModel;
    private final ZhiMeshProperties properties;
    private volatile EmbeddedCatalog cached;

    public RetrievalRoutePrototypeCatalog(ObjectMapper objectMapper, ResourceLoader resourceLoader,
                                          EmbeddingModel embeddingModel, ZhiMeshProperties properties) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    public EmbeddedCatalog get() {
        EmbeddedCatalog result = cached;
        if (result == null) {
            synchronized (this) {
                result = cached;
                if (result == null) cached = result = loadAndEmbed();
            }
        }
        return result;
    }

    private EmbeddedCatalog loadAndEmbed() {
        String location = properties.getIntentRouting().getRoutePrototypeResource();
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Route prototype resource does not exist: " + location);
        }
        CatalogDocument document;
        try (InputStream input = resource.getInputStream()) {
            document = objectMapper.readValue(input, CatalogDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read route prototype resource: " + location, exception);
        }
        validateDocument(document);

        List<RetrievalRouteProfile> labels = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : document.prototypes().entrySet()) {
            RetrievalRouteProfile profile = parseProfile(entry.getKey());
            for (String example : entry.getValue()) {
                labels.add(profile);
                segments.add(TextSegment.from(example));
            }
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings == null || embeddings.size() != segments.size()) {
            throw new IllegalStateException("Embedding model returned an invalid route prototype result");
        }
        EnumMap<RetrievalRouteProfile, List<float[]>> vectors = new EnumMap<>(RetrievalRouteProfile.class);
        int dimension = -1;
        for (int index = 0; index < embeddings.size(); index++) {
            float[] vector = embeddings.get(index).vector();
            validateVector(vector);
            if (dimension < 0) dimension = vector.length;
            if (vector.length != dimension) throw new IllegalStateException("Route prototype dimensions differ");
            vectors.computeIfAbsent(labels.get(index), ignored -> new ArrayList<>()).add(vector);
        }
        if (document.embeddingDimension() > 0 && dimension != document.embeddingDimension()) {
            throw new IllegalStateException("Route prototype dimension does not match catalog metadata");
        }
        for (RetrievalRouteProfile profile : RetrievalRouteProfile.values()) {
            if (!vectors.containsKey(profile) || vectors.get(profile).isEmpty()) {
                throw new IllegalStateException("Route prototype catalog is missing profile: " + profile);
            }
            vectors.put(profile, List.copyOf(vectors.get(profile)));
        }
        return new EmbeddedCatalog(document.version(), dimension, Map.copyOf(vectors));
    }

    private static RetrievalRouteProfile parseProfile(String value) {
        try {
            return RetrievalRouteProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown route prototype profile: " + value, exception);
        }
    }

    private void validateDocument(CatalogDocument document) {
        if (document == null || StringUtils.isBlank(document.version()) || document.prototypes() == null) {
            throw new IllegalStateException("Route prototype catalog is missing required fields");
        }
        String expectedModel = document.embeddingModel();
        String actualModel = properties.getEmbeddingModel();
        if (StringUtils.isNotBlank(expectedModel) && !"*".equals(expectedModel)
                && !expectedModel.equalsIgnoreCase(StringUtils.defaultString(actualModel))) {
            throw new IllegalStateException("Route prototype embedding model does not match runtime model");
        }
        EnumSet<RetrievalRouteProfile> profiles = EnumSet.noneOf(RetrievalRouteProfile.class);
        Set<String> uniqueExamples = new HashSet<>();
        int expectedProfileSize = -1;
        int total = 0;
        for (Map.Entry<String, List<String>> entry : document.prototypes().entrySet()) {
            RetrievalRouteProfile profile = parseProfile(entry.getKey());
            if (!profiles.add(profile)) {
                throw new IllegalStateException("Duplicate route prototype profile: " + profile);
            }
            List<String> examples = entry.getValue();
            if (examples == null || examples.stream().anyMatch(StringUtils::isBlank)) {
                throw new IllegalStateException("Each route profile needs non-blank examples");
            }
            if (examples.size() < MIN_PROTOTYPES_PER_PROFILE
                    || examples.size() > MAX_PROTOTYPES_PER_PROFILE) {
                throw new IllegalStateException("Each route profile must contain between "
                        + MIN_PROTOTYPES_PER_PROFILE + " and " + MAX_PROTOTYPES_PER_PROFILE + " examples");
            }
            if (expectedProfileSize < 0) expectedProfileSize = examples.size();
            if (examples.size() != expectedProfileSize) {
                throw new IllegalStateException("All route prototype profiles must contain the same number of examples");
            }
            for (String example : examples) {
                if (!uniqueExamples.add(example.trim().toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("Route prototype examples must be unique across profiles");
                }
            }
            total += examples.size();
        }
        if (!profiles.equals(EnumSet.allOf(RetrievalRouteProfile.class))) {
            throw new IllegalStateException("Route prototype catalog must contain all four route profiles");
        }
        if (total > MAX_TOTAL_PROTOTYPES) {
            throw new IllegalStateException("Route prototype catalog must not exceed "
                    + MAX_TOTAL_PROTOTYPES + " examples");
        }
    }

    private static void validateVector(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalStateException("Route prototype embedding is empty");
        double norm = 0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Route prototype embedding is non-finite");
            norm += value * value;
        }
        if (norm == 0D) throw new IllegalStateException("Route prototype embedding is a zero vector");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogDocument(String version, String locale, String embeddingModel,
                           int embeddingDimension, Map<String, List<String>> prototypes) {
    }

    public record EmbeddedCatalog(String version, int dimension,
                                  Map<RetrievalRouteProfile, List<float[]>> vectors) {
    }
}
