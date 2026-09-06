package com.pppp.zhimesh.common.languagemodel;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolRegistryTest {

    @Test
    void keepsFirstProviderWhenToolNamesCollide() {
        McpClient first = mock(McpClient.class);
        McpClient second = mock(McpClient.class);
        ToolSpecification firstSearch = tool("search", "first");
        ToolSpecification secondSearch = tool("search", "second");
        ToolSpecification unique = tool("lookup", "second");
        when(first.listTools()).thenReturn(List.of(firstSearch));
        when(second.listTools()).thenReturn(List.of(secondSearch, unique));
        List<String> duplicates = new ArrayList<>();

        Map<ToolSpecification, McpClient> tools = McpToolRegistry.discover(
                List.of(first, second), duplicates::add);

        assertThat(tools).hasSize(2);
        assertThat(tools.get(firstSearch)).isSameAs(first);
        assertThat(tools.get(unique)).isSameAs(second);
        assertThat(tools).doesNotContainKey(secondSearch);
        assertThat(duplicates).containsExactly("search");
        verify(first).listTools();
        verify(second).listTools();
    }

    private static ToolSpecification tool(String name, String description) {
        return ToolSpecification.builder().name(name).description(description).build();
    }
}
