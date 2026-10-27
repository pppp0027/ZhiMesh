package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.entity.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2 工具策略解析的行为验证：默认策略（null/空白）、非法 JSON fail-safe（不抛异常
 * + 全允许 + 行为可断言）、denylist 生效、approvalRequiredMcpTools 解析、混合 JSON
 * 两 key 共存、key 常量与迁移 044 契约一致、返回集合不可变。
 * <p>
 * Behavioral verification for the T2 tool-policy parsing: the default policy
 * (null/blank), invalid-JSON fail-safe (no exception + allow-all + assertable
 * behavior), denylist enforcement, approvalRequiredMcpTools parsing, both
 * keys coexisting in one JSON, key constants pinned to the migration-044
 * contract, and immutability of the returned sets.
 */
class CharacterToolPolicyTest {

    // ==================== 默认策略 / Default policy ====================

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void nullOrBlankPolicyJsonYieldsDefaultAllowAll(String raw) {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(raw);

        assertThat(policy.isBuiltinAllowed("search_knowledge")).isTrue();
        assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
        // 未列举的（含未来的 ask_user 等协作类工具名）默认允许
        // Unlisted names (incl. future collaborative tools like ask_user) default to allowed
        assertThat(policy.isBuiltinAllowed("ask_user")).isTrue();
        assertThat(policy.isBuiltinAllowed(null)).isTrue();
        assertThat(policy.getApprovalRequiredMcpTools()).isEmpty();
        assertThat(policy.getBuiltinDenylist()).isEmpty();
    }

    @Test
    void nullCharacterOrBlankToolPolicyFieldYieldsDefault() {
        CharacterToolPolicy fromNull = CharacterToolPolicy.fromCharacter(null);
        Character blank = new Character();
        CharacterToolPolicy fromBlankField = CharacterToolPolicy.fromCharacter(blank);

        for (CharacterToolPolicy policy : java.util.Arrays.asList(fromNull, fromBlankField)) {
            assertThat(policy.isBuiltinAllowed("search_knowledge")).isTrue();
            assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
            assertThat(policy.getApprovalRequiredMcpTools()).isEmpty();
        }
    }

