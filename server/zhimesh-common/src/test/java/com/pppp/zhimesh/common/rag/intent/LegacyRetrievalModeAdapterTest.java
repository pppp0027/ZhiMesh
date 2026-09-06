package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyRetrievalModeAdapterTest {
    @Test
    void convertsLegacyModesWithoutLosingVectorGraphSemantics() {
        assertThat(LegacyRetrievalModeAdapter.toRoutes(RetrievalMode.VECTOR))
                .containsExactly(RetrievalRoute.VECTOR);
        assertThat(LegacyRetrievalModeAdapter.toRoutes(RetrievalMode.GRAPH))
                .containsExactly(RetrievalRoute.GRAPH);
        assertThat(LegacyRetrievalModeAdapter.toMode(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH)))
                .isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void refusesToSilentlyDropFutureRoutes() {
        assertThatThrownBy(() -> LegacyRetrievalModeAdapter.toMode(Set.of(RetrievalRoute.BM25)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LegacyRetrievalModeAdapter.toMode(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
