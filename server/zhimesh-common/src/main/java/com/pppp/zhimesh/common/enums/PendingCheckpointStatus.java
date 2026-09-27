package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 挂起检查点（adi_agent_pending_checkpoint.status）的状态机。
 * 字符串编码直接落库（列是 VARCHAR 而非数值编码，快照域表数据量小、可读性优先），
 * 因此不实现 {@link BaseEnum}（那是 Integer 编码 + MyBatis-Plus IEnum 自动映射的口径）。
 * <ul>
 *   <li>ACTIVE：挂起中。同一会话最多一条，由 PendingCheckpointService 在应用层保证
 *       （新挂起先置旧 ACTIVE 为 SUPERSEDED 再插入，单实例语义，不加分布式锁）。</li>
 *   <li>CONSUMED：终态。恢复流程已消费（条件 UPDATE，幂等）。</li>
 *   <li>EXPIRED：终态。读取时惰性判定 created_at + TTL 超时后置位，不设定时任务。</li>
 *   <li>SUPERSEDED：终态。被更新的挂起覆盖，或 regenerate 视为重问作废旧挂起。</li>
 *   <li>DELETED：终态。会话删除/清空级联置位，防孤儿 ACTIVE。</li>
 * </ul>
 * <p>
 * Status machine for agent pending checkpoints (adi_agent_pending_checkpoint.status).
 * Codes are stored as-is in a VARCHAR column (readability first — this table is
 * small), so this enum intentionally does NOT implement {@link BaseEnum}
 * (the Integer-code + IEnum auto-mapping convention).
 * ACTIVE is the only non-terminal state: at most one per conversation,
 * enforced by PendingCheckpointService at the application layer; every other
 * state is terminal and irreversible.
 */
@Getter
@AllArgsConstructor
public enum PendingCheckpointStatus {

    /** 挂起中（唯一非终态）。 | Suspended (the only non-terminal state). */
    ACTIVE("ACTIVE"),

    /** 终态：已恢复消费。 | Terminal: consumed by the resume flow. */
    CONSUMED("CONSUMED"),

    /** 终态：惰性过期。 | Terminal: lazily expired (TTL exceeded on read). */
    EXPIRED("EXPIRED"),

    /** 终态：被新挂起/regenerate 覆盖。 | Terminal: superseded by a newer suspension or a regenerate re-ask. */
    SUPERSEDED("SUPERSEDED"),

    /** 终态：会话删除级联。 | Terminal: conversation-deletion cascade. */
    DELETED("DELETED");

    private final String code;

    /**
     * 落库编码反查。未知编码抛 IllegalArgumentException，避免静默写错状态机。
     * <p>
     * Resolve from the stored code. Unknown codes throw
     * IllegalArgumentException so the state machine never gets silently corrupted.
     */
    public static PendingCheckpointStatus fromCode(String code) {
        for (PendingCheckpointStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown pending checkpoint status code: " + code);
    }
}
