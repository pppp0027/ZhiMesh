package com.pppp.zhimesh.common.languagemodel.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

/**
 * MCP 工具的 {@link ToolExecutor} 包装：工具规格来自现有 MCP 工具发现，
 * 执行委托给底层 {@link McpClient}，与改造前行为保持一致
 * <p>
 * {@link ToolExecutor} wrapper for MCP tools: the specification comes from the
 * existing MCP tool discovery, and execution is delegated to the underlying
 * {@link McpClient}, preserving the pre-refactor behavior.
 */
public class McpToolExecutor implements ToolExecutor {

    private final ToolSpecification spec;
    private final McpClient client;

    public McpToolExecutor(ToolSpecification spec, McpClient client) {
        this.spec = spec;
        this.client = client;
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        return client.executeTool(request).resultText();
    }

    @Override
    public boolean isMcpTool() {
        return true;
    }
}
