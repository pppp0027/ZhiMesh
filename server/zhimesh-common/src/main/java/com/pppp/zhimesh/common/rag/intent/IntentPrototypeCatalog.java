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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads versioned text prototypes and lazily embeds them once per process. */
@Component
public class IntentPrototypeCatalog {
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final EmbeddingModel embeddingModel;
    private final ZhiMeshProperties properties;
    private volatile EmbeddedCatalog cached;

    public IntentPrototypeCatalog(ObjectMapper objectMapper, ResourceLoader resourceLoader,
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
        String location = properties.getIntentRouting().getPrototypeResource();
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) throw new IllegalStateException("Intent prototype resource does not exist: " + location);
        CatalogDocument document;
        try (InputStream input = resource.getInputStream()) {
            document = objectMapper.readValue(input, CatalogDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read intent prototype resource: " + location, exception);
        }
        validateDocument(document);
        EnumMap<QueryIntent, List<String>> examplesByIntent = new EnumMap<>(QueryIntent.class);
        List<QueryIntent> labels = new java.util.ArrayList<>();
        List<TextSegment> allSegments = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> entry : document.prototypes().entrySet()) {
            QueryIntent intent = parseIntent(entry.getKey());
            if (intent == QueryIntent.UNCERTAIN) continue;
            examplesByIntent.put(intent, List.copyOf(entry.getValue()));
            for (String example : entry.getValue()) {
                labels.add(intent);
                allSegments.add(TextSegment.from(example));
            }
        }
        if (!examplesByIntent.containsKey(QueryIntent.KNOWLEDGE_LOOKUP)
                || !examplesByIntent.containsKey(QueryIntent.RELATIONSHIP)) {
            throw new IllegalStateException("Prototype catalog must include KNOWLEDGE_LOOKUP and RELATIONSHIP");
        }
        List<Embedding> embeddings = embeddingModel.embedAll(allSegments).content();
        if (embeddings == null || embeddings.size() != allSegments.size()) {
            throw new IllegalStateException("Embedding model returned an invalid prototype result");
        }

        EnumMap<QueryIntent, List<float[]>> mutableVectors = new EnumMap<>(QueryIntent.class);
        int actualDimension = -1;
        for (int index = 0; index < embeddings.size(); index++) {
            float[] vector = embeddings.get(index).vector();
            validateVector(vector);
            if (actualDimension < 0) actualDimension = vector.length;
            if (vector.length != actualDimension) throw new IllegalStateException("Prototype embedding dimensions differ");
            mutableVectors.computeIfAbsent(labels.get(index), ignored -> new java.util.ArrayList<>()).add(vector);
        }
        if (document.embeddingDimension() > 0 && actualDimension != document.embeddingDimension()) {
            throw new IllegalStateException("Prototype embedding dimension does not match catalog metadata");
        }
        EnumMap<QueryIntent, List<float[]>> vectors = new EnumMap<>(QueryIntent.class);
        mutableVectors.forEach((intent, values) -> vectors.put(intent, List.copyOf(values)));
        return new EmbeddedCatalog(document.version(), actualDimension, Map.copyOf(vectors));
    }

    private static QueryIntent parseIntent(String value) {
        try {
            return QueryIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown intent in prototype catalog: " + value, exception);
        }
    }

    private void validateDocument(CatalogDocument document) {
        if (document == null || StringUtils.isBlank(document.version()) || document.prototypes() == null) {
            throw new IllegalStateException("Intent prototype catalog is missing required fields");
        }
        String expectedModel = document.embeddingModel();
        String actualModel = properties.getEmbeddingModel();
        if (StringUtils.isNotBlank(expectedModel) && !"*".equals(expectedModel)
                && !expectedModel.equalsIgnoreCase(StringUtils.defaultString(actualModel))) {
            throw new IllegalStateException("Prototype catalog embedding model does not match runtime model");
        }
        document.prototypes().forEach((intent, examples) -> {
            if (StringUtils.isBlank(intent) || examples == null || examples.isEmpty()
                    || examples.stream().anyMatch(StringUtils::isBlank)) {
                throw new IllegalStateException("Each prototype intent needs non-blank examples");
            }
        });
    }

    private static void validateVector(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalStateException("Prototype embedding is empty");
        double norm = 0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Prototype embedding contains a non-finite value");
            norm += value * value;
        }
        if (norm == 0D) throw new IllegalStateException("Prototype embedding is a zero vector");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogDocument(String version, String locale, String embeddingModel,
                           int embeddingDimension, Map<String, List<String>> prototypes) {
    }

    public record EmbeddedCatalog(String version, int dimension,
                                  Map<QueryIntent, List<float[]>> vectors) {
    }
}
