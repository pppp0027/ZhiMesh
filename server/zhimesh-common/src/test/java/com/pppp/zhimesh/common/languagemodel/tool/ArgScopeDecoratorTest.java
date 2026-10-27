package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 参数域装饰器（仅限本人办理）的门控行为验证：@caller 约束参数等值放行（内层真实
 * 执行器恰好被调一次）；值不等拒绝——内层零调用、拒绝文本含双方姓名、轨迹记
 * ARG_SCOPE 形态（success=false + 形态前缀）；参数缺失（key 不在 / 参数空白 /
 * arguments 为 null）拒绝并引导以当前对话用户身份补填；@caller 之外的未知表达式
 * 忽略（面向未来扩展）；当前
 * 用户解析失败（无 user / 姓名空白 / 无上下文）fail-open 放行；与审批装饰器的叠加
 * 顺序——审批在外层参数域在内层：未审批零调用、审批通过但参数不符仍零调用、两者
 * 都过才真实调用（fake McpClient 断言）；spec/isMcpTool 透传而 isCollaborative
 * 恒假（拒绝是普通结果回喂，不是挂起）。
 * <p>
 * Gate-behavior verification of the argument-scope decorator (self-service
 * only): a @caller-constrained argument equal to the current user passes (the
 * inner real executor runs exactly once); a differing value is rejected — zero
 * inner calls, both names in the rejection text, and an ARG_SCOPE-form trace
 * row (success=false + the form prefix); a missing argument (key absent /
 * blank arguments) is rejected with a prompt to refill it for the current
 * user; unknown expressions beyond @caller are ignored (future-facing); an
 * unresolvable current user (no user / blank name / no context) fails open;
 * the stacking order with the approval decorator — approval outer, argument
 * scope inner: zero calls without approval, still zero with approval but a
 * mismatching value, and a real call only when both pass (asserted against a
 * fake McpClient); spec and isMcpTool pass through while isCollaborative
 * stays false (a rejection is a plain result fed back, not a suspension).
 */
class ArgScopeDecoratorTest {

    private static final String TOOL_NAME = "submit_leave_request";
    private static final String PARAM_NAME = "employee_name";
    private static final String CALLER = "李四";
    private static final String OTHER = "张三";
    private static final String MATCHING_ARGS = "{\"employee_name\":\"李四\",\"days\":3}";
    private static final String MISMATCHING_ARGS = "{\"employee_name\":\"张三\",\"days\":3}";
    private static final Map<String, String> CONSTRAINTS = Map.of(PARAM_NAME, ArgScopeDecorator.CALLER_EXPRESSION);

    @Test
    void equalValuePassesThroughToInnerExecutorExactlyOnce() throws Exception {
        // 当前用户李四，参数值同为李四：约束放行，内层真实执行器被调恰好一次，
        // 返回其结果，不产生拒绝轨迹
        // The current user is 李四 and the argument says 李四: the constraint
        // passes, the inner real executor runs exactly once, its result returns,
        // and no rejection trace is produced
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, CONSTRAINTS);
        ToolContext context = contextWithUser(CALLER);

        String result = decorator.execute(toolRequest("id-1", TOOL_NAME, MATCHING_ARGS), context);

