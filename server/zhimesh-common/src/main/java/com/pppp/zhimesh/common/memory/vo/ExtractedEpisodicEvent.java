package com.pppp.zhimesh.common.memory.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Single episodic event extracted from a conversation by the LLM.
 * <p>
 * 单条情景事件，由 LLM 从对话中提取。
 */
@Data
public class ExtractedEpisodicEvent {

    /**
     * 事件摘要文本。 | Event summary text.
     */
    private String summary;

    /**
     * 事件类型。LLM 判定，如 travel / health / work / general。 | Event type.
     */
    private String eventType;

    /**
     * 重要性 1-5。 | Importance 1-5.
     */
    private Integer importance;

    /** Normalized local occurrence time, or null when the turn has no reliable event time. */
    @JsonProperty("occurred_at")
    private String occurredAt;

    /** DATETIME / DAY / MONTH / YEAR / APPROXIMATE / UNKNOWN. */
    @JsonProperty("time_precision")
    private String timePrecision;

    /** Verbatim temporal phrase from the user, never synthesized by the model. */
    @JsonProperty("raw_time_expression")
    private String rawTimeExpression;
}
