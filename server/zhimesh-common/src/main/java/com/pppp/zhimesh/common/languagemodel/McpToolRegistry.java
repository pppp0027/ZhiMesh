package com.pppp.zhimesh.common.languagemodel;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Deterministically keeps the first provider when MCP tool names collide. */
final class McpToolRegistry {

    private McpToolRegistry() {
    }

    static Map<ToolSpecification, McpClient> discover(
            List<McpClient> clients, Consumer<String> duplicateNameConsumer) {
        Map<ToolSpecification, McpClient> tools = new LinkedHashMap<>();
        if (clients == null || clients.isEmpty()) {
            return tools;
        }
        Set<String> names = new LinkedHashSet<>();
        for (McpClient client : clients) {
            for (ToolSpecification tool : client.listTools()) {
                if (!names.add(tool.name())) {
                    duplicateNameConsumer.accept(tool.name());
                    continue;
                }
                tools.put(tool, client);
            }
        }
        return tools;
    }
}
