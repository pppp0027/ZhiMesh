package com.pppp.zhimesh.common.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 挂起轮的 meta 载荷（AnswerMeta.suspension）：协作类工具把工具循环挂起时，随 META 事件
 * 下发的卡片渲染依据——类型/问题/检查点 uuid，加上按类型的补充字段（ask_user 的选项；
 * 审批类的 action/summary/riskLevel，可空省略）。形态对齐 {@link ToolCallTrace}（可空
 * 字段 + Builder），由聊天完成回调从 ToolContext.suspensionSignal 组装。
 * <p>
 * Meta payload of a suspending round (AnswerMeta.suspension): the card-rendering
 * basis shipped on the META event when a collaborative tool suspends the tool
 * loop — kind/question/checkpoint uuid plus kind-specific extras (ask_user's
 * options; the approval kinds' action/summary/riskLevel, omitted when null).
 * Shaped like {@link ToolCallTrace} (nullable fields + Builder) and assembled
 * by the chat completion callback from ToolContext.suspensionSignal.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SuspensionMeta {

    /** 挂起类型编码（PendingCheckpointKind.code，如 ASK_USER） / Suspension kind code (PendingCheckpointKind.code, e.g. ASK_USER) */
    private String type;

    /** 问题文本（= 挂起轮消息内容） / Question text (= the suspending round's message content) */
    private String question;

    /** 可选项列表（可空） / Choice options (nullable) */
    private List<String> options;

    /**
     * 审批动作名（可空，仅 APPROVAL/MCP_APPROVAL 携带）：审批卡片标题；ASK_USER 挂起为 null
     * <p>
     * Approval action (nullable, carried by APPROVAL/MCP_APPROVAL only): the
     * approval card's heading; null for ASK_USER suspensions.
     */
    private String action;

    /**
     * 审批摘要（可空，仅 APPROVAL/MCP_APPROVAL 携带）：操作说明或被拦截 MCP 调用的参数摘要；
     * ASK_USER 挂起为 null
     * <p>
     * Approval summary (nullable, carried by APPROVAL/MCP_APPROVAL only): the
     * action description or the intercepted MCP call's arguments digest; null
     * for ASK_USER suspensions.
     */
    private String summary;

    /**
     * 风险等级（可空，仅 APPROVAL 携带，request_human_approval 的 risk_level）：
     * 审批卡片风险标识；MCP 拦截挂起与 ASK_USER 为 null
     * <p>
     * Risk level (nullable, carried by APPROVAL only — request_human_approval's
     * risk_level): the approval card's risk badge; null for MCP interceptions
     * and ASK_USER.
     */
    private String riskLevel;

    /** 挂起检查点 uuid（恢复配对与前端刷新重放的定位依据） / Suspension checkpoint uuid */
    private String checkpointUuid;
}
