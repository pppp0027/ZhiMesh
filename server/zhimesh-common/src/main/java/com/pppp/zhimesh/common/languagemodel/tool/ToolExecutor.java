package com.pppp.zhimesh.common.languagemodel.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * LLM 工具调用循环的统一执行器抽象，MCP 工具与内置工具共用同一执行入口
 * <p>
 * Unified executor abstraction for the LLM tool-calling loop; both MCP tools
 * and builtin tools (e.g. search_knowledge) share the same execution entry.
 */
public interface ToolExecutor {

    /**
     * 该执行器暴露给模型的工具规格（name 唯一标识，用于按名匹配执行）
     * <p>
     * The tool specification exposed to the model; the name uniquely
     * identifies the tool and is used for by-name executor resolution.
     */
    ToolSpecification spec();

    /**
     * 执行一次工具调用并返回结果文本；抛出的异常由调用循环统一转为失败结果消息
     * <p>
     * Execute one tool call and return the result text; thrown exceptions are
     * converted into failure result messages by the calling loop.
     */
    String execute(ToolExecutionRequest request, ToolContext context) throws Exception;

    /**
     * 是否为 MCP 工具包装；MCP 工具在循环层保持原有直调行为（不套超时、不截断结果）
     * <p>
     * Whether this wraps an MCP tool; MCP tools keep the legacy direct-invocation
     * behavior in the loop (no timeout wrapping, no result truncation).
     */
    default boolean isMcpTool() {
        return false;
    }
}
