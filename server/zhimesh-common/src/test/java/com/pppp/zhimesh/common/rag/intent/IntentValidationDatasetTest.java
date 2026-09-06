package com.pppp.zhimesh.common.rag.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IntentValidationDatasetTest {
    @Test
    void validationSetIsVersionedDisjointAndCoversEveryIntent() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag/intent-validation.zh-CN.json")) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);
            assertThat(root.path("version").asText()).isNotBlank();
            Set<String> queries = new HashSet<>();
            EnumSet<QueryIntent> intents = EnumSet.noneOf(QueryIntent.class);
            for (JsonNode example : root.path("examples")) {
                assertThat(queries.add(example.path("query").asText())).isTrue();
                intents.add(QueryIntent.valueOf(example.path("intent").asText()));
            }
            assertThat(queries).hasSizeGreaterThanOrEqualTo(16);
            assertThat(intents).containsExactlyInAnyOrder(QueryIntent.values());
            try (InputStream prototypes = getClass().getResourceAsStream("/rag/intent-prototypes.zh-CN.json")) {
                assertThat(prototypes).isNotNull();
                JsonNode prototypeRoot = new ObjectMapper().readTree(prototypes);
                Set<String> prototypeQueries = new HashSet<>();
                prototypeRoot.path("prototypes").elements().forEachRemaining(values ->
                        values.forEach(value -> prototypeQueries.add(value.asText())));
                assertThat(queries).doesNotContainAnyElementsOf(prototypeQueries);
            }
        }
    }
}
