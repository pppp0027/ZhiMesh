package com.pppp.zhimesh.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for executing a Character in agent mode.
 *
 * <p>Specifies which Character to execute and which capabilities to enable
 * (RAG, MCP tools, web search). Decoupled from workflow-specific configuration.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentRequest {

    /** Character UUID to execute. */
    private String characterUuid;

    /** Optional short-term memory key. Blank means stateless execution. */
    private String memoryId;

    /** Optional Conversation UUID. LocalAgentService validates ownership before using its memory key. */
    private String conversationUuid;

    /**
     * Whether LocalAgentService owns the complete memory turn. Callers that
     * persist the final answer themselves set false and hold the outer turn lock.
     */
    @Builder.Default
    private Boolean manageMemoryTurn = true;

    /** Model platform (optional, uses system default if null). */
    private String modelPlatform;

    /** Model name (optional, uses system default if null). */
    private String modelName;

    /** Input text (rendered prompt). */
    private String inputText;

    /** Enable RAG retrieval from Character's knowledge bases. */
    private boolean enableRag;

    /** Enable MCP tools configured on the Character. */
    private boolean enableMcp;

    /** Enable web search. */
    private boolean enableWebSearch;
}
