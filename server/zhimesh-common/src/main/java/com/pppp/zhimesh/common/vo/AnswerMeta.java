package com.pppp.zhimesh.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class AnswerMeta {

    private Integer inputTokens;
    private Integer outputTokens;
    private String uuid;
    @Builder.Default
    private Integer duration = 0;
    @Builder.Default
    private Boolean isRefEmbedding = false;
    @Builder.Default
    private Boolean isRefGraph = false;
    @Builder.Default
    private Boolean isRefMemoryEmbedding = false;
    @Builder.Default
    private Boolean isRefBm25 = false;
    /**
     * Agentic 工具调用轨迹（可空）：仅当本次回答确有工具调用时随 meta 事件下发，
     * 非 Agentic 路径保持为 null 以维持旧载荷形状
     * <p>
     * Agentic tool-call traces (nullable): present on the meta event only when
     * the answer actually invoked tools; stays null on non-agentic paths to
     * preserve the legacy payload shape.
     */
    private List<ToolCallTrace> toolCalls;
}
