package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 协作类工具（ask_user 等）的挂起信号：工具 execute 时写入
 * {@link ToolContext#setSuspensionSignal(SuspensionSignal)}，工具循环据此不再递归调模型，
 * 而是落挂起检查点、发 agent_question 事件并合成收尾本轮 SSE。
 * <p>
 * 请求级生命周期：一次工具循环至多携带一个活跃信号——同轮多个协作请求时第一个挂起，
 * 其余按「已被忽略，请单独重新发起」在恢复轮注入结果（见 AbstractLLMService 的挂起分支）。
 * checkpointUuid 由内核在检查点落库后回填（工具与 CharacterChatService 的完成回调据此
 * 组装 AnswerMeta.suspension 载荷）。
 * <p>
 * Suspension signal raised by a collaborative tool (ask_user etc.): the tool's
 * execute writes it into {@link ToolContext#setSuspensionSignal(SuspensionSignal)};
 * the tool loop then stops recursing into the model, persists a pending
 * checkpoint, emits the agent_question event, and synthesizes the SSE wrap-up.
 * <p>
 * Request-scoped lifecycle: at most one live signal per tool loop — when the
 * model issues several collaborative requests in one round, the first suspends
 * and the rest receive an "ignored, re-issue alone" result injected on resume
 * (see the suspension branch in AbstractLLMService). The kernel backfills
 * checkpointUuid after the checkpoint row is persisted (the tool and the
 * CharacterChatService completion callback build the AnswerMeta.suspension
 * payload from it).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SuspensionSignal {

    /** 挂起类型（ASK_USER / APPROVAL / MCP_APPROVAL，见 PendingCheckpointKind） / Suspension kind (see PendingCheckpointKind) */
    private PendingCheckpointKind kind;

    /** 触发挂起的工具名（= ToolSpecification.name，如 ask_user） / Suspending tool name */
    private String toolName;

    /** 挂起的 ToolExecutionRequest.id（恢复轮配对 ToolExecutionResultMessage 用） / Suspended ToolExecutionRequest.id */
    private String requestId;

    /** 问题文本（合成收尾的消息内容、agent_question 载荷与轨迹摘要） / Question text */
    private String question;

    /** 可选候选项（前端渲染按钮，点选文本按普通聊天提交） / Optional choice buttons */
    private List<String> options;

    /**
     * 审批动作名（可空，仅 APPROVAL/MCP_APPROVAL 携带）：request_human_approval 的
     * action 参数，或 MCP 拦截挂起时派生的「调用工具 {toolName}」——审批卡片标题
     * <p>
     * Approval action (nullable, carried by APPROVAL/MCP_APPROVAL only):
     * request_human_approval's action argument, or the derived "invoke tool
     * {toolName}" for an intercepted MCP call — the approval card's heading.
     */
    private String action;

    /**
     * 审批摘要（可空，仅 APPROVAL/MCP_APPROVAL 携带）：request_human_approval 的
     * summary 参数，或 MCP 拦截挂起时的参数摘要——审批卡片正文
     * <p>
     * Approval summary (nullable, carried by APPROVAL/MCP_APPROVAL only):
     * request_human_approval's summary argument, or the arguments digest for
     * an intercepted MCP call — the approval card's body.
     */
    private String summary;

    /**
     * 风险等级（可空，仅 APPROVAL/MCP_APPROVAL 携带，request_human_approval 的
     * risk_level 参数；MCP 拦截挂起无声明等级时为空）——审批卡片强调标识
     * <p>
     * Risk level (nullable, carried by APPROVAL/MCP_APPROVAL only; the
     * risk_level argument of request_human_approval; null for an intercepted
     * MCP call with no declared level) — the approval card's risk badge.
     */
    private String riskLevel;

    /**
     * 审批批准凭证（可空，仅 MCP_APPROVAL 携带）：被拦截 MCP 工具的「工具名 + 参数哈希」，
     * 随检查点 approval_grant 落库；恢复轮读回 ToolContext，模型重调同工具同参数时
     * 装饰器据此放行。APPROVAL（显式审批工具）无后续真实调用，恒为空
     * <p>
     * Approval grant (nullable, carried by MCP_APPROVAL only): the intercepted
     * MCP tool's "tool name + arguments hash", persisted as the checkpoint's
     * approval_grant; the resume assembly reads it back into the ToolContext,
     * and the decorator lets the model's re-invocation of the same tool with
     * the same arguments through on an exact match. APPROVAL (the explicit
     * approval tool) has no real invocation behind it and always stays null.
     */
    private ApprovalGrant approvalGrant;

    /** 检查点 uuid，内核落库后回填 / Checkpoint uuid, backfilled by the kernel after persistence */
    private String checkpointUuid;
}
