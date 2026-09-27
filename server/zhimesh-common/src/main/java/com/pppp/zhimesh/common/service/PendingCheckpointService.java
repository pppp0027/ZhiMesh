package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.AgentPendingCheckpoint;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.enums.PendingCheckpointStatus;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalGrant;
import com.pppp.zhimesh.common.languagemodel.tool.ChatMessageSnapshotCodec;
import com.pppp.zhimesh.common.mapper.AgentPendingCheckpointMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import dev.langchain4j.data.message.ChatMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 挂起检查点（adi_agent_pending_checkpoint）的 CRUD 服务：
 * <ul>
 *   <li>{@link #findActive(Long)}：查会话最新 ACTIVE 检查点，惰性过期
 *       （created_at + TTL 超时则条件置 EXPIRED 并返回 null，不设定时任务）；</li>
 *   <li>{@link #create(...)}：新挂起落库前先把同会话旧 ACTIVE 置 SUPERSEDED
 *       （先 UPDATE 后 INSERT，单实例语义，不加分布式锁）；</li>
 *   <li>{@link #consume(Long)}：幂等消费，仅 ACTIVE→CONSUMED 返回 true，
 *       依赖条件 UPDATE 的受影响行数，天然防并发双消费；</li>
 *   <li>{@link #markDeletedByConversation(Long)}：会话删除/清空级联，非终态置
 *       DELETED，防孤儿 ACTIVE。</li>
 * </ul>
 * 挂起/恢复循环本身（SSE 收尾、恢复注入、预算继承）不在此层，属 T4 的
 * AbstractLLMService/CharacterChatService 职责；本服务只管检查点行状态机。
 * <p>
 * CRUD service for agent pending checkpoints (adi_agent_pending_checkpoint):
 * findActive (lazy TTL expiry on read), create (supersedes the previous ACTIVE
 * row for the conversation before inserting — UPDATE-then-INSERT, single
 * instance semantics, no distributed lock), consume (idempotent via a
 * conditional UPDATE that only wins on ACTIVE), and
 * markDeletedByConversation (conversation-deletion cascade). The
 * suspend/resume loop itself (SSE wrap-up, resume injection, budget
 * inheritance) lives in T4's AbstractLLMService/CharacterChatService; this
 * service owns only the row state machine.
 */
@Slf4j
@Service
public class PendingCheckpointService extends ServiceImpl<AgentPendingCheckpointMapper, AgentPendingCheckpoint> {

    /**
     * 容器/配置不可用时的惰性过期 TTL 兜底值（小时），与
     * ZhiMeshProperties.Agent#pendingTtlHours 的默认值一致。
     * <p>
     * Fallback lazy-expiry TTL (hours) when the container or the properties
     * bean is unavailable; matches the ZhiMeshProperties.Agent#pendingTtlHours
     * default.
     */
    static final int FALLBACK_PENDING_TTL_HOURS = 24;

    @Resource
    private ZhiMeshProperties adiProperties;

    /**
     * 查询会话当前活跃（ACTIVE）的挂起检查点，按 id 取最新一条。
     * 命中后做惰性 TTL 校验：created_at + TTL 已过则条件置 EXPIRED 并返回 null
     * （新消息按无挂起的正常流程处理）；行已被并发消费/覆盖时置位失败也不影响
     * 返回 null 的正确性。
     * <p>
     * Returns the latest ACTIVE checkpoint of the conversation. On hit, lazily
     * checks the TTL: when created_at + TTL has passed, the row is flipped to
     * EXPIRED via a conditional UPDATE and null is returned (the new message
     * then takes the normal no-pending path). If the conditional UPDATE loses
     * a race, returning null is still correct.
     */
    public AgentPendingCheckpoint findActive(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        AgentPendingCheckpoint active = lambdaQuery()
                .eq(AgentPendingCheckpoint::getConversationId, conversationId)
                .eq(AgentPendingCheckpoint::getStatus, PendingCheckpointStatus.ACTIVE.getCode())
                .orderByDesc(AgentPendingCheckpoint::getId)
                .last("limit 1")
                .one();
        if (active == null) {
            return null;
        }
        if (isExpired(active)) {
            boolean flipped = casStatus(active.getId(), PendingCheckpointStatus.ACTIVE, PendingCheckpointStatus.EXPIRED);
            log.info("Pending checkpoint expired lazily, checkpointId:{}, conversationId:{}, flipped:{}",
                    active.getId(), conversationId, flipped);
            return null;
        }
        return active;
    }

    /**
     * 落一条新的 ACTIVE 挂起检查点：先把同会话旧 ACTIVE 置 SUPERSEDED 再 INSERT，
     * 维持“同一会话至多一个活跃挂起”的应用层约束（单实例语义即可，单会话消息流
     * 天然串行，不加分布式锁）。created_at 在插入时显式赋值——惰性 TTL 判定依赖它。
     * payload 为挂起载荷（ask_user 的 question/options、审批的 action/summary/
     * risk_level），Map 经 JsonUtil 序列化；messagesSnapshot 为消息链快照，经
     * {@link ChatMessageSnapshotCodec} 编码；approvalGrant 为审批批准凭证
     * （仅 MCP_APPROVAL 挂起携带：被拦截工具名 + 参数哈希，恢复轮装配读回 ToolContext
     * 供装饰器校验放行；其余 kind 传 null），经 JsonUtil 序列化为 approval_grant 列。
     * <p>
     * Inserts a new ACTIVE checkpoint: the previous ACTIVE row of the same
     * conversation is first flipped to SUPERSEDED (UPDATE-then-INSERT keeps the
     * at-most-one-active-per-conversation invariant; single-instance semantics
     * suffice, no distributed lock). created_at is set explicitly here because
     * the lazy TTL check depends on it. payload (question/options for ask_user,
     * action/summary/risk_level for approvals) is serialized via JsonUtil; the
     * message chain goes through {@link ChatMessageSnapshotCodec}; approvalGrant
     * is the approval grant (carried by MCP_APPROVAL suspensions only: the
     * intercepted tool name + arguments hash, read back into the ToolContext by
     * the resume assembly for the decorator's gate; null for every other kind),
     * serialized via JsonUtil into the approval_grant column.
     */
    public AgentPendingCheckpoint create(Long conversationId, Long characterId, Long userId,
                                         PendingCheckpointKind kind, String pendingToolName, String pendingRequestId,
                                         Map<String, Object> payload, List<ChatMessage> messagesSnapshot,
                                         Integer toolCallDepth, Integer suspensionCount,
                                         ApprovalGrant approvalGrant) {
        supersedeActive(conversationId);
        AgentPendingCheckpoint checkpoint = new AgentPendingCheckpoint();
        checkpoint.setUuid(UUID.randomUUID().toString());
        checkpoint.setConversationId(conversationId);
        checkpoint.setCharacterId(characterId);
        checkpoint.setUserId(userId);
        checkpoint.setKind(kind == null ? null : kind.getCode());
        checkpoint.setPendingToolName(pendingToolName);
        checkpoint.setPendingRequestId(pendingRequestId);
        checkpoint.setPayload(payload == null ? null : JsonUtil.toJson(payload));
        checkpoint.setMessagesSnapshot(ChatMessageSnapshotCodec.encode(messagesSnapshot));
        checkpoint.setToolCallDepth(toolCallDepth);
        checkpoint.setSuspensionCount(suspensionCount);
        checkpoint.setApprovalGrant(null == approvalGrant ? null : JsonUtil.toJson(approvalGrant));
        checkpoint.setStatus(PendingCheckpointStatus.ACTIVE.getCode());
        checkpoint.setCreatedAt(LocalDateTime.now());
        save(checkpoint);
        log.info("Pending checkpoint created, checkpointId:{}, conversationId:{}, kind:{}, pendingToolName:{}, toolCallDepth:{}, suspensionCount:{}, withGrant:{}",
                checkpoint.getId(), conversationId, checkpoint.getKind(), pendingToolName, toolCallDepth,
                suspensionCount, null != approvalGrant);
        return checkpoint;
    }

    /**
     * 幂等消费：仅当行仍为 ACTIVE 时置 CONSUMED 并返回 true，其余（已消费/已过期/
     * 已覆盖/已删除/不存在）一律返回 false。并发安全靠条件 UPDATE 的受影响行数，
     * 不会出现两轮恢复消费同一检查点。
     * <p>
     * Idempotent consume: flips ACTIVE → CONSUMED and returns true only when
     * the row is still ACTIVE; every other state (consumed/expired/superseded/
     * deleted/missing) returns false. Concurrency safety comes from the
     * conditional UPDATE's affected-row count, so the same checkpoint can never
     * be consumed by two resumes.
     */
    public boolean consume(Long id) {
        if (id == null) {
            return false;
        }
        return casStatus(id, PendingCheckpointStatus.ACTIVE, PendingCheckpointStatus.CONSUMED);
    }

    /**
     * 把一条仍处 ACTIVE 的检查点作废为 SUPERSEDED（regenerate 重问、检查点角色与当前
     * 会话角色不符、快照损坏 fail-safe）；行已进终态时条件迁移落空返回 false（幂等，
     * 无副作用）。挂起恢复前置检查（CharacterChatService.ask 前段）使用。
     * <p>
     * Invalidate a still-ACTIVE checkpoint to SUPERSEDED (regenerate re-ask,
     * checkpoint character no longer matching the conversation, or the
     * corrupt-snapshot fail-safe); when the row is already terminal the
     * conditional update misses and false is returned (idempotent, no side
     * effect). Used by the resume pre-check at the front of
     * CharacterChatService.ask.
     */
    public boolean markSuperseded(Long id) {
        if (id == null) {
            return false;
        }
        return casStatus(id, PendingCheckpointStatus.ACTIVE, PendingCheckpointStatus.SUPERSEDED);
    }

    /**
     * 会话删除/清空级联入口：把该会话全部非终态（当前仅 ACTIVE）检查点置 DELETED，
     * 防孤儿 ACTIVE 让已删会话的下一轮消息误入恢复流程。终态保持不变。
     * <p>
     * Conversation-deletion cascade: flips every non-terminal (currently only
     * ACTIVE) checkpoint of the conversation to DELETED, so a dangling ACTIVE
     * row cannot push the next message of a deleted conversation into the
     * resume path. Terminal states are left untouched.
     */
    public boolean markDeletedByConversation(Long conversationId) {
        if (conversationId == null) {
            return false;
        }
        boolean marked = lambdaUpdate()
                .eq(AgentPendingCheckpoint::getConversationId, conversationId)
                .eq(AgentPendingCheckpoint::getStatus, PendingCheckpointStatus.ACTIVE.getCode())
                .set(AgentPendingCheckpoint::getStatus, PendingCheckpointStatus.DELETED.getCode())
                .update();
        if (marked) {
            log.info("Pending checkpoints marked DELETED by conversation cascade, conversationId:{}", conversationId);
        }
        return marked;
    }

    private void supersedeActive(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        boolean superseded = lambdaUpdate()
                .eq(AgentPendingCheckpoint::getConversationId, conversationId)
                .eq(AgentPendingCheckpoint::getStatus, PendingCheckpointStatus.ACTIVE.getCode())
                .set(AgentPendingCheckpoint::getStatus, PendingCheckpointStatus.SUPERSEDED.getCode())
                .update();
        if (superseded) {
            log.info("Previous ACTIVE pending checkpoint superseded, conversationId:{}", conversationId);
        }
    }

    /**
     * 条件状态迁移（compare-and-set）：仅当行仍处 expect 状态时置为 target，
     * 返回是否迁移成功。所有终态化/消费迁移共用，保证幂等与并发安全。
     * <p>
     * Compare-and-set style transition: flips expect → target only when the row
     * is still in expect, returning whether it won. Shared by every
     * terminal/consume transition for idempotency and concurrency safety.
     */
    private boolean casStatus(Long id, PendingCheckpointStatus expect, PendingCheckpointStatus target) {
        return lambdaUpdate()
                .eq(AgentPendingCheckpoint::getId, id)
                .eq(AgentPendingCheckpoint::getStatus, expect.getCode())
                .set(AgentPendingCheckpoint::getStatus, target.getCode())
                .update();
    }

    /**
     * 惰性过期判定：created_at + TTL 是否已过。TTL 读
     * zhimesh.agent.pending-ttl-hours（ZhiMeshProperties.Agent#pendingTtlHours，
     * 默认 24）。created_at 由本服务插入时显式赋值，理论上非空；万一缺失按未过期
     * 处理（宁可不过期也不误杀活跃挂起）。
     * <p>
     * Lazy-expiry check: whether created_at + TTL has passed. The TTL comes
     * from zhimesh.agent.pending-ttl-hours
     * (ZhiMeshProperties.Agent#pendingTtlHours, default 24). created_at is set
     * explicitly on insert so it should never be null; if it somehow is, treat
     * the row as not expired rather than kill a live suspension by accident.
     */
    private boolean isExpired(AgentPendingCheckpoint checkpoint) {
        if (checkpoint.getCreatedAt() == null) {
            return false;
        }
        return checkpoint.getCreatedAt().plusHours(resolvePendingTtlHours()).isBefore(LocalDateTime.now());
    }

    /**
     * 读取挂起 TTL 配置；容器或配置 bean 不可用时回退默认值（对齐
     * AbstractLLMService#resolveAgentSettings 的防御口径）。
     * <p>
     * Resolve the pending TTL; falls back to the default when the container or
     * the properties bean is unavailable (same defensive posture as
     * AbstractLLMService#resolveAgentSettings).
     */
    private int resolvePendingTtlHours() {
        if (adiProperties != null && adiProperties.getAgent() != null) {
            return adiProperties.getAgent().getPendingTtlHours();
        }
        return FALLBACK_PENDING_TTL_HOURS;
    }
}