    // ==================== 非法 JSON fail-safe / Invalid JSON fail-safe ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json{",                      // 词法损坏 / lexically broken
            "{\"builtinDenylist\":",          // 截断 / truncated
            "[]",                             // 合法 JSON 但根不是对象 / valid JSON, non-object root
            "\"plain string\"",               // 标量根 / scalar root
            "42"
    })
    void invalidJsonFallsBackToDefaultPolicyWithoutThrowing(String raw) {
        // fail-safe：策略配错绝不阻断聊天——不抛异常，全允许、无审批门，行为可断言
        // fail-safe: a misconfigured policy must never break the chat — no
        // exception, everything allowed, no approval gate, and assertable
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(raw);

        assertThat(policy.isBuiltinAllowed("search_knowledge")).isTrue();
        assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
        assertThat(policy.getApprovalRequiredMcpTools()).isEmpty();
    }

    @Test
    void invalidJsonThroughCharacterPathAlsoFallsBackToDefault() {
        Character character = new Character();
        character.setToolPolicy("{\"builtinDenylist\":[\"run_workflow\""); // 截断 JSON / truncated

        CharacterToolPolicy policy = CharacterToolPolicy.fromCharacter(character);

        assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
        assertThat(policy.getApprovalRequiredMcpTools()).isEmpty();
    }

    // ==================== denylist 生效 / Denylist enforcement ====================

    @Test
    void denylistBlocksListedToolOnly() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson("{\"builtinDenylist\":[\"run_workflow\"]}");

        assertThat(policy.isBuiltinAllowed("run_workflow")).isFalse();
        // 摘除只影响被点名的工具，其余内置与未知名照常允许
        // Only the named tool is removed; the other builtins and unknown names stay allowed
        assertThat(policy.isBuiltinAllowed("search_knowledge")).isTrue();
        assertThat(policy.getApprovalRequiredMcpTools()).isEmpty();
        assertThat(policy.getBuiltinDenylist()).containsExactly("run_workflow");
    }

    // ==================== approvalRequiredMcpTools 解析 / Approval-marker parsing ====================

    @Test
    void approvalRequiredMcpToolsParsedWithoutTouchingBuiltinGate() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"approvalRequiredMcpTools\":[\"submit_expense_report\",\"query_budget\"]}");

        assertThat(policy.getApprovalRequiredMcpTools())
                .containsExactlyInAnyOrder("submit_expense_report", "query_budget");
        // 审批标记不影响内置注册判定（两者是独立维度；审批门由 T5 接线）
        // The approval markers never affect the builtin gate (independent
        // dimensions; the approval gate is wired by T5)
        assertThat(policy.isBuiltinAllowed("submit_expense_report")).isTrue();
        assertThat(policy.isBuiltinAllowed("search_knowledge")).isTrue();
        assertThat(policy.getBuiltinDenylist()).isEmpty();
    }

    // ==================== 混合 JSON 两 key 共存 / Both keys in one JSON ====================

    @Test
    void bothKeysCoexistInOnePolicyJson() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"builtinDenylist\":[\"search_knowledge\"],\"approvalRequiredMcpTools\":[\"submit_expense_report\"]}");

        assertThat(policy.isBuiltinAllowed("search_knowledge")).isFalse();
        assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
        assertThat(policy.getApprovalRequiredMcpTools()).containsExactly("submit_expense_report");
    }

    @Test
    void wrongTypedKeyIsIgnoredWhileSiblingKeyStillParses() {
        // 单个 key 类型不符（应为字符串数组）只按空集处理，不连坐其余 key
        // A single wrong-typed key (expecting a string array) is treated as
        // empty without taking down its sibling key
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"builtinDenylist\":\"run_workflow\",\"approvalRequiredMcpTools\":[\"submit_expense_report\"],"
                        + "\"unknownKey\":123}");

        assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
        assertThat(policy.getApprovalRequiredMcpTools()).containsExactly("submit_expense_report");
    }

    @Test
    void nonTextualArrayElementsAreSkippedAndDuplicatesCollapse() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"builtinDenylist\":[\"run_workflow\",42,null,\"run_workflow\"]}");

        assertThat(policy.getBuiltinDenylist()).containsExactly("run_workflow");
        assertThat(policy.isBuiltinAllowed("run_workflow")).isFalse();
    }

    // ==================== 契约与不可变 / Contract pinning and immutability ====================

    @Test
    void keyConstantsArePinnedToMigration044Contract() {
        // 常量与迁移 044 / 044 注释钉死的契约一致；用常量拼出策略 JSON 回环解析验证
        // The constants match the contract pinned by migration 044; round-trip
        // a policy JSON built from the constants
        assertThat(CharacterToolPolicy.KEY_BUILTIN_DENYLIST).isEqualTo("builtinDenylist");
        assertThat(CharacterToolPolicy.KEY_APPROVAL_REQUIRED_MCP_TOOLS).isEqualTo("approvalRequiredMcpTools");

        String json = String.format("{\"%s\":[\"run_workflow\"],\"%s\":[\"submit_expense_report\"]}",
                CharacterToolPolicy.KEY_BUILTIN_DENYLIST, CharacterToolPolicy.KEY_APPROVAL_REQUIRED_MCP_TOOLS);
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(json);

        assertThat(policy.isBuiltinAllowed("run_workflow")).isFalse();
        assertThat(policy.getApprovalRequiredMcpTools()).containsExactly("submit_expense_report");
    }

    @Test
    void returnedSetsAreImmutable() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"builtinDenylist\":[\"run_workflow\"],\"approvalRequiredMcpTools\":[\"submit_expense_report\"]}");

        assertThatThrownBy(() -> policy.getBuiltinDenylist().add("search_knowledge"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.getApprovalRequiredMcpTools().add("another_tool"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== argConstraints 解析 / argConstraints parsing ====================

    @Test
    void argConstraintsParsedForSingleToolAndParam() {
        // 045 人事/财务种子形态：工具名 → 参数名 → 约束表达式（当前唯一 "@caller"）
        // The 045 HR/finance seed shape: tool name → parameter name → constraint
        // expression (the only expression today is "@caller")
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{\"submit_leave_request\":{\"employee_name\":\"@caller\"}}}");

        assertThat(policy.getArgConstraints())
                .containsExactly(Map.entry("submit_leave_request", Map.of("employee_name", "@caller")));
        // 参数域约束与其余两键正交：不点亮 denylist、不点亮审批标记
        // Argument-scope constraints are orthogonal to the other two keys
        assertThat(policy.getBuiltinDenylist()).isEmpty();
        assertThat(policy.getApprovalRequiredMcpTools()).isEmpty();
    }

    @Test
    void nestedMultipleToolsAndParamsParseIndependently() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{"
                        + "\"submit_leave_request\":{\"employee_name\":\"@caller\"},"
                        + "\"submit_expense_report\":{\"employee_name\":\"@caller\",\"other_param\":\"@caller\"}}}");

        assertThat(policy.getArgConstraints()).hasSize(2);
        assertThat(policy.getArgConstraints().get("submit_leave_request"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("employee_name", "@caller"));
        assertThat(policy.getArgConstraints().get("submit_expense_report"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("employee_name", "@caller", "other_param", "@caller"));
    }

    @Test
    void missingArgConstraintsKeyYieldsEmptyMap() {
        // 缺 key（JSON 只有别的键）、null 输入、实体字段空白 → 均为空 Map
        // Missing key, null input, and a blank entity field all yield an empty map
        CharacterToolPolicy withoutKey = CharacterToolPolicy.fromPolicyJson(
                "{\"builtinDenylist\":[\"run_workflow\"]}");
        Character blank = new Character();

        assertThat(withoutKey.getArgConstraints()).isEmpty();
        assertThat(CharacterToolPolicy.fromPolicyJson(null).getArgConstraints()).isEmpty();
        assertThat(CharacterToolPolicy.fromCharacter(blank).getArgConstraints()).isEmpty();
        assertThat(CharacterToolPolicy.defaultPolicy().getArgConstraints()).isEmpty();
    }

    @Test
    void emptyArgConstraintsObjectAndEmptyEntriesYieldEmptyMap() {
        // 空对象、以及"工具条目里没有任何文本参数"的条目，均为空 Map
        // An empty object, and tool entries without any textual parameter, both yield an empty map
        CharacterToolPolicy emptyObject = CharacterToolPolicy.fromPolicyJson("{\"argConstraints\":{}}");
        CharacterToolPolicy emptyToolEntry = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{\"submit_leave_request\":{}}}");

        assertThat(emptyObject.getArgConstraints()).isEmpty();
        assertThat(emptyToolEntry.getArgConstraints()).isEmpty();
    }

    @Test
    void wrongTypedArgConstraintsIsIgnoredWhileSiblingKeysStillParse() {
        // argConstraints 整体类型不符、工具条目非对象、参数值非文本：按空处理不连坐其余 key
        // A non-object argConstraints, a non-object tool entry, and non-textual
        // values are all treated as empty without taking down sibling keys
        CharacterToolPolicy scalarKey = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":\"@caller\",\"approvalRequiredMcpTools\":[\"submit_expense_report\"]}");
        CharacterToolPolicy scalarToolEntry = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{\"submit_leave_request\":\"@caller\"}}");
        CharacterToolPolicy nonTextualValue = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{\"submit_leave_request\":{\"employee_name\":42,\"days\":\"@caller\"}}}");

        assertThat(scalarKey.getArgConstraints()).isEmpty();
        assertThat(scalarKey.getApprovalRequiredMcpTools()).containsExactly("submit_expense_report");
        assertThat(scalarToolEntry.getArgConstraints()).isEmpty();
        // 条目内仅丢弃坏值，同条目的合法参数保留
        // Only the bad value is dropped within the entry; valid siblings survive
        assertThat(nonTextualValue.getArgConstraints())
                .containsExactly(Map.entry("submit_leave_request", Map.of("days", "@caller")));
    }

    @Test
    void invalidJsonFailSafeYieldsEmptyArgConstraints() {
        // 非法 JSON 整体回到默认策略：argConstraints 也为空、不抛异常（fail-safe）
        // Invalid JSON falls back wholesale: argConstraints is empty too, no exception (fail-safe)
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{\"submit_leave_request\":{\"employee_name\":\"@caller\"}");

        assertThat(policy.isBuiltinAllowed("run_workflow")).isTrue();
        assertThat(policy.getArgConstraints()).isEmpty();
    }

    @Test
    void allThreeKeysCoexistInOnePolicyJson() {
        // 045 种子落库后的完整形态：denylist、审批标记、参数域约束三键共存
        // The full post-045 seed shape: denylist, approval markers, and
        // argument-scope constraints coexisting in one JSON
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"builtinDenylist\":[\"run_workflow\"],"
                        + "\"approvalRequiredMcpTools\":[\"submit_expense_report\",\"submit_leave_request\"],"
                        + "\"argConstraints\":{\"submit_expense_report\":{\"employee_name\":\"@caller\"}}}");

        assertThat(policy.isBuiltinAllowed("run_workflow")).isFalse();
        assertThat(policy.getApprovalRequiredMcpTools())
                .containsExactlyInAnyOrder("submit_expense_report", "submit_leave_request");
        assertThat(policy.getArgConstraints())
                .containsExactly(Map.entry("submit_expense_report", Map.of("employee_name", "@caller")));
    }

    @Test
    void argConstraintsKeyConstantIsPinnedToMigration045Contract() {
        // 常量与迁移 045 注释钉死的契约一致；用常量拼出策略 JSON 回环解析验证
        // The constant matches the contract pinned by migration 045; round-trip
        // a policy JSON built from the constants
        assertThat(CharacterToolPolicy.KEY_ARG_CONSTRAINTS).isEqualTo("argConstraints");

        String json = String.format("{\"%s\":{\"submit_leave_request\":{\"employee_name\":\"@caller\"}}}",
                CharacterToolPolicy.KEY_ARG_CONSTRAINTS);
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(json);

        assertThat(policy.getArgConstraints())
                .containsExactly(Map.entry("submit_leave_request", Map.of("employee_name", "@caller")));
    }

    @Test
    void argConstraintsMapIsDeeplyImmutable() {
        CharacterToolPolicy policy = CharacterToolPolicy.fromPolicyJson(
                "{\"argConstraints\":{\"submit_leave_request\":{\"employee_name\":\"@caller\"}}}");

        // 外层与每个参数 Map 均不可变（请求级只读视图不允许原地改写）
        // Both the outer map and every parameter map are immutable
        assertThatThrownBy(() -> policy.getArgConstraints().put("another_tool", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.getArgConstraints().get("submit_leave_request").put("days", "@caller"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
