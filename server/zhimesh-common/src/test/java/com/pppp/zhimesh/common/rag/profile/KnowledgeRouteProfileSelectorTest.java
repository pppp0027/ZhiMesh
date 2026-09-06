package com.pppp.zhimesh.common.rag.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRouteProfileSelectorTest {

    @Test
    void keepsOverviewAndSelectsTheMostDiverseRealCandidate() {
        var refs = new ObjectMapper().createArrayNode();
        List<KnowledgeRouteProfileCandidate> candidates = List.of(
                new KnowledgeRouteProfileCandidate("OVERVIEW", "overview", "overview", refs),
                new KnowledgeRouteProfileCandidate("DOCUMENT", "near", "near", refs),
                new KnowledgeRouteProfileCandidate("TOPIC", "far", "far", refs));
        List<Embedding> embeddings = List.of(
                Embedding.from(new float[]{1F, 0F}),
                Embedding.from(new float[]{0.99F, 0.01F}),
                Embedding.from(new float[]{0F, 1F}));

        List<KnowledgeRouteProfileSelector.Selected> selected =
                new KnowledgeRouteProfileSelector().select(candidates, embeddings, 2);

        assertThat(selected).extracting(value -> value.candidate().key())
                .containsExactly("overview", "far");
    }
}
