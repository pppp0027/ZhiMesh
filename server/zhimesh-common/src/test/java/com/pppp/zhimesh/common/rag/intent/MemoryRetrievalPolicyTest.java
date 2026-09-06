package com.pppp.zhimesh.common.rag.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MemoryRetrievalPolicyTest {

    private final MemoryRetrievalPolicy policy = new MemoryRetrievalPolicy();

    @ParameterizedTest
    @CsvSource({
            "你好, NONE",
            "谢谢, NONE",
            "火影忍者里面的面具男是谁, AUTO",
            "我之前让你抓取过哪个网站？, EPISODIC",
            "我喜欢吃什么？, SEMANTIC",
            "我是谁？, SEMANTIC",
            "你还记得我之前说过什么？, BOTH",
            "还是按老规矩来吧, SEMANTIC",
            "照我平时的口味推荐, SEMANTIC",
            "上回那个网站叫什么？, EPISODIC",
            "那个牛客网链接再发我一次, EPISODIC"
    })
    void routesLongTermMemoryOnlyForExplicitPersonalRecall(
            String query, MemoryRetrievalMode expected) {
        assertEquals(expected, policy.decide(query, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"你好", "您好！", "thanks", "好的。", "再见"})
    void trivialTurnsAreNotExtractedAsDurableMemory(String query) {
        assertFalse(policy.shouldExtract(query));
    }

    @ParameterizedTest
    @ValueSource(strings = {"我之前让你抓取过哪个网站？", "你还记得我之前说过什么？"})
    void personalRecallSignalsOverrideDocumentNoRagDecision(String query) {
        IntentDecision documentDecision = new IntentDecision(
                QueryIntent.NO_RAG, 1D, 1D, Set.of(), Set.of(), "test", "not a document query");

        assertNotEquals(MemoryRetrievalMode.NONE, policy.decide(query, documentDecision));
    }
}
