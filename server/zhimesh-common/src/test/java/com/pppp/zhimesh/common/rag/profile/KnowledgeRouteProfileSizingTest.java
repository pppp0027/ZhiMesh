package com.pppp.zhimesh.common.rag.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRouteProfileSizingTest {

    @Test
    void scalesProfileBudgetByKnowledgeBaseSize() {
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(0)).isEqualTo(8);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(50)).isEqualTo(8);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(51)).isEqualTo(12);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(300)).isEqualTo(12);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(301)).isEqualTo(16);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(1_000)).isEqualTo(16);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(1_001)).isEqualTo(20);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(5_000)).isEqualTo(20);
        assertThat(KnowledgeRouteProfileSizing.forContentUnits(5_001)).isEqualTo(24);
    }

    @Test
    void usesTheLargerOfDocumentsAndEmbeddingsAsContentUnits() {
        assertThat(KnowledgeRouteProfileSizing.contentUnits(12, 1_000)).isEqualTo(1_000);
        assertThat(KnowledgeRouteProfileSizing.contentUnits(1_000, 12)).isEqualTo(1_000);
    }

    @Test
    void configuredMaximumCannotExceedTheServerHardLimit() {
        assertThat(KnowledgeRouteProfileSizing.boundedLimit(100_000, 64)).isEqualTo(24);
        assertThat(KnowledgeRouteProfileSizing.boundedLimit(100_000, 12)).isEqualTo(12);
        assertThat(KnowledgeRouteProfileSizing.boundedLimit(10, 0)).isEqualTo(1);
    }
}
