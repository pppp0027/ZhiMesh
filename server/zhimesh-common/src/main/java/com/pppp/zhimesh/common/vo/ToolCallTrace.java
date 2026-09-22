package com.pppp.zhimesh.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次工具调用的执行轨迹记录，由 ToolContext 按请求收集
 * <p>
 * Execution trace of a single tool call, collected per request by ToolContext.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolCallTrace {

    /** 工具名 / Tool name */
    private String toolName;

    /** 调用参数（JSON 字符串）/ Call arguments (JSON string) */
    private String args;

    /** 结果摘要（失败时为错误信息）/ Result summary (error message on failure) */
    private String resultSummary;

    /** 执行耗时（毫秒）/ Execution duration in milliseconds */
    private long durationMs;

    /** 是否执行成功 / Whether the call succeeded */
    private boolean success;

    /** 请求内递增序号，从 0 开始 / Sequence number within the request, starting at 0 */
    private int seq;
}
