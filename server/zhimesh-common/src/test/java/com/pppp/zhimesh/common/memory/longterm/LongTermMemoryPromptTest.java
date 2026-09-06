package com.pppp.zhimesh.common.memory.longterm;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryPromptTest {

    @Test
    void buildsRelativeDateExamplesFromEachSourceMessageTime() {
        String first = LongTermMemoryPrompt.buildExtractionPrompt(
                LocalDateTime.of(2026, 8, 21, 10, 30), ZoneId.of("Asia/Shanghai"));
        String second = LongTermMemoryPrompt.buildExtractionPrompt(
                LocalDateTime.of(2026, 8, 22, 10, 30), ZoneId.of("Asia/Shanghai"));

        assertThat(first).contains("2026-08-21 10:30:00 (Asia/Shanghai)")
                .contains("2026-08-20 15:00:00");
        assertThat(second).contains("2026-08-22 10:30:00 (Asia/Shanghai)")
                .contains("2026-08-21 15:00:00");
        assertThat(first).isNotEqualTo(second);
    }
}
