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

    /**
     * 挂起载荷（可空）：仅挂起轮（ask_user 等）随 meta 事件下发——类型/问题/选项/
     * 检查点 uuid，前端据此渲染问题卡片；历史回放的落库依据由 ask_user 轨迹行
     * （adi_character_message_tool_call）与消息行（remark=问题文本）承载。非挂起轮
     * 保持为 null 以维持旧载荷形状
     * <p>
     * Suspension payload (nullable): present on the meta event only in a
     * suspending round (ask_user etc.) — kind/question/options/checkpoint uuid,
     * from which the frontend renders the question card. History-replay
     * persistence rides the ask_user trace row
     * (adi_character_message_tool_call) and the message row (remark = the
     * question text). Null on every non-suspending round to preserve the
     * legacy payload shape.
     */
    private SuspensionMeta suspension;
}
