package com.pppp.zhimesh.common.languagemodel.data;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.vo.AnswerMeta;
import com.pppp.zhimesh.common.vo.PromptMeta;
import dev.langchain4j.agent.tool.ToolSpecification;
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
     * MCP tools discovered once for this streaming request. The same mapping is
     * reused when executing tool calls, including recursive tool-call rounds.
     */
    private Map<ToolSpecification, McpClient> toolSpecificationMcpClientMap;
    private StreamingChatModel streamingChatModel;
    private ChatRequest chatRequest;
    private Integer answerContentType;
    private TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> consumer;
    private User user;
    @Builder.Default
    private int toolCallDepth = 0;
}
