package com.pppp.zhimesh.common.rag.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeRouteProfileCodecTest {

    private final KnowledgeRouteProfileCodec codec = new KnowledgeRouteProfileCodec();

    @Test
    void roundTripsCompactFloat32VectorAndRejectsWrongDimension() {
        float[] source = new float[]{0.25F, -0.5F, 0.75F};
        String encoded = codec.encode(source);

        assertThat(codec.decode(encoded, 3)).containsExactly(source);
        assertThatThrownBy(() -> codec.decode(encoded, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
