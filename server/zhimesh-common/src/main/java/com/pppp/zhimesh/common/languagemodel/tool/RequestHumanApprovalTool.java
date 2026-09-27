package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.util.JsonUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.apache.commons.lang3.StringUtils;

/**
 * 内置协作工具 request_human_approval：让 Agentic 角色在执行有实质影响/有风险的操作前，
 * 显式向当前对话的用户请求人工审批并挂起工具循环（挂起-恢复内核复用 T4 的 ask_user 机制，
 * 事件为 approval_request）。与 MCP 需审批装饰器共用 APPROVAL 审批卡片，区别在于本工具是
 * 模型主动声明「接下来要做的事需要批准」（无后续真实工具调用的凭证），装饰器是被动拦截
 * tool_policy 标记的 MCP 工具（挂起为 MCP_APPROVAL 并携带批准凭证）。
 * <p>
 * 非 Spring bean，由聊天入口按请求构造（纯 ToolContext 驱动）。execute 校验 action/summary
 * 非空后把挂起信号（kind=APPROVAL、action/summary/risk_level 载荷、审批问句 question）
 * 写入 ToolContext 的挂起结果槽并返回占位文本——占位文本不会被使用：循环检测到挂起信号即
 * 走挂起路径。用户在恢复轮提交的同意/拒绝文本作为本请求的 ToolExecutionResultMessage 注入，
 * 模型据其决定是否继续（拒绝 = 转述并停止，无额外机制）。工具 description 同时引导
 * 「需要审批时单独调用，不要与其他工具同轮」。
 * <p>
 * Builtin collaborative tool request_human_approval: lets an agentic character
 * explicitly ask the current user for human approval before performing a
 * consequential/risky action, suspending the tool loop (the suspend/resume
 * kernel reuses T4's ask_user machinery; the event is approval_request). It
 * shares the approval card with the MCP approval decorator but differs in
 * nature: this tool is the model proactively declaring "what I am about to do
 * needs approval" (no grant — no real tool invocation follows), while the
 * decorator passively intercepts MCP tools marked by tool_policy (suspending
 * as MCP_APPROVAL and carrying an approval grant).
 * <p>
 * Not a Spring bean; constructed per request by the chat entry and driven
 * purely by ToolContext. After validating non-blank action/summary, execute
 * writes the suspension signal (kind=APPROVAL with the action/summary/
 * risk_level payload and the approval question) into the ToolContext
 * suspension slot and returns a placeholder text — never used: the loop takes
 * the suspension path as soon as it sees the signal. The user's approve/reject
 * text submitted on the resume round is injected as this request's
 * ToolExecutionResultMessage, and the model decides from it whether to
 * proceed (rejection = relay and stop; no extra mechanism). The tool
 * description also nudges the model to call it alone rather than alongside
 * other tools in the same round.
 */
public class RequestHumanApprovalTool implements ToolExecutor {

    /** 工具名（按名匹配执行的唯一标识）/ Tool name (unique key for by-name resolution) */
    public static final String NAME = "request_human_approval";

    /**
     * 挂起占位文本：挂起路径不会把它当工具结果使用（循环见信号即短路）；仅当某个
     * 非标准调用方绕过挂起内核直接执行本工具时作为兜底返回
     * <p>
     * Suspension placeholder: the suspension path never treats it as the tool
     * result (the loop short-circuits on the signal); it is a fallback only for
     * a non-standard caller executing this tool outside the suspension kernel.
     */
    static final String SUSPENDED_PLACEHOLDER_TEXT = "[request_human_approval 已挂起，等待用户审批]";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("即将执行有实质影响或风险的操作（如提交单据、删除数据、对外发送、资金变动）前，"
                    + "向用户请求人工审批并暂停等待其结论。不要未经审批直接执行此类操作。"
                    + "需要审批时应单独调用本工具，不要与其他工具在同一轮并行调用。"
                    + "action 一句话概括要做什么，summary 说明具体内容与影响范围，risk_level 取 LOW/MEDIUM/HIGH。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("action", "要请求审批的动作，一句话概括（如：提交报销单）")
                    .addStringProperty("summary", "操作的具体内容与影响范围（如：金额 3200 元，事由部门聚餐）")
                    .addStringProperty("risk_level", "风险等级：LOW / MEDIUM / HIGH")
                    .required("action", "summary")
                    .build())
            .build();

    @Override
    public ToolSpecification spec() {
        return SPEC;
    }

    /**
     * 协作类工具标记：execute 产出挂起信号而非普通文本结果，循环据此走挂起分支
     * <p>
     * Collaborative-tool marker: execute yields a suspension signal instead of a
     * plain text result, which routes the loop into the suspension branch.
     */
    @Override
    public boolean isCollaborative() {
        return true;
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        JsonNode arguments = parseArguments(request);
        String action = textArgument(arguments, "action");
        String summary = textArgument(arguments, "summary");
        if (StringUtils.isBlank(action) || StringUtils.isBlank(summary)) {
            throw new IllegalArgumentException(
                    "request_human_approval requires non-blank 'action' and 'summary' arguments");
        }
        if (null == context) {
            // 无请求级上下文即无处写挂起信号：明确失败，绝不假装已发起审批
            // Without a request-scoped context there is nowhere to put the
            // suspension signal: fail explicitly, never fake a filed approval
            throw new IllegalStateException("request_human_approval requires a tool context to suspend the loop");
        }
        String riskLevel = textArgument(arguments, "risk_level");
        context.setSuspensionSignal(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.APPROVAL)
                .toolName(NAME)
                .requestId(null == request ? null : request.id())
                .question(approvalQuestion(action, summary, riskLevel))
                .action(action)
                .summary(summary)
                .riskLevel(riskLevel)
                .build());
        return SUSPENDED_PLACEHOLDER_TEXT;
    }

    /**
     * 审批问句（= 挂起轮合成收尾的消息内容与 agent 卡片正文兜底）：由 action/summary/
     * risk_level 拼装，风险等级缺省时省略该从句
     * <p>
     * The approval question (= the suspending round's synthesized message
     * content and the card-body fallback): assembled from action/summary/
     * risk_level, the risk clause omitted when the level is absent.
     */
    private static String approvalQuestion(String action, String summary, String riskLevel) {
        StringBuilder question = new StringBuilder("该操作需要人工审批：").append(action)
                .append("。说明：").append(summary).append("。");
        if (StringUtils.isNotBlank(riskLevel)) {
            question.append("风险等级：").append(riskLevel).append("。");
        }
        return question.append("请确认是否同意执行。").toString();
    }

    private static JsonNode parseArguments(ToolExecutionRequest request) {
        String arguments = null == request ? null : request.arguments();
        if (StringUtils.isBlank(arguments)) {
            throw new IllegalArgumentException(
                    "request_human_approval requires JSON arguments with 'action' and 'summary' fields");
        }
        JsonNode parsed = JsonUtil.toJsonNode(arguments);
        if (null == parsed || !parsed.isObject()) {
            throw new IllegalArgumentException("request_human_approval arguments must be a JSON object");
        }
        return parsed;
    }

    private static String textArgument(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return null == node || node.isNull() ? null : node.asText();
    }
}
