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
     * 是否为 MCP 工具包装；默认（mcp-guardrails-enabled=false）时 MCP 工具在循环层保持
     * 原有直调行为（不套超时、不截断结果），开关打开后与内置工具同受保护（T7）
     * <p>
     * Whether this wraps an MCP tool; by default (mcp-guardrails-enabled=false)
     * MCP tools keep the legacy direct-invocation behavior in the loop (no
     * timeout wrapping, no result truncation), and once the switch is on they
     * join the builtin guardrails (T7).
     */
    default boolean isMcpTool() {
        return false;
    }

    /**
     * 是否为协作类工具（ask_user / request_human_approval 等）：execute 的产物是
     * {@link ToolContext} 上的挂起信号而非普通文本结果，工具循环据此在挂起预算允许时
     * 走挂起分支（不递归调模型、落检查点、合成收尾），预算耗尽或未接线挂起回调时
     * 退回引导文本结果走正常循环。同时是「同轮多协作请求仅第一个挂起」的识别依据。
     * <p>
     * Whether this is a collaborative tool (ask_user / request_human_approval
     * etc.): execute yields a suspension signal on the {@link ToolContext}
     * instead of a plain text result. The tool loop uses the marker to route
     * into the suspension branch while the suspension budget allows (no further
     * model call, checkpoint persisted, synthesized wrap-up), and to fall back
     * to a guidance-text result when the budget is exhausted or no checkpoint
     * sink is wired. It is also how "only the first collaborative request in a
     * round suspends" is detected.
     */
    default boolean isCollaborative() {
        return false;
    }
}
