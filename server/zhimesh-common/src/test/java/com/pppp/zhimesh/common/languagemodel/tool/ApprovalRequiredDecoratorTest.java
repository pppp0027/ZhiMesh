package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需审批 MCP 工具装饰器（模型再调用式审批）的门控行为验证：未持有效凭证绝不触达内层
 * 真实执行器（fail-closed）；恢复链装配的凭证「同工具同参数」精确匹配才放行（内层恰好
 * 被调一次）且放行即核销（一次批准授权一次执行，同链重复调用再次挂起）；参数或工具名
 * 任一不匹配即再次挂起（防提示注入改参数绕审批）；规范形哈希
 * 让 key 顺序漂移不产生假阴性；spec/isMcpTool 委托内层而 isCollaborative 恒真。
 * 另覆盖 ApprovalGrant 的哈希确定性与 fromJson fail-safe 语义。
 * <p>
 * Gate-behavior verification of the approval-required MCP tool decorator
 * (re-invocation-style approval): without a valid grant the inner real executor
 * is never touched (fail-closed); a grant assembled by the resume chain lets
 * the call through only on an exact "same tool, same arguments" match (the
 * inner executor runs exactly once) and is consumed on use (one approval
 * authorizes one execution; a repeat call within the chain re-suspends); any
 * mismatch of arguments or tool name
 * re-suspends (the prompt-injection args-mutation bypass guard); the
 * canonical-form hash keeps key-order drift from false negatives; spec and
 * isMcpTool delegate to the inner executor while isCollaborative stays true.
 * Also covers ApprovalGrant's hash determinism and fromJson fail-safe
 * semantics.
 */
class ApprovalRequiredDecoratorTest {

    private static final String TOOL_NAME = "submit_expense_report";
    private static final String ARGS = "{\"title\":\"部门聚餐报销\",\"amount\":3200}";

    @Test
    void unapprovedCallSuspendsWithoutTouchingInnerExecutor() throws Exception {
        // 未持凭证（普通请求/凭证不适用）：不执行真实 MCP 调用，挂起信号完整携带
        // kind=MCP_APPROVAL、工具名、审批问句与本次请求的凭证；内层零调用
        // Without a grant (an ordinary request): the real MCP call does not run;
        // the suspension signal carries kind=MCP_APPROVAL, the tool name, the
        // approval question, and the grant for this request; zero inner calls
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder().toolTraces(new java.util.ArrayList<>()).build();

        String result = decorator.execute(toolRequest("id-1", TOOL_NAME, ARGS), context);

        // 占位文本返回但信号已置位（循环见信号即走挂起分支，占位不会被当结果使用）
        // The placeholder returns with the signal raised (the loop takes the
        // suspension path on the signal; the placeholder is never used as the result)
        assertThat(result).contains(TOOL_NAME);
        assertThat(inner.invocations.get()).isZero();
        SuspensionSignal signal = context.getSuspensionSignal();
        assertThat(signal).isNotNull();
        assertThat(signal.getKind()).isEqualTo(PendingCheckpointKind.MCP_APPROVAL);
        assertThat(signal.getToolName()).isEqualTo(TOOL_NAME);
        assertThat(signal.getRequestId()).isEqualTo("id-1");
        assertThat(signal.getQuestion()).contains(TOOL_NAME).contains("审批").contains(ARGS);
        // action/summary 载荷：审批卡片据此渲染，summary 即参数摘要
        // action/summary payload: the approval card renders from these, the
        // summary being the arguments digest
        assertThat(signal.getAction()).isEqualTo("调用工具 " + TOOL_NAME);
        assertThat(signal.getSummary()).isEqualTo(ARGS);
        // 随信号携带的凭证精确覆盖本次「工具 + 参数」：恢复轮读回后重调即匹配
        // The grant riding the signal exactly covers this "tool + arguments":
        // once the resume round reads it back, the re-invocation matches
        assertThat(signal.getApprovalGrant()).isNotNull();
        assertThat(signal.getApprovalGrant().matches(TOOL_NAME, ARGS)).isTrue();
        assertThat(signal.getApprovalGrant().matches(TOOL_NAME, "{\"title\":\"部门聚餐报销\",\"amount\":9999}"))
                .isFalse();
    }

