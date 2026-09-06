package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterServiceKnowledgeBaseFilterTest {

    @Test
    void disabledKnowledgeBaseCanNeverEnterRuntimeRetrievalScope() {
        KbInfoResp enabled = new KbInfoResp();
        enabled.setIsEnabled(true);
        KbInfoResp legacyEnabled = new KbInfoResp();
        legacyEnabled.setIsEnabled(null);
        KbInfoResp disabled = new KbInfoResp();
        disabled.setIsEnabled(false);

        assertThat(CharacterService.isKnowledgeBaseEnabled(enabled)).isTrue();
        assertThat(CharacterService.isKnowledgeBaseEnabled(legacyEnabled)).isTrue();
        assertThat(CharacterService.isKnowledgeBaseEnabled(disabled)).isFalse();
        assertThat(CharacterService.isKnowledgeBaseEnabled(null)).isFalse();
    }
}
