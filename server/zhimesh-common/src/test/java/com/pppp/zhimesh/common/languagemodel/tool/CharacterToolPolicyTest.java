package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.entity.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
}
