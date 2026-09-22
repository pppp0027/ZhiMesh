package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 请求级工具执行上下文：一次聊天请求构造一次，贯穿所有递归工具调用轮次，
 * 供内置工具获取当前用户/角色等信息并收集执行轨迹
 * <p>
 * Request-scoped tool execution context: constructed once per chat request and
 * shared across all recursive tool-call rounds. It lets builtin tools access the
 * current user/character and collects execution traces.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolContext {

    private User user;

    /** 当前角色ID，由调用方按请求填充 / Current character id, populated by the caller per request */
    private Long characterId;

    private String memoryId;

    /** 本次请求的工具调用轨迹收集器 / Collector of tool-call traces for this request */
    private List<ToolCallTrace> toolTraces;

    /**
     * RAG 检索上下文（鉴权后的知识库范围 + 检索依赖），由聊天入口按请求构造；
     * 内置检索工具（如 search_knowledge）从这里取检索依赖，filteredKb 即合法检索范围
     * <p>
     * RAG retrieval context (authorization-filtered KB scope plus retrieval
     * dependencies), constructed per request by the chat entry; builtin retrieval
     * tools (e.g. search_knowledge) read their dependencies from here, and
     * filteredKb is the legal retrieval scope.
     */
    private ToolRagContext ragContext;

    /**
     * 检索命中片段回流收集器（可空）：内置工具检索命中的 RetrieverWrapper 汇聚至此，
     * 供调用方合并进 adi_character_message_ref_*（引用弹窗语义）
     * <p>
     * Optional collector where builtin tools flow back hit RetrieverWrappers,
     * merged by the caller into adi_character_message_ref_* (citation-popup semantics).
     */
    private List<RetrieverWrapper> refCollector;
}