    @Test
    void matchingGrantLetsInnerExecuteExactlyOnce() throws Exception {
        // 恢复链持匹配凭证（同工具同参数）：放行委托内层执行真实 MCP 调用，恰好一次，
        // 且不再置挂起信号（循环照常续跑）
        // The resume chain holds a matching grant (same tool, same arguments):
        // the inner executor runs the real MCP call exactly once and no
        // suspension signal is raised (the loop continues normally)
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder()
                .toolTraces(new java.util.ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, ARGS))
                .build();

        String result = decorator.execute(toolRequest("id-2", TOOL_NAME, ARGS), context);

        assertThat(result).isEqualTo(CountingMcpExecutor.RESULT);
        assertThat(inner.invocations.get()).isEqualTo(1);
        assertThat(context.getSuspensionSignal()).isNull();
    }

    @Test
    void grantIsConsumedOnUseSoRepeatCallReSuspends() throws Exception {
        // 凭证一次性：放行即核销（上下文凭证置空），一次批准授权一次执行——同链第二次
        // 同工具同参数调用已无凭证可匹配 → 再次挂起转审批并携带新凭证（防注入放大
        // 重复提交：注入文本让模型连调两次也只成功一次）
        // Single-use grant: consumed on pass-through (nulled on the context),
        // one approval authorizes one execution — the chain's second same-tool-
        // same-arguments call finds no grant left → re-suspension carrying a
        // fresh grant (the repeat-submission amplifier guard: an injected text
        // making the model call twice still gets only one execution through)
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder()
                .toolTraces(new java.util.ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, ARGS))
                .build();

        String first = decorator.execute(toolRequest("id-2a", TOOL_NAME, ARGS), context);
        assertThat(first).isEqualTo(CountingMcpExecutor.RESULT);
        assertThat(context.getApprovalGrant()).isNull();
        assertThat(context.getSuspensionSignal()).isNull();

        String second = decorator.execute(toolRequest("id-2b", TOOL_NAME, ARGS), context);
        assertThat(second).contains(TOOL_NAME);
        assertThat(inner.invocations.get()).isEqualTo(1);
        assertThat(context.getSuspensionSignal()).isNotNull();
        assertThat(context.getSuspensionSignal().getKind()).isEqualTo(PendingCheckpointKind.MCP_APPROVAL);
        // 新信号携带覆盖本次参数的新凭证：用户对新一次执行的批准是新一次审批
        // The new signal carries a fresh grant covering these arguments:
        // approving this second execution is a new approval
        assertThat(context.getSuspensionSignal().getApprovalGrant()).isNotNull();
        assertThat(context.getSuspensionSignal().getApprovalGrant().matches(TOOL_NAME, ARGS)).isTrue();
    }

    @Test
    void changedArgumentsReSuspendForAnotherApproval() throws Exception {
        // 凭证覆盖 A 参数，模型改调 B 参数（提示注入改参场景）：哈希不匹配 → 再次挂起、
        // 内层零调用；新信号的凭证覆盖 B 参数（用户对新参数的批准是新一次审批）
        // The grant covers arguments A but the model calls with B (the
        // injection args-mutation shape): the hash mismatches → re-suspension
        // and zero inner calls; the new signal's grant covers B (approving B
        // is a fresh approval)
        String approvedArgs = "{\"title\":\"部门聚餐报销\",\"amount\":3200}";
        String mutatedArgs = "{\"title\":\"部门聚餐报销\",\"amount\":32000}";
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder()
                .toolTraces(new java.util.ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, approvedArgs))
                .build();

        String result = decorator.execute(toolRequest("id-3", TOOL_NAME, mutatedArgs), context);

        assertThat(result).contains(TOOL_NAME);
        assertThat(inner.invocations.get()).isZero();
        assertThat(context.getSuspensionSignal()).isNotNull();
        assertThat(context.getSuspensionSignal().getKind()).isEqualTo(PendingCheckpointKind.MCP_APPROVAL);
        // 旧凭证不覆盖新参数（上下文里装配的还是旧凭证），新信号携带的凭证覆盖新参数（各批各的）
        // The old grant no longer covers the new arguments (the context still
        // holds the old one) while the new signal's grant does (each approval its own)
        assertThat(context.getApprovalGrant().matches(TOOL_NAME, mutatedArgs)).isFalse();
        assertThat(context.getApprovalGrant().matches(TOOL_NAME, approvedArgs)).isTrue();
        assertThat(context.getSuspensionSignal().getApprovalGrant().matches(TOOL_NAME, mutatedArgs)).isTrue();
    }

    @Test
    void grantForAnotherToolDoesNotOpenThisToolsGate() throws Exception {
        // 凭证属于别的工具（同参数哈希也不行）：工具名不匹配 → 照常挂起、内层零调用
        // The grant belongs to another tool (even an equal arguments hash won't
        // do): the tool name mismatches → suspension as usual, zero inner calls
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder()
                .toolTraces(new java.util.ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments("send_external_email", ARGS))
                .build();

        decorator.execute(toolRequest("id-4", TOOL_NAME, ARGS), context);

        assertThat(inner.invocations.get()).isZero();
        assertThat(context.getSuspensionSignal()).isNotNull();
        assertThat(context.getSuspensionSignal().getApprovalGrant().getToolName()).isEqualTo(TOOL_NAME);
    }

    @Test
    void canonicalHashIgnoresKeyOrderDrift() throws Exception {
        // 模型重调时 key 顺序漂移（规范形哈希的目的）：语义相同的参数仍精确匹配放行，
        // 不产生「刚批准又要再批」的假阴性；嵌套对象同样递归排序
        // Key-order drift on the model's re-invocation (the whole point of the
        // canonical hash): semantically equal arguments still match exactly —
        // no "just approved, approval demanded again" false negative; nested
        // objects are recursively sorted too
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder()
                .toolTraces(new java.util.ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME,
                        "{\"title\":\"聚餐\",\"nested\":{\"z\":1,\"a\":2},\"amount\":100}"))
                .build();

        // 同语义参数换一种 key 顺序（含嵌套层）
        // The same semantics with a different key order (nested level included)
        String reordered = "{\"amount\":100,\"nested\":{\"a\":2,\"z\":1},\"title\":\"聚餐\"}";
        String result = decorator.execute(toolRequest("id-5", TOOL_NAME, reordered), context);

        assertThat(result).isEqualTo(CountingMcpExecutor.RESULT);
        assertThat(inner.invocations.get()).isEqualTo(1);
        assertThat(context.getSuspensionSignal()).isNull();
    }

    @Test
    void nullContextFailsExplicitlyInsteadOfExecuting() {
        // 无请求级上下文即无处写挂起信号：明确失败，绝不放行未批准调用（fail-closed）
        // Without a request-scoped context there is nowhere to put the signal:
        // fail explicitly, never let an unapproved call through (fail-closed)
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);

        assertThatThrownBy(() -> decorator.execute(toolRequest("id-6", TOOL_NAME, ARGS), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TOOL_NAME);
        assertThat(inner.invocations.get()).isZero();
    }

    @Test
    void specAndMcpMarkerDelegateToInnerWhileCollaborativeMarkerStaysTrue() {
        // 委托契约：模型看到的工具规格与未包装时完全一致（装饰器透明），isMcpTool 委托
        // 内层（放行后的真实调用保持 MCP 直调口径），isCollaborative 恒真（挂起分支依据）
        // Delegation contract: the model sees exactly the unwrapped spec (a
        // transparent decorator); isMcpTool delegates to the inner executor
        // (the approved real call keeps the MCP direct convention);
        // isCollaborative stays true (the suspension-branch marker)
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);

        assertThat(decorator.spec()).isSameAs(inner.spec());
        assertThat(decorator.isMcpTool()).isTrue();
        assertThat(decorator.isCollaborative()).isTrue();
    }

    @Test
    void oversizedArgumentsSummaryIsTruncatedWithMarker() throws Exception {
        // 超长参数摘要截断到上限并加末尾标记：完整参数已由轨迹行承载，卡片只取摘要
        // An oversized arguments digest is capped with a trailing marker: the
        // full arguments ride the trace row, the card only needs a digest
        StringBuilder bigArgs = new StringBuilder("{\"data\":\"");
        bigArgs.append("x".repeat(400));
        bigArgs.append("\"}");
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ApprovalRequiredDecorator decorator = new ApprovalRequiredDecorator(inner);
        ToolContext context = ToolContext.builder().toolTraces(new java.util.ArrayList<>()).build();

        decorator.execute(toolRequest("id-7", TOOL_NAME, bigArgs.toString()), context);

        assertThat(context.getSuspensionSignal().getSummary())
                .hasSize(ApprovalRequiredDecorator.ARGS_SUMMARY_MAX_CHARS + "...[truncated]".length())
                .endsWith("...[truncated]");
        // 空白参数归「无参数」
        // Blank arguments collapse to "no arguments"
        ToolContext blankContext = ToolContext.builder().toolTraces(new java.util.ArrayList<>()).build();
        decorator.execute(toolRequest("id-8", TOOL_NAME, null), blankContext);
        assertThat(blankContext.getSuspensionSignal().getSummary()).isEqualTo("（无参数）");
        assertThat(blankContext.getSuspensionSignal().getQuestion()).contains("无参数");
    }

    // ==================== ApprovalGrant 语义 / ApprovalGrant semantics ====================

    @Test
    void approvalGrantHashIsDeterministicAndSensitive() {
        // 哈希确定性：同参数同哈希；任何内容差异（值/结构/空白 vs 有值）都改变哈希；
        // 空白与 null 归同一口径（空串）
        // Hash determinism: equal arguments hash equally; any content
        // difference (value/structure/blank vs present) changes the hash;
        // blank and null share the empty-string convention
        assertThat(ApprovalGrant.hashArguments(ARGS)).isEqualTo(ApprovalGrant.hashArguments(ARGS));
        assertThat(ApprovalGrant.hashArguments(ARGS))
                .isNotEqualTo(ApprovalGrant.hashArguments("{\"title\":\"部门聚餐报销\",\"amount\":3201}"));
        assertThat(ApprovalGrant.hashArguments("{\"a\":1}"))
                .isNotEqualTo(ApprovalGrant.hashArguments("{\"a\":2}"));
        assertThat(ApprovalGrant.hashArguments("{\"a\":1}"))
                .isNotEqualTo(ApprovalGrant.hashArguments("[1]"));
        assertThat(ApprovalGrant.hashArguments(null)).isEqualTo(ApprovalGrant.hashArguments("   "));
        assertThat(ApprovalGrant.hashArguments(ARGS)).hasSize(64);
    }

    @Test
    void approvalGrantJsonRoundTripAndFailSafeParsing() {
        // 凭证 JSON 无损回环（检查点落库/恢复读回依据）；空白或损坏 JSON 返回 null
        // （fail-safe：无凭证 = 未批准，绝不把坏凭证当批准）
        // The grant JSON round-trips losslessly (the checkpoint persist/resume
        // basis); blank or corrupt JSON yields null (fail-safe: no grant =
        // not approved, a corrupt grant never counts as an approval)
        ApprovalGrant grant = ApprovalGrant.forArguments(TOOL_NAME, ARGS);
        ApprovalGrant parsed = ApprovalGrant.fromJson(grant.toJson());
        assertThat(parsed).isNotNull();
        assertThat(parsed.getToolName()).isEqualTo(TOOL_NAME);
        assertThat(parsed.getArgsHash()).isEqualTo(grant.getArgsHash());
        assertThat(parsed.matches(TOOL_NAME, ARGS)).isTrue();

        assertThat(ApprovalGrant.fromJson(null)).isNull();
        assertThat(ApprovalGrant.fromJson("  ")).isNull();
        assertThat(ApprovalGrant.fromJson("{\"toolName\":\"submit_expense_report")).isNull();
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private static ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    /**
     * 计数型假 MCP 执行器：isMcpTool=true，execute 返回固定结果并计数——装饰器测试的
     * 「内层恰好被调几次」断言全部依赖它
     * <p>
     * Counting fake MCP executor: isMcpTool=true, execute returns a fixed
     * result and counts — every "how many times the inner ran" assertion in
     * the decorator tests leans on it
     */
    private static final class CountingMcpExecutor implements ToolExecutor {
        static final String RESULT = "mcp-ok";

        final java.util.concurrent.atomic.AtomicInteger invocations =
                new java.util.concurrent.atomic.AtomicInteger();
        private final ToolSpecification spec;

        private CountingMcpExecutor(String toolName) {
            this.spec = ToolSpecification.builder()
                    .name(toolName)
                    .description("test mcp tool " + toolName)
                    .build();
        }

        @Override
        public ToolSpecification spec() {
            return spec;
        }

        @Override
        public boolean isMcpTool() {
            return true;
        }

        @Override
        public String execute(ToolExecutionRequest request, ToolContext context) {
            invocations.incrementAndGet();
            return RESULT;
        }
    }
}
