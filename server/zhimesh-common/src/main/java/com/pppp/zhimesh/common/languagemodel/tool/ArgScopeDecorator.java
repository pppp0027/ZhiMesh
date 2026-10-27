package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具参数域装饰器（仅限本人办理）：tool_policy 的 argConstraints 命中的 MCP
 * 工具在装配期（AbstractLLMService.discoverRequestTools）被本装饰器包装。执行前
 * 逐参数校验约束——当前唯一表达式 {@code "@caller"} 要求参数值等于当前对话用户名
 * （{@link ToolContext#getUser()}，与审批装饰器同源的请求级通道）：
 * <ul>
 *   <li>全部约束参数等值 → 放行委托内层执行真实 MCP 调用；</li>
 *   <li>任一参数值不等 → 不执行底层工具，返回「该业务仅限本人办理：当前对话用户为
 *       X，无法为 Y 代办」拒绝文本作为工具结果回喂模型，模型转述给用户；</li>
 *   <li>任一参数缺失（参数 JSON 空白/损坏/无该 key）→ 同样不执行，返回提示模型以
 *       当前对话用户身份填写该参数的引导文本；</li>
 *   <li>{@code "@caller"} 之外的未知表达式忽略并 warn（面向未来扩展，不按字面量
 *       比对硬编码）；</li>
 *   <li>当前用户解析失败（上下文无 user 或姓名空白）→ <b>放行</b>（fail-open：演示
 *       环境避免身份解析故障把写操作整体锁死，与审批门的 fail-closed 口径相反，
 *       属明示决策——漏放行一次代办可由 MCP 侧数据修正，锁死全部业务不可接受）。</li>
 * </ul>
 * 拒绝时沿挂起/审批轨迹的口径记一条 {@link ToolCallTrace}（toolName=被包装工具、
 * success=false、resultSummary 前缀 {@link #ARG_SCOPE_MARKER_PREFIX}，形态与
 * MCP_APPROVAL 拒绝并列可查）；外层 ToolRoundExecutor 仍会按普通结果记一条
 * success=true 的轨迹行（拒绝文本回喂模型的口径），两行并存。
 * <p>
 * 与审批装饰器的叠加顺序：本装饰器在内层、ApprovalRequiredDecorator 在外层——
 * 审批凭证 hash 必须基于模型原始参数（不被本装饰器改写），审批通过后仍要过参数域
 * 校验（批准了「调这个工具」不等于批准「替任何人调」）。spec()/isMcpTool() 透传
 * 内层，isCollaborative 不置位（拒绝是普通结果回喂，不是挂起）。
 * <p>
 * Argument-scope decorator for MCP tools (self-service only): MCP tools hit by
 * tool_policy's argConstraints are wrapped by this decorator at assembly time
 * (AbstractLLMService.discoverRequestTools). Before execution each constrained
 * argument is checked — the only expression today, {@code "@caller"}, requires
 * the value to equal the current chat user's name ({@link ToolContext#getUser()},
 * the same request-scoped channel the approval decorator reads):
 * <ul>
 *   <li>every constrained argument equals → pass through to the inner real MCP
 *       call;</li>
 *   <li>any argument differs → the underlying tool does not run; the rejection
 *       text "self-service only: the current chat user is X, cannot act for Y"
 *       returns as the tool result for the model to relay;</li>
 *   <li>any argument missing (blank/corrupt arguments JSON or the key absent)
 *       → likewise no execution; the text prompts the model to fill the
 *       argument with the current chat user instead;</li>
 *   <li>unknown expressions beyond {@code "@caller"} are ignored with a warn
 *       (future-facing; no hardcoded literal matching);</li>
 *   <li>current user unresolvable (no user on the context, or a blank name) →
 *       <b>pass through</b> (fail-open: a demo environment must not have all
 *       write operations locked out by an identity-resolution glitch — the
 *       deliberate opposite of the approval gate's fail-closed posture; one
 *       let-through proxy submission is correctable on the MCP data side, a
 *       full lockout is not).</li>
 * </ul>
 * On rejection a {@link ToolCallTrace} row is recorded in the suspension/
 * approval trace convention (toolName = the wrapped tool, success = false,
 * resultSummary prefixed with {@link #ARG_SCOPE_MARKER_PREFIX}, a form
 * queryable alongside MCP_APPROVAL rejections); the outer ToolRoundExecutor
 * still records its own success = true row afterwards (the
 * rejection-text-fed-back-to-the-model convention) — both rows coexist.
 * <p>
 * Stacking order with the approval decorator: this one is the inner layer and
 * ApprovalRequiredDecorator the outer — the approval grant's hash must be
 * computed over the model's original arguments (never rewritten by this
 * decorator), and an approved call still passes the argument-scope check
 * (approving "invoke this tool" never implies "invoke it for anyone"). spec()
 * and isMcpTool() pass through to the inner executor; isCollaborative stays
 * unset (a rejection is a plain result fed back, not a suspension).
 */
@Slf4j
public class ArgScopeDecorator implements ToolExecutor {

    /** 当前唯一约束表达式：参数值必须等于当前对话用户名 / The only expression today: the value must equal the current chat user's name */
    public static final String CALLER_EXPRESSION = "@caller";

    /**
     * 拒绝轨迹行的形态标记前缀（与 MCP_APPROVAL 拒绝并列的查询口径）：拒绝时装饰器
     * 自记一条 success=false、resultSummary 以该前缀开头的轨迹行，审计/前端可据此前缀
     * 区分参数域拒绝与普通工具结果
     * <p>
     * Form-marker prefix of the rejection trace row (a queryable form alongside
     * MCP_APPROVAL rejections): on rejection the decorator records a
     * success = false trace row whose resultSummary starts with this prefix, so
     * audits / the frontend can tell an argument-scope rejection from an
     * ordinary tool result.
     */
    public static final String ARG_SCOPE_MARKER_PREFIX = "[ARG_SCOPE] ";

    /** 被包装的真实 MCP 执行器 / The wrapped real MCP executor */
    private final ToolExecutor delegate;

    /** 本工具的参数域约束（参数名 → 约束表达式，快照不可变）/ This tool's constraints (argument name → expression, immutable snapshot) */
    private final Map<String, String> paramConstraints;

    public ArgScopeDecorator(ToolExecutor delegate, Map<String, String> paramConstraints) {
        this.delegate = delegate;
        this.paramConstraints = Collections.unmodifiableMap(new LinkedHashMap<>(paramConstraints));
    }

    /** 透传内层：模型看到的工具规格与未包装时完全一致 / Passes through: the model sees exactly the unwrapped spec */
    @Override
    public ToolSpecification spec() {
        return delegate.spec();
    }

    /** 透传内层（MCP=true）：放行后的真实调用保持 MCP 直调口径 / Passes through (MCP=true): the let-through real call keeps the MCP direct convention */
    @Override
    public boolean isMcpTool() {
        return delegate.isMcpTool();
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        String caller = resolveCaller(context);
        String toolName = null != request ? request.name() : spec().name();
        if (null == caller) {
            // fail-open：当前用户解析失败不阻断（与审批门 fail-closed 相反的明示决策，
            // 见类注释），warn 留痕便于定位身份接线缺口
            // Fail-open: an unresolvable current user never blocks the call (the
            // deliberate opposite of the approval gate's fail-closed, see the
            // class javadoc); the warn leaves a trace for debugging the identity
            // wiring gap
            log.warn("Arg-scope check skipped: no current user on the tool context (fail-open), toolName:{}",
                    toolName);
            return delegate.execute(request, context);
        }
        // 参数整体缺失短路（解析前，不触碰 JsonUtil）：request 为 null 或 arguments 为
        // null/空白时 readTree(null) 会抛 IllegalArgumentException（Jackson 断言）冒泡成
        // 通用工具失败，违背设计承诺的"参数缺失引导文本"；短路后 arguments 保持 null，
        // 循环内走 missingParamRejection 路径（含 ARG_SCOPE 轨迹），语义与参数 JSON
        // 可解析但 key 缺失完全一致
        // Whole-arguments-missing short-circuit (before parsing, JsonUtil
        // untouched): with a null request or null/blank arguments, readTree(null)
        // would throw an IllegalArgumentException (a Jackson assertion) bubbling
        // up as a generic tool failure, breaking the promised missing-argument
        // guidance text; short-circuited, the node stays null and the loop takes
        // the missingParamRejection path (with the ARG_SCOPE trace) — semantically
        // identical to parsable JSON with the key absent
        JsonNode arguments = null != request && StringUtils.isNotBlank(request.arguments())
                ? JsonUtil.toJsonNode(request.arguments())
                : null;
        for (Map.Entry<String, String> constraint : paramConstraints.entrySet()) {
            String paramName = constraint.getKey();
            String expression = constraint.getValue();
            if (!CALLER_EXPRESSION.equals(expression)) {
                // 未知表达式面向未来扩展：忽略并告警，不按字面量硬编码比对
                // Unknown expressions are future-facing: ignore with a warn, no
                // hardcoded literal matching
                log.warn("Unknown arg-scope expression ignored, toolName:{}, param:{}, expression:{}",
                        toolName, paramName, expression);
                continue;
            }
            JsonNode value = null != arguments ? arguments.get(paramName) : null;
            if (null == value || value.isNull()) {
                String rejection = missingParamRejection(caller, paramName);
                recordRejectionTrace(context, request, rejection);
                log.info("Arg-scope rejected: constrained argument missing, toolName:{}, param:{}, caller:{}",
                        toolName, paramName, caller);
                return rejection;
            }
            String paramValue = value.asText();
            if (!caller.equals(paramValue)) {
                String rejection = mismatchRejection(caller, paramValue);
                recordRejectionTrace(context, request, rejection);
                log.info("Arg-scope rejected: argument value is not the current chat user, toolName:{}, param:{}, caller:{}, value:{}",
                        toolName, paramName, caller, paramValue);
                return rejection;
            }
        }
        return delegate.execute(request, context);
    }

    /**
     * 当前对话用户名：ToolContext.user 是请求级唯一可靠通道（工具可能在守护线程池上
     * 执行，ThreadLocal 不可达）；user 缺失或姓名空白视为解析失败
     * <p>
     * The current chat user's name: ToolContext.user is the one reliable
     * request-scoped channel (tools may execute on the daemon pool where the
     * ThreadLocal is unreachable); a missing user or a blank name counts as
     * unresolvable.
     */
    private static String resolveCaller(ToolContext context) {
        User user = null != context ? context.getUser() : null;
        return null != user && StringUtils.isNotBlank(user.getName()) ? user.getName() : null;
    }

    /**
     * 值不等拒绝文案（模型转述给用户）：双方姓名都必须出现
     * <p>
     * The value-mismatch rejection text (relayed by the model): both names must appear.
     */
    private static String mismatchRejection(String caller, String paramValue) {
        return "该业务仅限本人办理：当前对话用户为 " + caller + "，无法为 " + paramValue + " 代办。请改为办理本人业务。";
    }

    /**
     * 参数缺失拒绝文案：引导模型以当前对话用户身份补填该参数
     * <p>
     * The missing-argument rejection text: prompts the model to fill the argument for the current chat user.
     */
    private static String missingParamRejection(String caller, String paramName) {
        return "该业务仅限本人办理：本次调用缺少参数 " + paramName + "。请以当前对话用户 " + caller
                + "（本人）的身份填写该参数后重试。";
    }

    /**
     * 记一条拒绝轨迹行（ARG_SCOPE 形态）：success=false、resultSummary 带形态前缀；
     * 上下文或收集器为空时静默跳过（与 ToolRoundExecutor 记录口径一致的防御）
     * <p>
     * Record one rejection trace row (the ARG_SCOPE form): success = false with
     * the form-marker prefix on the resultSummary; silently skipped when the
     * context or the collector is absent (the same defensive posture as
     * ToolRoundExecutor's recording).
     */
    private static void recordRejectionTrace(ToolContext context, ToolExecutionRequest request, String rejection) {
        if (null == context || null == context.getToolTraces()) {
            return;
        }
        List<ToolCallTrace> traces = context.getToolTraces();
        traces.add(ToolCallTrace.builder()
                .seq(traces.size())
                .toolName(null != request ? request.name() : null)
                .args(null != request ? request.arguments() : null)
                .resultSummary(ARG_SCOPE_MARKER_PREFIX + rejection)
                .durationMs(0L)
                .success(false)
                .build());
    }
}
