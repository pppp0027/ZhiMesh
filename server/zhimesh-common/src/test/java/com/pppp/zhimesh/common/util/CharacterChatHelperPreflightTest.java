package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopeDecision;
import com.pppp.zhimesh.common.rag.intent.MemoryRetrievalPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterChatHelperPreflightTest {

    private final MemoryRetrievalPolicy memoryPolicy = new MemoryRetrievalPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "你好", "您好！", "谢谢", "好的。", "再见"})
    void onlyBlankOrProvablyTrivialTurnsSkipAllExternalSources(String query) {
        assertThat(CharacterChatHelper.shouldSkipAllExternalSources(query, memoryPolicy)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"这个呢？", "为什么？", "继续", "然后呢", "上面那个呢？"})
    void contextDependentFollowUpsMustReachFailOpenRouting(String query) {
        assertThat(CharacterChatHelper.shouldSkipAllExternalSources(query, memoryPolicy)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void resolvedKnowledgeScopeFiltersOnlyExplicitlyUnrelatedKnowledgeBases() {
        KbInfoResp first = new KbInfoResp();
        first.setUuid("kb-a");
        KbInfoResp second = new KbInfoResp();
        second.setUuid("kb-b");
        KbInfoResp third = new KbInfoResp();
        third.setUuid("kb-c");
        KnowledgeScopeDecision decision = new KnowledgeScopeDecision(
                KnowledgeScopeDecision.Status.RELATED, 0.9D, "per-kb", List.of(), 1L,
                Set.of("kb-a", "kb-c"), true);

        assertThat(CharacterChatHelper.resolveKnowledgeBaseRetrievalScope(
                List.of(first, second, third), decision))
                .containsExactlyInAnyOrder("kb-a", "kb-c");
    }

    @org.junit.jupiter.api.Test
    void unresolvedKnowledgeScopeKeepsAllAttachedKnowledgeBases() {
        KbInfoResp first = new KbInfoResp();
        first.setUuid("kb-a");
        KbInfoResp second = new KbInfoResp();
        second.setUuid("kb-b");
        KnowledgeScopeDecision decision = new KnowledgeScopeDecision(
                KnowledgeScopeDecision.Status.UNCERTAIN, -1D, "fail open", List.of(), 1L);

        assertThat(CharacterChatHelper.resolveKnowledgeBaseRetrievalScope(
                List.of(first, second), decision))
                .containsExactlyInAnyOrder("kb-a", "kb-b");
    }
}
