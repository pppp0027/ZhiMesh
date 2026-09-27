package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.mcp.client.McpClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

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
    /**
     * 挂起恢复轮的消息链覆盖（可空）：非空时 createChatRequest 跳过常规的短期记忆
     * 窗口装配，直接以「检查点快照链 + 配对结果消息」作为本次请求的 messages；
     * 仅由挂起恢复装配（CharacterChatService）填充，其余调用方恒为 null
     * <p>
     * Message-chain override for a resumed round (nullable): when non-null,
     * createChatRequest skips the regular short-term-memory window assembly and
     * uses "checkpoint snapshot chain + paired result messages" as this
     * request's messages directly. Filled only by the suspension-resume
     * assembly (CharacterChatService); null for every other caller.
     */
    private List<ChatMessage> resumedMessages;
    /**
     * 恢复轮继承的工具迭代预算（可空）：InnerStreamChatParam.toolCallDepth 从该值起步
     * （跨挂起累计总迭代数的继承通道）；与 {@link #resumedMessages} 成对使用
     * <p>
     * Inherited tool-loop depth for a resumed round (nullable):
     * InnerStreamChatParam.toolCallDepth starts from this value (the
     * cross-suspension inheritance channel for the total iteration budget);
     * paired with {@link #resumedMessages}.
     */
    private Integer resumedToolCallDepth;
    /**
     * 调用前需人工审批的 MCP 工具名集合（可空）：来自角色 tool_policy 的
     * approvalRequiredMcpTools，由聊天入口写入；discoverRequestTools 对命中的 MCP 工具
     * 执行器包 ApprovalRequiredDecorator（未持批准凭证不执行真实调用，挂起转审批）。
     * 仅约束 MCP 工具，内置工具不受影响；为空 = 无审批门，行为不变
     * <p>
     * MCP tool names requiring human approval before execution (nullable):
     * the character tool_policy's approvalRequiredMcpTools, written by the
     * chat entry; discoverRequestTools wraps the hit MCP executors with
     * ApprovalRequiredDecorator (no real invocation without a matching grant —
     * the call suspends for approval instead). MCP tools only; builtin tools
     * are unaffected. Absent = no approval gate, unchanged behavior.
     */
    private Set<String> approvalRequiredTools;
    private String responseFormat;
    private Boolean returnThinking;
    private Boolean enableWebSearch;
}
