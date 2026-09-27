package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色 Agent 挂起-恢复的待定检查点实体：ask_user / request_human_approval 等协作类工具
 * 触发挂起时，把当前消息链快照与循环预算落一行；下一轮用户消息进来据此恢复工具循环。
 * <p>
 * 生命周期（status 列，编码见 PendingCheckpointStatus，与 044 列注释一致）：
 * ACTIVE（挂起中，同一会话最多一条，由应用层保证）→ CONSUMED（已恢复消费）；
 * ACTIVE 亦可被惰性置 EXPIRED（读取时发现超过 TTL）、SUPERSEDED（新挂起覆盖 / regenerate 重问）、
 * DELETED（会话删除级联）。终态不可逆，consume 走条件 UPDATE 保证幂等。
 * created_at 由本服务在插入时显式赋值（DDL 亦有 CURRENT_TIMESTAMP 默认值兜底）；
 * updated_at 由 044 的 BEFORE UPDATE 触发器维护，Java 端只读不写。
 * 与姊妹表 adi_character_message_tool_call 一致，不建外键。
 * <p>
 * Pending checkpoint entity for the character-agent suspend/resume flow: when a
 * collaborative tool (ask_user / request_human_approval) suspends the tool loop,
 * a row is inserted carrying the message-chain snapshot and loop budgets; the
 * next user message resumes the loop from it.
 * <p>
 * Lifecycle (status column, uppercase codes — see PendingCheckpointStatus):
 * ACTIVE (suspended, at most one per conversation, enforced at the application
 * layer) → CONSUMED (resumed); ACTIVE may also be lazily flipped to EXPIRED
 * (TTL exceeded on read), SUPERSEDED (superseded by a newer suspension or a
 * regenerate re-ask), or DELETED (conversation-deletion cascade). Terminal
 * states are final; consume relies on a conditional UPDATE for idempotency.
 * created_at is set explicitly by this service on insert (the DDL also carries
 * a CURRENT_TIMESTAMP default as a safety net); updated_at is maintained by a
 * BEFORE UPDATE trigger from migration 044 and never written from Java. Like
 * the sibling table adi_character_message_tool_call, no foreign key is
 * declared.
 */
@Data
@TableName("adi_agent_pending_checkpoint")
@Schema(title = "AgentPendingCheckpoint对象")
public class AgentPendingCheckpoint implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(title = "检查点uuid | Checkpoint UUID")
    @TableField("uuid")
    private String uuid;

    @Schema(title = "会话id | Conversation ID")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(title = "角色id | Character ID")
    @TableField("character_id")
    private Long characterId;

    @Schema(title = "用户id | User ID")
    @TableField("user_id")
    private Long userId;

    @Schema(title = "挂起类型：ASK_USER / APPROVAL / MCP_APPROVAL | Suspension kind: ASK_USER / APPROVAL / MCP_APPROVAL")
    @TableField("kind")
    private String kind;

    @Schema(title = "触发挂起的工具名 | Tool name that triggered the suspension")
    @TableField("pending_tool_name")
    private String pendingToolName;

    @Schema(title = "挂起的工具请求id（ToolExecutionRequest.id） | Pending tool request ID (ToolExecutionRequest.id)")
    @TableField("pending_request_id")
    private String pendingRequestId;

    @Schema(title = "挂起载荷 JSON：ASK_USER=question/options；APPROVAL/MCP_APPROVAL=action/summary/risk_level | Suspension payload JSON: question/options for ASK_USER; action/summary/risk_level for APPROVAL/MCP_APPROVAL")
    @TableField("payload")
    private String payload;

    @Schema(title = "挂起时的消息链快照 JSON（ChatMessageSnapshotCodec 编码） | Message-chain snapshot JSON at suspension time (encoded by ChatMessageSnapshotCodec)")
    @TableField("messages_snapshot")
    private String messagesSnapshot;

    @Schema(title = "挂起时已耗工具迭代数（恢复后继续累计） | Tool-loop iterations already consumed at suspension (resumed run keeps accumulating)")
    @TableField("tool_call_depth")
    private Integer toolCallDepth;

    @Schema(title = "同一工具链已挂起次数（含本次，达 max-suspensions 后不再挂起） | Suspension count within the same tool chain (including this one; no further suspension once max-suspensions is reached)")
    @TableField("suspension_count")
    private Integer suspensionCount;

    @Schema(title = "审批凭证 JSON（toolName + argsHash，仅本恢复链有效；ASK_USER 挂起为空） | Approval grant JSON (toolName + argsHash, valid only within this resume chain; null for ASK_USER)")
    @TableField("approval_grant")
    private String approvalGrant;

    @Schema(title = "状态：ACTIVE/CONSUMED/EXPIRED/SUPERSEDED/DELETED | Status: ACTIVE/CONSUMED/EXPIRED/SUPERSEDED/DELETED")
    @TableField("status")
    private String status;

    @Schema(title = "创建时间（惰性 TTL 基准，插入时显式赋值） | Created time (lazy-TTL baseline, set explicitly on insert)")
    @TableField("created_at")
    private LocalDateTime createdAt;

    @Schema(title = "更新时间 | Updated time")
    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
