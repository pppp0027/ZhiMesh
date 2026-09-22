package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import dev.langchain4j.mcp.client.McpClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 使用http与模型进行交互时需要用到的的参数
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatModelRequest {
    private String memoryId;
    /** Runtime-computed token limit for the complete short-term message window. */
    private Integer memoryWindowMaxTokens;
    private String systemMessage;
    private String userMessage;
    /**
     * Standalone query used only by RAG routing and retrieval. The answer model
     * still receives {@link #userMessage}, preserving the user's original turn.
     */
    private String retrievalQuery;
    /**
     * Raw user text persisted in short-term memory when {@link #userMessage}
     * contains request-scoped RAG or long-term-memory augmentation.
     */
    private String shortTermMemoryUserMessage;
    //Image URL, only effective for multimodal LLM
    private List<String> imageUrls;
    private List<McpClient> mcpClients;
    /**
     * 请求级内置工具（如 search_knowledge），与 MCP 工具按名合并后统一执行；可空，
     * 与 MCP 工具重名时内置工具优先
     * <p>
     * Request-scoped builtin tools (e.g. search_knowledge), merged with MCP tools
     * by name before unified execution; optional. Builtin tools win on name conflicts.
     */
    private List<ToolExecutor> builtinTools;
    private String responseFormat;
    private Boolean returnThinking;
    private Boolean enableWebSearch;
}
