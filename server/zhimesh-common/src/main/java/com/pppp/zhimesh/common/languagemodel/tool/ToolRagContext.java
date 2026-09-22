package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 内置工具可用的 RAG 检索上下文：由聊天入口（ask）按请求构造，随
 * {@link ToolContext} 传入工具执行循环。filteredKb 已经过
 * KnowledgeBaseAccessService 的鉴权过滤，是工具检索的唯一合法范围——
 * 工具只能在该范围内继续缩小（kbHint），绝不能扩大
 * <p>
 * RAG retrieval context available to builtin tools: constructed per request by
 * the chat entry (ask) and passed down through {@link ToolContext}.
 * filteredKb has already been authorization-filtered by
 * KnowledgeBaseAccessService and is the only legal retrieval scope for tools —
 * a tool may only narrow this scope (kbHint), never widen it.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolRagContext {

    /** 鉴权后当前角色绑定的可见知识库 / Visible character-bound knowledge bases after authorization */
    private List<KbInfoResp> filteredKb;

    /** 大模型服务 / LLM service */
    private AbstractLLMService llmService;

    /** 向量化模型 / Embedding model */
    private EmbeddingModel embeddingModel;
}
