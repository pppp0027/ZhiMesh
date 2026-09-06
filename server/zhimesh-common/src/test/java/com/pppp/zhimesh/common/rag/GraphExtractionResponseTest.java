package com.pppp.zhimesh.common.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExtractionResponseTest {

    @Test
    void acceptsAnEmptyExtractionAsAnUngraphableFragment() {
        String result = GraphExtractionResponse.parseToLegacyFormat(
                "{\"entities\":[],\"relationships\":[]}");

        assertTrue(result.isEmpty());
    }

    @Test
    void keepsValidEntityRecordsForGraphPersistence() {
        String result = GraphExtractionResponse.parseToLegacyFormat("""
                {"entities":[{"name":"ZhiMesh","type":"ORGANIZATION","description":"AI platform"}],
                "relationships":[]}
                """);

        assertEquals("(\"entity\"<|>ZhiMesh<|>ORGANIZATION<|>AI platform<|>ZhiMesh<|>[]<|>{}<|>5)", result);
    }

    @Test
    void supportsTheExpandedCoreEntityTypes() {
        String result = GraphExtractionResponse.parseToLegacyFormat("""
                {"entities":[
                  {"name":"Redis","type":"TECHNOLOGY","description":"Cache"},
                  {"name":"Checkout","type":"PROCESS","description":"Order process"},
                  {"name":"Admin","type":"ROLE","description":"Operator role"}],
                "relationships":[]}
                """);

        assertTrue(result.contains("Redis<|>TECHNOLOGY"));
        assertTrue(result.contains("Checkout<|>PROCESS"));
        assertTrue(result.contains("Admin<|>ROLE"));
    }

    @Test
    void keepsUnsupportedTypesExplicitlyUnknownOnTheLegacyTransportOnly() {
        String response = """
                {"entities":[{"name":"Redis","type":"CACHE","description":"Cache"}],
                "relationships":[]}
                """;

        String result = GraphExtractionResponse.parseToLegacyFormat(response);

        // The legacy transport still carries the unresolved type explicitly, but
        // the quality gate below must reject it before anything is persisted.
        assertEquals("(\"entity\"<|>Redis<|>UNKNOWN<|>Cache<|>Redis<|>[]<|>{}<|>5)", result);
        assertTrue(GraphExtractionResponse.qualityIssues(response, "Redis is used as a cache.")
                .stream().anyMatch(issue -> issue.contains("entity type must be one of the core types")));
    }

    @Test
    void flagsExplicitUnknownTypeForRepairBeforePersistence() {
        String response = """
                {"entities":[{"name":"某物","type":"UNKNOWN","description":"描述"}],
                "relationships":[]}
                """;

        assertTrue(GraphExtractionResponse.qualityIssues(response, "某物的描述文本。")
                .stream().anyMatch(issue -> issue.contains("entity type must be one of the core types")
                        && issue.contains("UNKNOWN")));
    }

    @Test
    void detectsEnglishProseInjectedIntoChineseDescriptions() {
        String response = """
                {"entities":[{"name":"鹤纹感知材料","type":"PRODUCT",
                "description":"一种用于潮汐监测的材料。This material is designed for sensing ocean changes."}],
                "relationships":[]}
                """;

        assertTrue(GraphExtractionResponse.qualityIssues(response,
                        "鹤纹感知材料是一种用于潮汐监测的复合材料。")
                .stream().anyMatch(issue -> issue.contains("unexpected English prose")));
    }

    @Test
    void acceptsChineseDescriptionsWithSourceBackedEnglishNames() {
        String response = """
                {"entities":[{"name":"GPT-5.6 Terra","type":"TECHNOLOGY",
                "description":"文档使用 GPT-5.6 Terra 完成实体抽取"}],"relationships":[]}
                """;

        assertTrue(GraphExtractionResponse.qualityIssues(response,
                "该文档使用 GPT-5.6 Terra 完成实体抽取。").isEmpty());
    }

    @Test
    void rejectsEmptyDescriptionsAndUndeclaredRelationshipEndpoints() {
        String response = """
                {"entities":[{"name":"曜穹机器人","type":"ORGANIZATION","description":""}],
                "relationships":[{"source":"曜穹机器人","target":"赤脊七型","description":"","weight":8}]}
                """;

        var issues = GraphExtractionResponse.qualityIssues(response,
                "曜穹机器人发布了赤脊七型。");

        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("entity description is empty")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("missing from entities")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("relationship description is empty")));
    }

    @Test
    void detectsSameNameTypeConflictsBeforePersistence() {
        String response = """
                {"entities":[
                  {"name":"曜穹机器人","type":"PRODUCT","description":"一家机器人公司"},
                  {"name":"曜穹机器人","type":"ORGANIZATION","description":"从事机器人研发的公司"}],
                "relationships":[]}
                """;

        assertTrue(GraphExtractionResponse.qualityIssues(response,
                        "曜穹机器人是一家从事机器人研发的公司。")
                .stream().anyMatch(issue -> issue.contains("conflicting types")));
    }

    @Test
    void serializesSemanticRelationshipIdentityAndClampsWeight() {
        String result = GraphExtractionResponse.parseToLegacyFormat("""
                {"entities":[
                  {"name":"甲公司","canonical_name":"甲公司","aliases":[],"type":"ORGANIZATION","description":"甲方","properties":{},"salience":8},
                  {"name":"乙公司","canonical_name":"乙公司","aliases":[],"type":"ORGANIZATION","description":"乙方","properties":{},"salience":8}],
                 "relationships":[{"source":"甲公司","target":"乙公司","type":"partners_with",
                   "polarity":false,"status":"ASSERTED","description":"并非合作方","properties":{"依据":"公告"},"weight":16}]}
                """);

        assertTrue(result.contains("<|>COOPERATES_WITH<|>false<|>ASSERTED<|>"));
        assertTrue(result.contains("<|>10.0<|>"));
    }

    @Test
    void filtersLowValueStandaloneAttributesButKeepsRelationshipEndpoints() {
        String result = GraphExtractionResponse.parseToLegacyFormat("""
                {"entities":[
                  {"name":"现金储备","type":"CONCEPT","description":"现金指标","salience":2},
                  {"name":"实际放电时长","type":"CONCEPT","description":"放电指标","salience":2},
                  {"name":"潮汐环能联合体","type":"ORGANIZATION","description":"能源组织","salience":9}],
                 "relationships":[{"source":"潮汐环能联合体","target":"实际放电时长","type":"HAS_METRIC",
                   "polarity":true,"status":"ASSERTED","description":"记录该指标","weight":5}]}
                """);

        assertFalse(result.contains("现金储备"));
        assertTrue(result.contains("实际放电时长"));
    }
}
