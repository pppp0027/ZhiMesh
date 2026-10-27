package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 角色工具策略：adi_character.tool_policy JSON 的运行时只读视图，决定内置工具的
 * 注册集合（默认集 ∩ policy ∩ 可用性）、暂存 MCP 工具的"需审批"标记（审批门由
 * 后续审批装饰器任务接线，此处只解析暴露），并携带 MCP 工具的参数域约束
 * argConstraints（工具名 → 参数名 → 约束表达式；校验装饰器由后续任务接线）。
 * <p>
 * 非 Spring bean，静态工厂风格，按请求从角色实体解析：
 * {@code {"builtinDenylist":["run_workflow"],"approvalRequiredMcpTools":["submit_expense_report"],"argConstraints":{"submit_leave_request":{"employee_name":"@caller"}}}}，
 * key 名与迁移 044/045 注释钉死的契约一致（{@link #KEY_BUILTIN_DENYLIST} /
 * {@link #KEY_APPROVAL_REQUIRED_MCP_TOOLS} / {@link #KEY_ARG_CONSTRAINTS}）。
 * argConstraints 当前唯一约束表达式 {@code "@caller"}（参数值必须等于当前对话
 * 用户名，仅限本人办理），仅约束 MCP 工具参数。NULL / 空白 → 默认策略（内置
 * 工具全允许、无审批门、无参数域约束）；非法 JSON → 同样回到默认策略并打 warn
 * 日志（fail-safe：策略配错绝不能阻断聊天，宁可全允许也不抛异常）；单个 key
 * 类型不符（前两键非字符串数组、argConstraints 非对象或其内层条目非对象/非文本）
 * 仅该 key 按空集处理，不影响其余 key。
 * <p>
 * Character tool policy: the runtime read-only view of the
 * adi_character.tool_policy JSON. It decides the builtin registration set
 * (default set ∩ policy ∩ availability), carries the approval-required
 * markers for MCP tools (the approval gate itself is wired by the later
 * approval-decorator task; this class only parses and exposes them), and
 * holds the argConstraints parameter-scope map for MCP tools (tool name →
 * parameter name → constraint expression; enforcement is wired by a later
 * decorator task).
 * <p>
 * Not a Spring bean; static-factory style, resolved per request from the
 * character entity:
 * {@code {"builtinDenylist":["run_workflow"],"approvalRequiredMcpTools":["submit_expense_report"],"argConstraints":{"submit_leave_request":{"employee_name":"@caller"}}}},
 * with key names pinned to the contract documented in migrations 044/045
 * ({@link #KEY_BUILTIN_DENYLIST} / {@link #KEY_APPROVAL_REQUIRED_MCP_TOOLS} /
 * {@link #KEY_ARG_CONSTRAINTS}). The only constraint expression today is
 * {@code "@caller"} (the parameter value must equal the current chat user;
 * self-service only) and it applies to MCP tool arguments only. NULL / blank
 * yields the default policy (all builtin tools allowed, no approval gate, no
 * argument-scope constraints); invalid JSON falls back to the same default
 * policy with a warn log (fail-safe: a misconfigured policy must never break
 * the chat — prefer allowing everything over throwing); a single wrong-typed
 * key (not an array of strings for the first two keys, or a non-object /
 * non-textual structure inside argConstraints) is treated as empty for that
 * key only, leaving the other keys intact.
 */
@Slf4j
public final class CharacterToolPolicy {

    /** tool_policy JSON key：内置工具禁用清单 / tool_policy JSON key: builtin tool denylist */
    public static final String KEY_BUILTIN_DENYLIST = "builtinDenylist";

    /** tool_policy JSON key：调用前需人工审批的 MCP 工具名清单 / tool_policy JSON key: MCP tool names requiring human approval before execution */
    public static final String KEY_APPROVAL_REQUIRED_MCP_TOOLS = "approvalRequiredMcpTools";

    /** tool_policy JSON key：MCP 工具参数域约束（工具名 → 参数名 → 约束表达式，当前唯一表达式 "@caller"）/ tool_policy JSON key: MCP argument-scope constraints (tool name → parameter name → constraint expression; the only expression today is "@caller") */
    public static final String KEY_ARG_CONSTRAINTS = "argConstraints";

    /** 默认策略（内置工具全允许、无审批门、无参数域约束）/ The default policy (all builtin tools allowed, no approval gate, no argument-scope constraints) */
    private static final CharacterToolPolicy DEFAULT = new CharacterToolPolicy(Set.of(), Set.of(), Map.of());

    private final Set<String> builtinDenylist;
    private final Set<String> approvalRequiredMcpTools;
    private final Map<String, Map<String, String>> argConstraints;

    private CharacterToolPolicy(Set<String> builtinDenylist, Set<String> approvalRequiredMcpTools,
                                Map<String, Map<String, String>> argConstraints) {
        this.builtinDenylist = Collections.unmodifiableSet(new HashSet<>(builtinDenylist));
        this.approvalRequiredMcpTools = Collections.unmodifiableSet(new HashSet<>(approvalRequiredMcpTools));
        this.argConstraints = unmodifiableCopy(argConstraints);
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
                readStringSet(root.get(KEY_APPROVAL_REQUIRED_MCP_TOOLS)),
                readArgConstraints(root.get(KEY_ARG_CONSTRAINTS)));
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
     * MCP 工具参数域约束（不可变嵌套 Map：工具名 → 参数名 → 约束表达式，当前
     * 唯一表达式 "@caller" 即"参数值必须等于当前对话用户名"；校验由后续
     * ArgScopeDecorator 任务接线，此处只解析暴露）
     * <p>
     * MCP argument-scope constraints (deeply immutable nested map: tool name
     * → parameter name → constraint expression, the only expression today
     * being "@caller" meaning "the value must equal the current chat user";
     * enforcement is wired by the later ArgScopeDecorator task, this class
     * only parses and exposes them).
     */
    public Map<String, Map<String, String>> getArgConstraints() {
        return argConstraints;
    }

    /**
     * 深拷贝并冻结嵌套 Map：外层与每个参数 Map 均不可变（策略是请求级只读视图，
     * 不允许下游原地改写）
     * <p>
     * Deep-copy and freeze the nested map: the outer map and every parameter
     * map are immutable (the policy is a per-request read-only view; downstream
     * in-place mutation is not allowed).
     */
    private static Map<String, Map<String, String>> unmodifiableCopy(Map<String, Map<String, String>> argConstraints) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        argConstraints.forEach((tool, params) -> copy.put(tool, Collections.unmodifiableMap(new LinkedHashMap<>(params))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 读取参数域约束 key：null / 非对象 / 工具条目非对象 / 参数值非文本一律跳过
     * （逐 key fail-safe，坏一处不连坐其余；无有效参数的工具条目整体丢弃）
     * <p>
     * Read the argument-constraints key: null / non-object nodes, non-object
     * tool entries, and non-textual values are skipped (per-key fail-safe —
     * one bad entry never takes down the others; a tool entry left with no
     * valid parameters is dropped entirely).
     */
    private static Map<String, Map<String, String>> readArgConstraints(JsonNode node) {
        if (null == node || !node.isObject()) {
            return Map.of();
        }
        Map<String, Map<String, String>> tools = new LinkedHashMap<>();
        node.fields().forEachRemaining(toolEntry -> {
            JsonNode params = toolEntry.getValue();
            if (null == params || !params.isObject()) {
                return;
            }
            Map<String, String> paramConstraints = new LinkedHashMap<>();
            params.fields().forEachRemaining(paramEntry -> {
                JsonNode expression = paramEntry.getValue();
                if (null != expression && expression.isTextual()) {
                    paramConstraints.put(paramEntry.getKey(), expression.asText());
                }
            });
            if (!paramConstraints.isEmpty()) {
                tools.put(toolEntry.getKey(), paramConstraints);
            }
        });
        return tools;
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
