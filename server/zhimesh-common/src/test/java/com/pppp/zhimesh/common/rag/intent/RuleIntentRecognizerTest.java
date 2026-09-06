package com.pppp.zhimesh.common.rag.intent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleIntentRecognizerTest {
    private final RuleIntentRecognizer recognizer = new RuleIntentRecognizer();

    @Test
    void recognizesOnlyStrongConversationalAndRelationshipRules() {
        assertThat(recognize("你好").intent()).isEqualTo(QueryIntent.NO_RAG);
        assertThat(recognize("你是谁").intent()).isEqualTo(QueryIntent.NO_RAG);
        assertThat(recognize("你是什么模型？").intent()).isEqualTo(QueryIntent.NO_RAG);
        assertThat(recognize("你使用的是什么模型").intent()).isEqualTo(QueryIntent.NO_RAG);
        assertThat(recognize("What model are you?").intent()).isEqualTo(QueryIntent.NO_RAG);
        assertThat(recognize("用户和角色之间是什么关系？").intent()).isEqualTo(QueryIntent.RELATIONSHIP);
        assertThat(recognize("如何配置 Redis").intent()).isEqualTo(QueryIntent.UNCERTAIN);
    }

    @Test
    void aSingleConnectionWordDoesNotForceGraphIntent() {
        IntentDecision decision = recognize("连接");
        assertThat(decision.intent()).isEqualTo(QueryIntent.UNCERTAIN);
        assertThat(decision.signals()).doesNotContain(IntentSignal.RELATION_QUERY);
    }

    @Test
    void definiteNoRagCanShortCircuitBeforeQueryEmbedding() {
        assertThat(recognizer.isDefiniteNoRag("你是什么模型？")).isTrue();
        assertThat(recognizer.isDefiniteNoRag("What model are you?")).isTrue();
        assertThat(recognizer.isDefiniteNoRag("如何配置 Redis")).isFalse();
    }

    @Test
    void exposesExactAndAmbiguousSignals() {
        IntentDecision exact = recognize("请从 Wiki 查询 POST /api/users 的配置项");
        assertThat(exact.signals()).containsExactly(IntentSignal.EXACT_IDENTIFIER);
        assertThat(exact.sourceHints()).isEmpty();

        IntentDecision ambiguous = recognize("这个");
        assertThat(ambiguous.intent()).isEqualTo(QueryIntent.UNCERTAIN);
        assertThat(ambiguous.signals()).contains(IntentSignal.AMBIGUOUS_CONTEXT);
    }

    @Test
    void distinguishesContextFollowUpsFromCompleteDemonstrativeNounQuestions() {
        assertThat(recognize("那它为什么失败了？").signals())
                .contains(IntentSignal.AMBIGUOUS_CONTEXT);
        assertThat(recognize("那么为什么会失败？").signals())
                .contains(IntentSignal.AMBIGUOUS_CONTEXT);
        assertThat(recognize("上面提到的配置如何生效？").signals())
                .contains(IntentSignal.AMBIGUOUS_CONTEXT);

        assertThat(recognize("这个功能是什么意思？").signals())
                .doesNotContain(IntentSignal.AMBIGUOUS_CONTEXT);
        assertThat(recognize("这个模块与其他组件的依赖关系是什么？").signals())
                .doesNotContain(IntentSignal.AMBIGUOUS_CONTEXT);
    }

    private IntentDecision recognize(String query) {
        return recognizer.recognize(new IntentRoutingContext(query, null, null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB),
                Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH), "test"));
    }
}
