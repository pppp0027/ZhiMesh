package com.pppp.zhimesh.common.languagemodel.data;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import com.pppp.zhimesh.common.languagemodel.tool.ToolExecutor;
import com.pppp.zhimesh.common.vo.AnswerMeta;
import com.pppp.zhimesh.common.vo.PromptMeta;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Builder
@Data
public class InnerStreamChatParam {
    private String uuid;
    /**
     * SSE 请求标识，用于从注册中心获取 SseEmitter
     * <p>
     * SSE request identifier, used to look up SseEmitter from the registry.
     * </p>
     */
    private String sseUuid;
    private List<McpClient> mcpClients;
    /**
     * Tools discovered once for this streaming request, keyed by tool name and
     * covering both MCP and builtin executors. The same mapping is reused when
     * executing tool calls, including recursive tool-call rounds.
     */
    private Map<String, ToolExecutor> toolExecutorMap;
    /**
     * 请求级工具执行上下文，一次流式请求构造一次，贯穿所有递归工具调用轮次
     * <p>
     * Request-scoped tool execution context, constructed once per streaming
     * request and shared across all recursive tool-call rounds.
     */
    private ToolContext toolContext;
    private StreamingChatModel streamingChatModel;
    private ChatRequest chatRequest;
    private Integer answerContentType;
    private TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> consumer;
    private User user;
    @Builder.Default
    private int toolCallDepth = 0;
    /**
     * 工具循环达上限后的"无工具收尾轮"标记：置位后请求不再携带工具规格，模型只能
     * 直接作答；若收尾轮仍返回工具请求（提供商级异常）则按错误终止，绝不无限循环
     * <p>
     * Flag for the tool-less wrap-up round entered after the tool loop hits its
     * cap: the request carries no tool specifications so the model can only
     * answer directly; a wrap-up round that still returns tool requests
     * (provider-level anomaly) terminates as an error and never loops forever.
     */
    @Builder.Default
    private boolean toollessFinalRound = false;
}
