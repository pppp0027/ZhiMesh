package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 角色工具策略：adi_character.tool_policy JSON 的运行时只读视图，决定内置工具的
 * 注册集合（默认集 ∩ policy ∩ 可用性）并暂存 MCP 工具的"需审批"标记（审批门由
 * 后续审批装饰器任务接线，此处只解析暴露）。
 * <p>
 * 非 Spring bean，静态工厂风格，按请求从角色实体解析：
 * {@code {"builtinDenylist":["run_workflow"],"approvalRequiredMcpTools":["submit_expense_report"]}}，
 * key 名与迁移 044 注释钉死的契约一致（{@link #KEY_BUILTIN_DENYLIST} /
 * {@link #KEY_APPROVAL_REQUIRED_MCP_TOOLS}）。NULL / 空白 → 默认策略（内置工具全
 * 允许、无审批门）；非法 JSON → 同样回到默认策略并打 warn 日志（fail-safe：策略
 * 配错绝不能阻断聊天，宁可全允许也不抛异常）；单个 key 类型不符（非字符串数组）
 * 仅该 key 按空集处理，不影响其余 key。
 * <p>
 * Character tool policy: the runtime read-only view of the
 * adi_character.tool_policy JSON. It decides the builtin registration set
 * (default set ∩ policy ∩ availability) and carries the approval-required
 * markers for MCP tools (the approval gate itself is wired by the later
 * approval-decorator task; this class only parses and exposes them).
 * <p>
 * Not a Spring bean; static-factory style, resolved per request from the
 * character entity:
 * {@code {"builtinDenylist":["run_workflow"],"approvalRequiredMcpTools":["submit_expense_report"]}},
 * with key names pinned to the contract documented in migration 044
 * ({@link #KEY_BUILTIN_DENYLIST} / {@link #KEY_APPROVAL_REQUIRED_MCP_TOOLS}).
 * NULL / blank yields the default policy (all builtin tools allowed, no
 * approval gate); invalid JSON falls back to the same default policy with a
 * warn log (fail-safe: a misconfigured policy must never break the chat —
 * prefer allowing everything over throwing); a single wrong-typed key
 * (not an array of strings) is treated as empty for that key only, leaving
 * the other keys intact.
 */
@Slf4j
public final class CharacterToolPolicy {

    /** tool_policy JSON key：内置工具禁用清单 / tool_policy JSON key: builtin tool denylist */
    public static final String KEY_BUILTIN_DENYLIST = "builtinDenylist";

    /** tool_policy JSON key：调用前需人工审批的 MCP 工具名清单 / tool_policy JSON key: MCP tool names requiring human approval before execution */
    public static final String KEY_APPROVAL_REQUIRED_MCP_TOOLS = "approvalRequiredMcpTools";

    /** 默认策略（内置工具全允许、无审批门）/ The default policy (all builtin tools allowed, no approval gate) */
    private static final CharacterToolPolicy DEFAULT = new CharacterToolPolicy(Set.of(), Set.of());

    private final Set<String> builtinDenylist;
    private final Set<String> approvalRequiredMcpTools;

    private CharacterToolPolicy(Set<String> builtinDenylist, Set<String> approvalRequiredMcpTools) {
        this.builtinDenylist = Collections.unmodifiableSet(new HashSet<>(builtinDenylist));
        this.approvalRequiredMcpTools = Collections.unmodifiableSet(new HashSet<>(approvalRequiredMcpTools));
    }

    /** 默认策略：全允许、无审批门 / The default policy: everything allowed, no approval gate */
    public static CharacterToolPolicy defaultPolicy() {
        return DEFAULT;
    }

    /**
     * 从角色实体解析策略；character 或 toolPolicy 字段为 null/空白时得到默认策略。
     * <p>
     * Resolve the policy from the character entity; a null character or a
     * null/blank toolPolicy field yields the default policy.
     */
    public static CharacterToolPolicy fromCharacter(Character character) {
        return fromPolicyJson(null == character ? null : character.getToolPolicy());
    }

    /**
     * 解析 tool_policy JSON；空白或非法 JSON 回到默认策略（fail-safe 不抛异常，
     * 非法输入打 warn 日志便于定位配置错误）。
     * <p>
     * Parse the tool_policy JSON; blank or invalid JSON falls back to the
     * default policy (fail-safe, never throws; invalid input is warn-logged
     * so the misconfiguration is traceable).
     */
    public static CharacterToolPolicy fromPolicyJson(String policyJson) {
        if (StringUtils.isBlank(policyJson)) {
            return DEFAULT;
        }
        JsonNode root = JsonUtil.toJsonNode(policyJson);
        if (null == root || !root.isObject()) {
            log.warn("Invalid tool_policy JSON, falling back to the default policy (all builtin tools allowed), raw:{}",
                    policyJson);
            return DEFAULT;
        }
        return new CharacterToolPolicy(readStringSet(root.get(KEY_BUILTIN_DENYLIST)),
                readStringSet(root.get(KEY_APPROVAL_REQUIRED_MCP_TOOLS)));
    }

    /**
     * 内置工具是否允许注册：不在 denylist 内即允许（null 工具名视为允许，由调用方
     * 的可用性判定另行过滤）
     * <p>
     * Whether the builtin tool may be registered: allowed unless listed in
     * the denylist (a null tool name counts as allowed; availability is
     * judged separately by the caller).
     */
    public boolean isBuiltinAllowed(String toolName) {
        return null == toolName || !builtinDenylist.contains(toolName);
    }

    /**
     * 调用前需人工审批的 MCP 工具名集合（不可变；审批门由后续任务接线）
     * <p>
     * MCP tool names requiring human approval before execution (immutable;
     * the approval gate is wired by a later task).
     */
    public Set<String> getApprovalRequiredMcpTools() {
        return approvalRequiredMcpTools;
    }

    /** 内置工具禁用清单（不可变）/ The builtin denylist (immutable) */
    public Set<String> getBuiltinDenylist() {
        return builtinDenylist;
    }

    /**
     * 读取字符串数组 key：null / 非数组 / 非文本元素一律跳过（逐 key fail-safe，
     * 坏一个 key 不连坐其余 key）
     * <p>
     * Read a string-array key: null / non-array / non-textual elements are
     * skipped (per-key fail-safe — one bad key never takes down the others).
     */
    private static Set<String> readStringSet(JsonNode node) {
        if (null == node || !node.isArray()) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        for (JsonNode item : node) {
            if (null != item && item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }
}
