package com.pppp.zhimesh.common.memory.shortterm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryKeyResolverTest {

    private final ShortTermMemoryKeyResolver resolver = new ShortTermMemoryKeyResolver();

    @Test
    void namespacesMemoryByConversation() {
        assertThat(resolver.forConversation("conversation-uuid"))
                .isEqualTo("conversation:conversation-uuid");
    }
}
