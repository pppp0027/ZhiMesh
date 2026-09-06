package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.vo.GraphVertex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphEntityNameMatcherTest {

    @Test
    void matchesNormalizedAndAliasEntityNames() {
        List<GraphVertex> candidates = List.of(
                GraphVertex.builder().id("1").name("OpenAI Inc").build(),
                GraphVertex.builder().id("2").name("Anthropic").build());

        List<GraphVertex> matches = GraphEntityNameMatcher.match(List.of("Open AI"), candidates, 3);

        assertEquals(List.of("OpenAI Inc"), matches.stream().map(GraphVertex::getName).toList());
    }

    @Test
    void excludesUnrelatedEntityNames() {
        List<GraphVertex> candidates = List.of(
                GraphVertex.builder().id("1").name("Anthropic").build());

        List<GraphVertex> matches = GraphEntityNameMatcher.match(List.of("OpenAI"), candidates, 3);

        assertEquals(List.of(), matches);
    }

    @Test
    void matchesEntitiesMentionedDirectlyInQuestionAndPrefersLongerNames() {
        List<GraphVertex> candidates = List.of(
                GraphVertex.builder().id("1").name("赤脊").build(),
                GraphVertex.builder().id("2").name("赤脊七型").build(),
                GraphVertex.builder().id("3").name("浮灯云网").build());

        List<GraphVertex> matches = GraphEntityNameMatcher.matchDirectMentions(
                "赤脊七型采用了哪家公司的控制器？", candidates, 2);

        assertEquals(List.of("赤脊七型", "赤脊"), matches.stream().map(GraphVertex::getName).toList());
    }

    @Test
    void matchesCanonicalVertexBySourceBackedAlias() {
        GraphVertex vertex = GraphVertex.builder()
                .id("1")
                .name("曜穹机器人股份公司")
                .canonicalName("曜穹机器人股份公司")
                .aliases(List.of("曜穹机器人"))
                .build();

        assertEquals(List.of(vertex), GraphEntityNameMatcher.matchDirectMentions(
                "曜穹机器人发布了什么产品？", List.of(vertex), 3));
        assertEquals(List.of(vertex), GraphEntityNameMatcher.match(
                List.of("曜穹机器人"), List.of(vertex), 3));
    }
}
