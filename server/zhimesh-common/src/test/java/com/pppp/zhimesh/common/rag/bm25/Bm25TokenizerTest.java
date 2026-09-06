package com.pppp.zhimesh.common.rag.bm25;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25TokenizerTest {

    private final Bm25Tokenizer tokenizer = new Bm25Tokenizer("test-analyzer-v1");

    @Test
    void normalizesFullWidthIdentifiersWithNfkc() {
        Bm25Analysis analysis = tokenizer.analyze("Ａ００７１ ｓｐｒｉｎｇ．ｄａｔａｓｏｕｒｃｅ．ｕｒｌ");

        assertTrue(analysis.termFrequencies().containsKey("a0071"));
        assertTrue(analysis.termFrequencies().containsKey("spring.datasource.url"));
        assertTrue(analysis.termFrequencies().containsKey("0071"));
    }

    @Test
    void emitsOverlappingChineseBigramsAndTrigrams() {
        Bm25Analysis analysis = tokenizer.analyze("知识检索");

        assertEquals(Map.of(
                "知识", 1,
                "识检", 1,
                "检索", 1,
                "知识检", 1,
                "识检索", 1), analysis.termFrequencies());
        assertEquals(5, analysis.documentLength());
    }

    @Test
    void usesSingleCharacterFallbackForOneCjkCodePoint() {
        Bm25Analysis analysis = tokenizer.analyze("知");

        assertEquals(Map.of("知", 1), analysis.termFrequencies());
        assertEquals(1, analysis.documentLength());
    }

    @Test
    void preservesTechnicalIdentifiersAndAddsUsefulParts() {
        Bm25Analysis analysis = tokenizer.analyze(
                "getUserName /api/v1/users/{id} spring.datasource.url A0071 ERR_CONNECTION_RESET");

        assertTrue(analysis.termFrequencies().keySet().containsAll(List.of(
                "getusername", "get", "user", "name",
                "/api/v1/users/{id}", "api/v1/users/{id}", "api", "v1", "v", "1", "users", "id",
                "spring.datasource.url", "spring", "datasource", "url",
                "a0071", "a", "0071",
                "err_connection_reset", "err", "connection", "reset")));
    }

    @Test
    void keepsLatinIdentifierAdjacentToChineseText() {
        Bm25Analysis analysis = tokenizer.analyze("知识getUserName");

        assertTrue(analysis.termFrequencies().containsKey("知识"));
        assertTrue(analysis.termFrequencies().containsKey("getusername"));
        assertTrue(analysis.termFrequencies().containsKey("get"));
    }

    @Test
    void queryTermLimitUsesDeterministicAnalyzerOrder() {
        Bm25Analysis analysis = tokenizer.analyze("getUserName A0071");

        assertEquals(List.of("getusername", "get", "user", "name", "a0071"),
                analysis.distinctTerms(5));
    }

    @Test
    void queryAnalysisRemovesConversationalNoiseButKeepsSubjectTerms() {
        Bm25Analysis analysis = tokenizer.analyzeQuery("火影忍者里面的面具男是谁？");

        assertTrue(analysis.termFrequencies().keySet().containsAll(List.of(
                "火影", "忍者", "面具", "面具男")));
        assertFalse(analysis.termFrequencies().containsKey("是谁"));
        assertFalse(analysis.termFrequencies().containsKey("里面"));
        assertFalse(analysis.termFrequencies().containsKey("面的"));
    }

    @Test
    void queryAnalysisStillPreservesTechnicalIdentifiers() {
        Bm25Analysis analysis = tokenizer.analyzeQuery(
                "请问 ERR_CONNECTION_RESET 和 spring.datasource.url 怎么处理？");

        assertTrue(analysis.termFrequencies().keySet().containsAll(List.of(
                "err_connection_reset", "err", "connection", "reset",
                "spring.datasource.url", "spring", "datasource", "url")));
    }

    @Test
    void queryAnalysisCoversQuestionPolitenessAndLocationVariants() {
        Bm25Analysis analysis = tokenizer.analyzeQuery(
                "麻烦告诉我，面具男谁是？他在哪里、什么时候出现的呢？");

        assertTrue(analysis.termFrequencies().containsKey("面具"));
        assertTrue(analysis.termFrequencies().containsKey("面具男"));
        assertFalse(analysis.termFrequencies().keySet().stream().anyMatch(term ->
                List.of("谁是", "哪里", "何时", "请问", "告诉", "时候").contains(term)));
    }

    @Test
    void postTokenFilterDropsGenericHighFrequencyTermsButKeepsSubject() {
        Bm25Analysis analysis = tokenizer.analyzeQuery("请说明一下相关系统如何进行版本治理");

        assertFalse(analysis.termFrequencies().containsKey("相关"));
        assertFalse(analysis.termFrequencies().containsKey("进行"));
        assertTrue(analysis.termFrequencies().keySet().containsAll(List.of("版本", "治理")));
    }

    @Test
    void skipsOversizedIdentifierTermsButKeepsPersistableParts() {
        String oversizedPart = "a".repeat(Bm25Tokenizer.MAX_TERM_CHARACTERS + 1);

        Bm25Analysis analysis = tokenizer.analyze(oversizedPart + "/usefulPart");

        assertTrue(analysis.termFrequencies().containsKey("useful"));
        assertTrue(analysis.termFrequencies().containsKey("part"));
        assertTrue(analysis.termFrequencies().keySet().stream().allMatch(term ->
                term.codePointCount(0, term.length()) <= Bm25Tokenizer.MAX_TERM_CHARACTERS));
    }

    @Test
    void safelyDropsIdentifierWhenNoPersistablePartExists() {
        String oversizedIdentifier = "x".repeat(Bm25Tokenizer.MAX_TERM_CHARACTERS + 1);

        Bm25Analysis analysis = tokenizer.analyze(oversizedIdentifier);

        assertTrue(analysis.termFrequencies().isEmpty());
        assertEquals(0, analysis.documentLength());
    }

    @Test
    void validatesAnalyzerVersionAgainstDatabaseColumnWidth() {
        String maxLengthVersion = "v".repeat(Bm25Tokenizer.MAX_ANALYZER_VERSION_CHARACTERS);

        assertEquals(maxLengthVersion, new Bm25Tokenizer(maxLengthVersion).analyzerVersion());
        assertThrows(IllegalArgumentException.class, () -> new Bm25Tokenizer("   "));
        assertThrows(IllegalArgumentException.class, () -> new Bm25Tokenizer(
                "v".repeat(Bm25Tokenizer.MAX_ANALYZER_VERSION_CHARACTERS + 1)));
    }
}
