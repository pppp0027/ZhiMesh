package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 挂起检查点（adi_agent_pending_checkpoint.kind）的挂起类型，取值与
 * 044 迁移列注释一致。字符串编码直接落库（与 PendingCheckpointStatus 同口径，
 * 不实现 {@link BaseEnum}）。
 * <ul>
 *   <li>ASK_USER：ask_user 澄清提问挂起，payload 为 question/options；</li>
 *   <li>APPROVAL：request_human_approval 显式审批挂起，payload 为
 *       action/summary/risk_level；</li>
 *   <li>MCP_APPROVAL：被 tool_policy 标记“需审批”的 MCP 工具由装饰器拦截转审批，
 *       payload 同 APPROVAL，pending_tool_name 记被包装的 MCP 工具名。</li>
 * </ul>
 * <p>
 * Suspension kind for agent pending checkpoints
 * (adi_agent_pending_checkpoint.kind), values aligned with the 044 migration
 * column comment. Codes are stored as-is in a VARCHAR column (same convention
 * as PendingCheckpointStatus, no {@link BaseEnum}).
 */
@Getter
@AllArgsConstructor
public enum PendingCheckpointKind {

    /** ask_user 澄清提问挂起（question/options 载荷）。 | ask_user clarification suspension (question/options payload). */
    ASK_USER("ASK_USER"),

    /** request_human_approval 显式审批挂起（action/summary/risk_level 载荷）。 | Explicit request_human_approval suspension (action/summary/risk_level payload). */
    APPROVAL("APPROVAL"),

    /** 需审批 MCP 工具被装饰器拦截转审批（payload 同 APPROVAL，工具名为被包装的 MCP 工具）。 | Approval-required MCP tool intercepted by the decorator (payload as APPROVAL; tool name is the wrapped MCP tool). */
    MCP_APPROVAL("MCP_APPROVAL");

    private final String code;

    /**
     * 落库编码反查。未知编码抛 IllegalArgumentException。
     * <p>
     * Resolve from the stored code. Unknown codes throw
     * IllegalArgumentException.
     */
    public static PendingCheckpointKind fromCode(String code) {
        for (PendingCheckpointKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown pending checkpoint kind code: " + code);
    }
}
