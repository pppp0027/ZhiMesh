package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.apache.commons.lang3.StringUtils;

/**
 * 需审批 MCP 工具装饰器（模型再调用式审批）：tool_policy 的 approvalRequiredMcpTools
 * 命中的 MCP 工具在装配期（AbstractLLMService.discoverRequestTools）被本装饰器包装，
 * McpToolExecutor 本体零改动。spec 委托内层（模型看到的是同一个工具），isCollaborative
 * =true 使其走挂起分支：
 * <ol>
 *   <li>未持有效批准凭证（恢复链的 ToolContext.approvalGrant 为空或 toolName+argsHash
 *       不精确匹配）→ 不执行真实 MCP 调用，置 SuspensionSignal(kind=MCP_APPROVAL，
 *       question=审批问句，action/summary 载荷带工具名与参数摘要，approvalGrant=本次
 *       请求的凭证）挂起转审批；</li>
 *   <li>恢复轮用户提交同意文本 → 模型重调同工具同参数 → 凭证精确匹配 → 放行委托内层
 *       执行真实 MCP 调用；参数变了哈希不匹配 → 再次挂起重新审批（max-suspensions 内核
 *       已防无限循环）；用户拒绝 → 拒绝文本作为恢复轮结果消息注入，模型转述，通常不再
 *       重调。拒绝防线分两层：T6 拒绝按钮提交带 {@link #REJECTION_MARKER_PREFIX} 固定
 *       前缀的结构化文本，恢复装配精确前缀匹配即<b>不装配凭证</b>（模型违背拒绝重调也
 *       无凭证可匹配，照常挂起，硬保证）；自由文本拒绝不带前缀，维持设计明文的「模型
 *       服从」口径（若模型仍重调且凭证在链上则放行——已知残留）。</li>
 *   <li>凭证一次性：放行即核销（上下文凭证置空），一次批准授权一次执行——同链重复
 *       调用同工具同参数会再次挂起转审批（防注入放大重复提交）。</li>
 * </ol>
 * 挂起能力不可用（无 sink，如 blocking 路径）或挂起次数达上限时，挂起内核回退为引导文本
 * 结果——需审批工具<b>绝不</b>在未持匹配凭证时执行（fail-closed）。isMcpTool 委托内层：
 * 批准放行后的真实调用保持 MCP 直调口径（guardrails 归 T7 统一处理）。
 * <p>
 * Approval-required MCP tool decorator (re-invocation-style approval): MCP
 * tools hit by tool_policy's approvalRequiredMcpTools are wrapped by this
 * decorator at assembly time (AbstractLLMService.discoverRequestTools) —
 * McpToolExecutor itself stays untouched. spec() delegates to the inner
 * executor (the model sees the very same tool) and isCollaborative() = true
 * routes it through the suspension branch:
 * <ol>
 *   <li>No valid grant (the resume chain's ToolContext.approvalGrant absent,
 *       or toolName + argsHash not an exact match) → the real MCP call does
 *       not run; a SuspensionSignal (kind=MCP_APPROVAL, question=the approval
 *       ask, action/summary payload carrying the tool name and an arguments
 *       digest, approvalGrant=the grant for this request) suspends the loop
 *       for approval;</li>
 *   <li>the user approves on the resume round → the model re-invokes the same
 *       tool with the same arguments → the grant matches exactly → the inner
 *       executor runs the real MCP call; changed arguments break the hash →
 *       another suspension and another approval (the max-suspensions kernel
 *       already caps the loop); a rejection rides the resume round's result
 *       message to the model, which relays it and normally never re-invokes.
 *       The refusal defense has two layers: the T6 reject button submits a
 *       structured text prefixed with {@link #REJECTION_MARKER_PREFIX}, and
 *       the resume assembly skips arming the grant on an exact prefix match
 *       (a re-invocation defying the rejection then finds no grant to match
 *       and suspends again — a hard guarantee); a free-text rejection
 *       carries no prefix and keeps the design-documented model-obedience
 *       posture (a re-invocation with the grant still on the chain passes —
 *       a known residual).</li>
 *   <li>single-use grant: consumed on pass-through (the context's grant is
 *       nulled), one approval authorizes one execution — a repeat call of
 *       the same tool with the same arguments within the chain suspends for
 *       approval again (the prompt-injection repeat-submission amplifier
 *       guard).</li>
 * </ol>
 * When suspension is unavailable (no sink, e.g. the blocking path) or the
 * suspension cap is hit, the suspension kernel falls back to a guidance-text
 * result — the approval-required tool <b>never</b> executes without a matching
 * grant (fail-closed). isMcpTool() delegates to the inner executor: the real
 * invocation after approval keeps the MCP direct-call convention (guardrails
 * are unified by T7).
 */
public class ApprovalRequiredDecorator implements ToolExecutor {

    /**
     * 审批卡片/问句里的参数摘要长度上限：完整参数已由轨迹行（ToolCallTrace.args）承载，
     * 卡片只取摘要，防超长参数把卡片与合成收尾消息内容撑爆
     * <p>
     * Length cap for the arguments digest on the approval card/question: the
     * full arguments already ride the trace row (ToolCallTrace.args), the card
     * only needs a digest — an oversized one would bloat both the card and the
     * synthesized wrap-up message content.
     */
    static final int ARGS_SUMMARY_MAX_CHARS = 200;

