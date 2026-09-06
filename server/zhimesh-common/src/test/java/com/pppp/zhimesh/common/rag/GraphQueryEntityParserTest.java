package com.pppp.zhimesh.common.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQueryEntityParserTest {

    @Test
    void parsesStructuredJsonAndMarkdownWrappedJson() {
        GraphQueryEntityParser.ParseResult plain = GraphQueryEntityParser.parse(
                "{\"entities\":[{\"name\":\"知识图谱\"},{\"entity_name\":\"RAG\"}]}");
        GraphQueryEntityParser.ParseResult fenced = GraphQueryEntityParser.parse(
                "```json\n{\"entities\":[\"OpenAI\"]}\n```");

        assertEquals("json", plain.format());
        assertEquals(2, plain.entities().size());
        assertTrue(plain.entities().contains("知识图谱"));
        assertTrue(plain.entities().contains("RAG"));
        assertEquals("json", fenced.format());
        assertTrue(fenced.entities().contains("OPENAI"));
    }

    @Test
    void keepsLegacyFormatAsCompatibilityFallback() {
        String response = "(\"entity\"<|>Microsoft<|>ORGANIZATION<|>description)"
                + "##(\"relationship\"<|>Microsoft<|>OpenAI<|>description<|>9)<|COMPLETE|>";

        GraphQueryEntityParser.ParseResult result = GraphQueryEntityParser.parse(response);

        assertEquals("legacy", result.format());
        assertEquals(1, result.entities().size());
        assertTrue(result.entities().contains("MICROSOFT"));
    }

    @Test
    void reportsNonBlankUnparsedResponseWithoutExposingIt() {
        GraphQueryEntityParser.ParseResult result = GraphQueryEntityParser.parse("not valid output");

        assertTrue(result.responseNonBlank());
        assertEquals("unparsed", result.format());
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void distinguishesBlankResponse() {
        GraphQueryEntityParser.ParseResult result = GraphQueryEntityParser.parse(" ");

        assertFalse(result.responseNonBlank());
        assertEquals("blank", result.format());
        assertTrue(result.entities().isEmpty());
    }
}