        assertThat(result).isEqualTo(CountingMcpExecutor.RESULT);
        assertThat(inner.invocations.get()).isEqualTo(1);
        assertThat(context.getToolTraces()).isEmpty();
        assertThat(context.getSuspensionSignal()).isNull();
    }

    @Test
    void mismatchedValueRejectedWithBothNamesAndArgScopeTrace() throws Exception {
        // 当前用户李四，模型替张三代办：内层零调用；拒绝文本含双方姓名（模型可转述
        // 仅限本人）；轨迹记 ARG_SCOPE 形态——toolName 归属被包装工具、success=false、
        // resultSummary 带形态前缀、args 为原始参数
        // The current user is 李四 but the model acts for 张三: zero inner
        // calls; the rejection text carries both names (the model can relay the
        // self-service-only rule); the trace records the ARG_SCOPE form — the
        // toolName attributed to the wrapped tool, success=false, the
        // resultSummary prefixed with the form marker, the args kept verbatim
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, CONSTRAINTS);
        ToolContext context = contextWithUser(CALLER);

        String result = decorator.execute(toolRequest("id-2", TOOL_NAME, MISMATCHING_ARGS), context);

        assertThat(inner.invocations.get()).isZero();
        assertThat(result).contains(CALLER).contains(OTHER).contains("仅限本人");
        assertThat(context.getToolTraces()).hasSize(1);
        ToolCallTrace trace = context.getToolTraces().get(0);
        assertThat(trace.getToolName()).isEqualTo(TOOL_NAME);
        assertThat(trace.getArgs()).isEqualTo(MISMATCHING_ARGS);
        assertThat(trace.isSuccess()).isFalse();
        assertThat(trace.getResultSummary())
                .startsWith(ArgScopeDecorator.ARG_SCOPE_MARKER_PREFIX)
                .contains(OTHER);
        // 拒绝不是挂起：不置挂起信号（外层循环照常把拒绝文本当普通结果回喂模型）
        // A rejection is no suspension: no signal is raised (the outer loop
        // feeds the rejection text back as an ordinary result)
        assertThat(context.getSuspensionSignal()).isNull();
    }

    @Test
    void missingArgumentRejectedPromptingCallerIdentity() throws Exception {
        // 参数缺失两态——key 不在参数里 / 参数整体空白：都拒绝执行并引导模型以当前
        // 对话用户身份补填该参数，内层零调用
        // The two missing-argument shapes — the key absent from the arguments /
        // the arguments entirely blank: both reject and prompt the model to
        // refill the argument for the current chat user; zero inner calls
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, CONSTRAINTS);

        ToolContext keyAbsentContext = contextWithUser(CALLER);
        String keyAbsent = decorator.execute(toolRequest("id-3a", TOOL_NAME, "{\"days\":3}"),
                keyAbsentContext);
        assertThat(keyAbsent).contains(PARAM_NAME).contains(CALLER).contains("仅限本人");
        assertThat(inner.invocations.get()).isZero();
        assertThat(keyAbsentContext.getToolTraces()).hasSize(1);
        assertThat(keyAbsentContext.getToolTraces().get(0).getResultSummary())
                .startsWith(ArgScopeDecorator.ARG_SCOPE_MARKER_PREFIX);

        ToolContext blankArgsContext = contextWithUser(CALLER);
        String blankArgs = decorator.execute(toolRequest("id-3b", TOOL_NAME, "  "),
                blankArgsContext);
        assertThat(blankArgs).contains(PARAM_NAME).contains(CALLER).contains("仅限本人");
        assertThat(inner.invocations.get()).isZero();
        assertThat(blankArgsContext.getToolTraces()).hasSize(1);

        // arguments=null：解析前短路（不触碰 JsonUtil——readTree(null) 会抛
        // IllegalArgumentException 冒泡成通用工具失败），直接走缺失拒绝路径（含轨迹）
        // arguments=null: short-circuited before parsing (JsonUtil untouched —
        // readTree(null) would throw an IllegalArgumentException bubbling up as
        // a generic tool failure), taking the missing-argument rejection path
        // (trace included)
        ToolContext nullArgsContext = contextWithUser(CALLER);
        String nullArgs = decorator.execute(toolRequest("id-3c", TOOL_NAME, null), nullArgsContext);
        assertThat(nullArgs).contains(PARAM_NAME).contains(CALLER).contains("仅限本人");
        assertThat(inner.invocations.get()).isZero();
        assertThat(nullArgsContext.getToolTraces()).hasSize(1);
        assertThat(nullArgsContext.getToolTraces().get(0).getResultSummary())
                .startsWith(ArgScopeDecorator.ARG_SCOPE_MARKER_PREFIX);
    }

    @Test
    void unknownExpressionIsIgnoredSoTheToolStillRuns() throws Exception {
        // @caller 之外的未知表达式（面向未来扩展）：忽略并告警，不按字面量硬编码比对
        // ——即使参数值与当前用户不等也照常放行内层
        // An unknown expression beyond @caller (future-facing): ignored with a
        // warn, no hardcoded literal matching — the inner runs even though the
        // value differs from the current user
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, Map.of(PARAM_NAME, "@manager"));
        ToolContext context = contextWithUser(CALLER);

        String result = decorator.execute(toolRequest("id-4", TOOL_NAME, MISMATCHING_ARGS), context);

        assertThat(result).isEqualTo(CountingMcpExecutor.RESULT);
        assertThat(inner.invocations.get()).isEqualTo(1);
        assertThat(context.getToolTraces()).isEmpty();
    }

    @Test
    void unresolvableCurrentUserFailsOpen() throws Exception {
        // 当前用户解析失败三态——上下文无 user / 姓名空白 / 无上下文：fail-open 放行
        // （演示环境不因身份解析故障锁死写操作，与审批门 fail-closed 相反的明示决策）
        // The three unresolvable-current-user shapes — no user on the context /
        // a blank name / no context at all: fail-open pass-through (a demo
        // environment must not have write operations locked out by an
        // identity-resolution glitch; the deliberate opposite of the approval
        // gate's fail-closed)
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, CONSTRAINTS);

        ToolContext noUser = ToolContext.builder().toolTraces(new ArrayList<>()).build();
        assertThat(decorator.execute(toolRequest("id-5a", TOOL_NAME, MISMATCHING_ARGS), noUser))
                .isEqualTo(CountingMcpExecutor.RESULT);

        ToolContext blankName = contextWithUser("  ");
        assertThat(decorator.execute(toolRequest("id-5b", TOOL_NAME, MISMATCHING_ARGS), blankName))
                .isEqualTo(CountingMcpExecutor.RESULT);

        assertThat(decorator.execute(toolRequest("id-5c", TOOL_NAME, MISMATCHING_ARGS), null))
                .isEqualTo(CountingMcpExecutor.RESULT);

        assertThat(inner.invocations.get()).isEqualTo(3);
    }

    @Test
    void specPassesThroughAndDecoratorStaysNonCollaborative() {
        // 委托契约：spec 透传内层（模型看到的规格不变）、isMcpTool 透传（放行后的真实
        // 调用保持 MCP 直调口径）、isCollaborative 恒假（拒绝走普通结果通道，绝不触发
        // 挂起分支）
        // Delegation contract: spec passes through (the model sees the
        // unwrapped spec), isMcpTool passes through (the let-through real call
        // keeps the MCP direct convention), isCollaborative stays false (a
        // rejection rides the ordinary result channel and never triggers the
        // suspension branch)
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, CONSTRAINTS);

        assertThat(decorator.spec()).isSameAs(inner.spec());
        assertThat(decorator.isMcpTool()).isTrue();
        assertThat(decorator.isCollaborative()).isFalse();
    }

    @Test
    void multipleConstraintsAllMustPassForTheCallToRun() throws Exception {
        // 多个约束参数：任一不等即拒绝（逐参数校验，先命中先拒），全过才放行
        // Multiple constrained arguments: any mismatch rejects (checked one by
        // one, first violation wins); only all-equal passes
        CountingMcpExecutor inner = new CountingMcpExecutor(TOOL_NAME);
        Map<String, String> two = new LinkedHashMap<>();
        two.put("employee_name", "@caller");
        two.put("applicant", "@caller");
        ArgScopeDecorator decorator = new ArgScopeDecorator(inner, two);
        ToolContext context = contextWithUser(CALLER);

        String halfMatching = decorator.execute(toolRequest("id-6a", TOOL_NAME,
                "{\"employee_name\":\"李四\",\"applicant\":\"王五\"}"), context);
        assertThat(halfMatching).contains("王五").contains(CALLER);
        assertThat(inner.invocations.get()).isZero();

        String allMatching = decorator.execute(toolRequest("id-6b", TOOL_NAME,
                "{\"employee_name\":\"李四\",\"applicant\":\"李四\"}"), context);
        assertThat(allMatching).isEqualTo(CountingMcpExecutor.RESULT);
        assertThat(inner.invocations.get()).isEqualTo(1);
    }

    // ==================== 叠加顺序：审批外层参数域内层 / Stacking: approval outer, argument scope inner ====================

    @Test
    void approvalOuterArgScopeInnerGateTheRealMcpCallInOrder() throws Exception {
        // 叠加顺序（ApprovalRequired(ArgScope(McpToolExecutor))，与
        // AbstractLLMService.discoverRequestTools 的装配顺序一致）：未持审批凭证 →
        // 零真实调用（审批门先挂起）；凭证匹配但参数与当前用户不符 → 审批已通过仍
        // 零真实调用（参数域拒绝，凭证被核销）；两者都过 → 真实调用恰好一次
        // Stacking order (ApprovalRequired(ArgScope(McpToolExecutor)), matching
        // AbstractLLMService.discoverRequestTools' assembly): without an
        // approval grant → zero real calls (the approval gate suspends first);
        // a matching grant but a value differing from the current user → still
        // zero real calls (the argument scope rejects, the grant consumed);
        // both passing → exactly one real call
        McpClient mcpClient = mock(McpClient.class);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenAnswer(invocation -> ToolExecutionResult.builder().resultText("ticket created").build());
        ToolExecutor stacked = new ApprovalRequiredDecorator(
                new ArgScopeDecorator(new McpToolExecutor(toolSpec(TOOL_NAME), mcpClient), CONSTRAINTS));

        // 1) 未审批（无凭证）：审批门先拦，真实 MCP 调用零次，挂起 MCP_APPROVAL
        // 1) Unapproved (no grant): the approval gate intercepts first, zero
        // real MCP calls, suspended as MCP_APPROVAL
        ToolContext unapproved = contextWithUser(CALLER);
        String suspended = stacked.execute(toolRequest("id-s1", TOOL_NAME, MATCHING_ARGS), unapproved);
        assertThat(suspended).contains(TOOL_NAME);
        verify(mcpClient, times(0)).executeTool(any(ToolExecutionRequest.class));
        assertThat(unapproved.getSuspensionSignal()).isNotNull();
        assertThat(unapproved.getSuspensionSignal().getKind())
                .isEqualTo(com.pppp.zhimesh.common.enums.PendingCheckpointKind.MCP_APPROVAL);

        // 2) 审批通过（凭证匹配原始参数）但参数域不符（替张三代办）：审批放行并核销
        //    凭证后参数域拒绝——真实 MCP 调用仍零次，拒绝文本含双方姓名
        // 2) Approved (the grant covers the original arguments) but the
        //    argument scope mismatches (acting for 张三): the approval passes
        //    and consumes the grant, then the scope rejects — still zero real
        //    MCP calls, both names in the rejection text
        ToolContext approvedMismatch = ToolContext.builder()
                .user(user(CALLER))
                .toolTraces(new ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, MISMATCHING_ARGS))
                .build();
        String rejected = stacked.execute(toolRequest("id-s2", TOOL_NAME, MISMATCHING_ARGS), approvedMismatch);
        assertThat(rejected).contains(CALLER).contains(OTHER).contains("仅限本人");
        verify(mcpClient, times(0)).executeTool(any(ToolExecutionRequest.class));
        assertThat(approvedMismatch.getSuspensionSignal()).isNull();
        // 凭证已核销：一次批准授权一次执行（参数域拒绝也算消耗了这次批准）
        // The grant is consumed: one approval authorizes one execution (an
        // argument-scope rejection still spends that approval)
        assertThat(approvedMismatch.getApprovalGrant()).isNull();

        // 3) 审批与参数域都过：真实 MCP 调用恰好一次
        // 3) Both approval and scope pass: exactly one real MCP call
        ToolContext approvedAndMatching = ToolContext.builder()
                .user(user(CALLER))
                .toolTraces(new ArrayList<>())
                .approvalGrant(ApprovalGrant.forArguments(TOOL_NAME, MATCHING_ARGS))
                .build();
        String passed = stacked.execute(toolRequest("id-s3", TOOL_NAME, MATCHING_ARGS), approvedAndMatching);
        assertThat(passed).isEqualTo("ticket created");
        verify(mcpClient, times(1)).executeTool(any(ToolExecutionRequest.class));
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private static ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static ToolSpecification toolSpec(String toolName) {
        return ToolSpecification.builder()
                .name(toolName)
                .description("test mcp tool " + toolName)
                .build();
    }

    private static User user(String name) {
        User user = new User();
        user.setName(name);
        return user;
    }

    private static ToolContext contextWithUser(String name) {
        return ToolContext.builder()
                .user(user(name))
                .toolTraces(new ArrayList<>())
                .build();
    }

    /**
     * 计数型假 MCP 执行器：isMcpTool=true，execute 返回固定结果并计数——「内层恰好
     * 被调几次」断言的依据
     * <p>
     * Counting fake MCP executor: isMcpTool=true, execute returns a fixed
     * result and counts — the basis for the "exactly how many times the inner
     * ran" assertions
     */
    private static final class CountingMcpExecutor implements ToolExecutor {
        static final String RESULT = "mcp-ok";

        final AtomicInteger invocations = new AtomicInteger();
        private final ToolSpecification spec;

        private CountingMcpExecutor(String toolName) {
            this.spec = toolSpec(toolName);
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