    /**
     * 结构化拒绝标记前缀（T6 前端拒绝按钮的文本契约）：恢复轮答复以该前缀开头时，
     * CharacterChatService 的恢复装配跳过批准凭证（fail-closed 硬保证——模型违背拒绝
     * 重调同工具同参数也无凭证可匹配，照常挂起转审批）。自由文本拒绝不匹配该前缀，
     * 维持设计文档的「模型服从」口径。精确前缀匹配、大小写敏感，杜绝自由文本误伤
     * <p>
     * Structured rejection marker prefix (the text contract of the T6
     * frontend's reject button): when the resumed round's answer starts with
     * it, CharacterChatService's resume assembly skips arming the approval
     * grant (a hard fail-closed guarantee — a re-invocation defying the
     * rejection finds no grant to match and suspends again). Free-text
     * rejections do not match and keep the design-documented model-obedience
     * posture. Exact, case-sensitive prefix matching avoids any free-text
     * collateral.
     */
    public static final String REJECTION_MARKER_PREFIX = "[APPROVAL_REJECTED]";

    /** 被包装的真实 MCP 执行器 / The wrapped real MCP executor */
    private final ToolExecutor delegate;

    public ApprovalRequiredDecorator(ToolExecutor delegate) {
        this.delegate = delegate;
    }

    /** 委托内层：模型看到的工具规格与未包装时完全一致 / Delegates: the model sees exactly the unwrapped spec */
    @Override
    public ToolSpecification spec() {
        return delegate.spec();
    }

    /**
     * 协作类工具标记：未持凭证时 execute 产出挂起信号而非普通文本结果，循环据此走挂起分支
     * <p>
     * Collaborative-tool marker: without a matching grant, execute yields a
     * suspension signal instead of a plain text result, routing the loop into
     * the suspension branch.
     */
    @Override
    public boolean isCollaborative() {
        return true;
    }

    /** 委托内层（MCP=true）：批准放行后的真实调用保持 MCP 直调口径 / Delegates (MCP=true): the approved real call keeps the MCP direct convention */
    @Override
    public boolean isMcpTool() {
        return delegate.isMcpTool();
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        String toolName = null != request ? request.name() : spec().name();
        String arguments = null != request ? request.arguments() : null;
        ApprovalGrant grant = null != context ? context.getApprovalGrant() : null;
        if (null != grant && grant.matches(toolName, arguments)) {
            // 凭证精确匹配（仅本恢复链装配进上下文）：放行真实 MCP 调用。放行即核销
            // （上下文凭证置空）：一次批准授权一次执行，同链重复调用同工具同参数需
            // 重新审批（防注入放大重复提交）
            // Exact grant match (loaded into the context only by this resume
            // chain's assembly): let the real MCP call through — and consume
            // the grant on use (null it on the context): one approval
            // authorizes one execution; a repeat same-tool-same-arguments call
            // within the chain must be approved again (the repeat-submission
            // amplifier guard)
            context.setApprovalGrant(null);
            return delegate.execute(request, context);
        }
        if (null == context) {
            // 无请求级上下文即无处写挂起信号：明确失败，绝不放行未批准调用
            // Without a request-scoped context there is nowhere to put the
            // suspension signal: fail explicitly, never let an unapproved call through
            throw new IllegalStateException(
                    "approval-required tool '" + toolName + "' cannot execute without a tool context");
        }
        // 未持有效凭证：不执行真实 MCP 调用，挂起转审批（凭证随信号落检查点，
        // 恢复轮读回后模型重调同工具同参数即匹配放行）
        // No valid grant: the real MCP call does not run; suspend for approval
        // (the grant persists with the signal's checkpoint; once the resume
        // assembly reads it back, the model's re-invocation of the same tool
        // with the same arguments matches and passes)
        String argsSummary = argsSummary(arguments);
        context.setSuspensionSignal(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.MCP_APPROVAL)
                .toolName(toolName)
                .requestId(null != request ? request.id() : null)
                .question(approvalQuestion(toolName, argsSummary))
                .action("调用工具 " + toolName)
                .summary(argsSummary)
                .approvalGrant(ApprovalGrant.forArguments(toolName, arguments))
                .build());
        return "[approval_required 已挂起，等待用户审批：" + toolName + "]";
    }

    /**
     * 审批问句（= 挂起轮合成收尾的消息内容）：「该操作需人工审批：调用工具 X。参数：…。
     * 请确认是否同意执行。」
     * <p>
     * The approval question (= the suspending round's synthesized message
     * content): "This action needs human approval: invoke tool X. Arguments:
     * …. Please confirm whether to proceed."
     */
    private static String approvalQuestion(String toolName, String argsSummary) {
        return "该操作需要人工审批：调用工具 " + toolName + "。参数：" + argsSummary + "。请确认是否同意执行。";
    }

    /**
     * 参数摘要：null 归「无参数」，超长截断到 {@link #ARGS_SUMMARY_MAX_CHARS} 并加末尾标记
     * <p>
     * Arguments digest: null collapses to "no arguments"; oversized input is
     * truncated to {@link #ARGS_SUMMARY_MAX_CHARS} with a trailing marker.
     */
    private static String argsSummary(String arguments) {
        if (StringUtils.isBlank(arguments)) {
            return "（无参数）";
        }
        if (arguments.length() <= ARGS_SUMMARY_MAX_CHARS) {
            return arguments;
        }
        return arguments.substring(0, ARGS_SUMMARY_MAX_CHARS) + "...[truncated]";
    }
}
