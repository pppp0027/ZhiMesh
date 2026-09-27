package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.util.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审批批准凭证：tool_policy 标记"需审批"的 MCP 工具挂起转审批时随检查点
 * （adi_agent_pending_checkpoint.approval_grant JSON）落库的一对「工具名 + 参数哈希」，
 * 恢复轮由装配读回 ToolContext。模型在恢复链内重调同工具且参数哈希精确匹配时装饰器
 * 才放行真实 MCP 调用；参数变了哈希不匹配 → 再次挂起重新审批（防提示注入改参数绕审批）。
 * 凭证只随请求级上下文存活 = 仅本恢复链有效：新一轮普通请求装配全新上下文、无凭证，
 * 需审批工具照常重新走审批。
 * <p>
 * 哈希口径（design.md 未明确规定，取最保守可行解）：对参数 JSON 的<b>规范形</b>做
 * SHA-256 十六进制——先递归按 key 字典序重排（模型重调时同语义参数的 key 顺序不保证
 * 稳定，原始串哈希会造成「刚批准又要再批」的假阴性），再取紧凑序列化的 SHA-256；
 * 参数空白按空串、非法 JSON 按原始串处理（两种退化都不影响安全性：不同内容必不同哈希）。
 * <p>
 * Approval grant persisted with the checkpoint (as the
 * adi_agent_pending_checkpoint.approval_grant JSON) when an approval-required
 * MCP tool (marked via tool_policy) suspends for approval: a "toolName +
 * arguments hash" pair read back into the ToolContext by the resume assembly.
 * Only an exact toolName + argsHash match on the model's re-invocation within
 * the resumed chain lets the decorator run the real MCP call; changed
 * arguments produce a different hash and re-suspend for another approval
 * (the prompt-injection args-mutation bypass guard). The grant lives only as
 * long as the request-scoped context — valid solely within this resume chain:
 * a fresh ordinary request assembles a fresh context with no grant, so the
 * approval-required tool goes through approval again.
 * <p>
 * Hash convention (design.md prescribes none; the most conservative workable
 * choice): SHA-256 hex over the <b>canonical form</b> of the arguments JSON —
 * object keys are recursively sorted first (providers do not guarantee a
 * stable key order on the model's re-invocation, and hashing the raw string
 * would false-negative into "just approved, approval demanded again"), then
 * the compact serialization is hashed; blank arguments hash the empty string
 * and invalid JSON falls back to the raw string (neither degenerate case
 * weakens safety: distinct content always yields a distinct hash).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApprovalGrant {

    /** 被批准执行的 MCP 工具名（与 ToolSpecification.name 精确匹配） / Approved MCP tool name (exact ToolSpecification.name match) */
    private String toolName;

    /** 被批准参数 JSON 的 SHA-256 十六进制（规范形，见类注释） / SHA-256 hex of the approved arguments JSON (canonical form, see class javadoc) */
    private String argsHash;

    /**
     * 由工具名 + 参数原文构造凭证（挂起时由装饰器调用，随信号经检查点落库）
     * <p>
     * Build a grant from the tool name plus the raw arguments (called by the
     * decorator at suspension time; persisted with the signal's checkpoint).
     */
    public static ApprovalGrant forArguments(String toolName, String arguments) {
        return ApprovalGrant.builder()
                .toolName(toolName)
                .argsHash(hashArguments(arguments))
                .build();
    }

    /**
     * 本凭证是否精确覆盖「该工具名 + 该参数」：工具名相等且参数哈希相等（放行的唯一依据，
     * 任一不匹配即视为未批准）
     * <p>
     * Whether this grant exactly covers "this tool with these arguments":
     * equal tool names and equal argument hashes (the sole basis for letting
     * the call through; any mismatch counts as not approved).
     */
    public boolean matches(String toolName, String arguments) {
        return StringUtils.equals(this.toolName, toolName)
                && StringUtils.equals(this.argsHash, hashArguments(arguments));
    }

    /**
     * 参数 JSON 的规范形哈希：可解析 JSON 递归按 key 字典序重排后取紧凑序列化；空白参数按
     * 空串；非法 JSON 按原始串（原始串同样确定性，不同内容必不同哈希）
     * <p>
     * Canonical-form hash of the arguments JSON: parseable JSON is recursively
     * key-sorted then compactly serialized; blank arguments hash the empty
     * string; invalid JSON hashes the raw string (equally deterministic —
     * distinct content always yields a distinct hash).
     */
    public static String hashArguments(String arguments) {
        return sha256Hex(canonicalize(arguments));
    }

    /**
     * 从检查点 approval_grant JSON 解析凭证；空白/损坏 JSON 返回 null（fail-safe：无凭证 =
     * 未批准，需审批工具重新挂起审批，绝不把坏凭证放行为已批准）
     * <p>
     * Parse a grant from the checkpoint's approval_grant JSON; blank or corrupt
     * JSON yields null (fail-safe: no grant = not approved, the
     * approval-required tool suspends for approval again — a corrupt grant must
     * never be treated as an approval).
     */
    public static ApprovalGrant fromJson(String grantJson) {
        if (StringUtils.isBlank(grantJson)) {
            return null;
        }
        return JsonUtil.fromJson(grantJson, ApprovalGrant.class);
    }

    /** 序列化为检查点 JSON 列内容 / Serialize into the checkpoint JSON column */
    public String toJson() {
        return JsonUtil.toJson(this);
    }

    /**
     * 递归构造规范形：对象按 key 字典序重排（值递归处理），数组保持元素顺序仅递归元素，
     * 标量原样。解析失败返回原始串
     * <p>
     * Recursively build the canonical form: object keys dictionary-sorted
     * (values recursed), arrays keep element order while recursing into
     * elements, scalars pass through. Unparseable input returns the raw string.
     */
    private static String canonicalize(String arguments) {
        if (StringUtils.isBlank(arguments)) {
            return "";
        }
        JsonNode node = JsonUtil.toJsonNode(arguments);
        if (null == node) {
            return arguments;
        }
        return sortDeep(node).toString();
    }

    private static JsonNode sortDeep(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonUtil.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                // fieldNames() 产出的字段必在节点上，get 不会返回 Java null
                // A field from fieldNames() always exists on the node, so get
                // never returns Java null here
                sorted.set(name, sortDeep(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonUtil.createArrayNode();
            for (JsonNode item : node) {
                sorted.add(sortDeep(item));
            }
            return sorted;
        }
        return node;
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((null == content ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String digits = Integer.toHexString(0xff & b);
                if (digits.length() < 2) {
                    hex.append('0');
                }
                hex.append(digits);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范强制提供 SHA-256；理论不可达，显式失败好过静默放行
            // The JDK spec mandates SHA-256; theoretically unreachable — failing
            // explicitly beats silently approving
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
