package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * request_human_approval 协作工具的契约单测：spec 形态（action/summary 必填、risk_level
 * 可选字符串、description 引导单独调用）、execute 的挂起信号契约（kind=APPROVAL、
 * action/summary/riskLevel 载荷、由三者拼装的审批问句）与参数校验（空 action/summary/
 * 坏 JSON/无上下文全部显式失败，不产生半挂起状态）。
 * <p>
 * Contract tests for the request_human_approval collaborative tool: the spec
 * shape (action/summary required, risk_level an optional string, the
 * description nudging solo invocation), execute's suspension-signal contract
 * (kind=APPROVAL, the action/summary/riskLevel payload, the approval question
 * assembled from the three), and argument validation (blank action/summary /
 * bad JSON / missing context all fail explicitly, never leaving a
 * half-suspended state).
 */
class RequestHumanApprovalToolTest {

    private final RequestHumanApprovalTool tool = new RequestHumanApprovalTool();

    @Test
    void specDeclaresActionAndSummaryRequiredWithOptionalRiskLevel() {
        JsonObjectSchema parameters = (JsonObjectSchema) tool.spec().parameters();

        assertThat(tool.spec().name()).isEqualTo(RequestHumanApprovalTool.NAME);
        assertThat(parameters.required()).containsExactly("action", "summary");
        assertThat(parameters.properties()).containsKeys("action", "summary", "risk_level");
        assertThat(parameters.properties().get("risk_level")).isInstanceOf(JsonStringSchema.class);
        // description 引导「单独调用」与「未经审批不得执行」，从源头减少同轮多协作请求
        // The description nudges "call it alone" and "never execute without
        // approval", reducing same-round multiple collaborative requests at the source
        assertThat(tool.spec().description()).contains("单独调用本工具").contains("审批");
    }

    @Test
    void collaborativeMarkerIsTrue() {
        assertThat(tool.isCollaborative()).isTrue();
    }

    @Test
    void executeRaisesApprovalSuspensionSignalWithFullPayload() throws Exception {
        ToolContext context = ToolContext.builder().build();

        String result = tool.execute(request("id-ap-1",
                "{\"action\":\"提交报销单\",\"summary\":\"金额 3200 元，事由部门聚餐\",\"risk_level\":\"MEDIUM\"}"),
                context);

        assertThat(result).isEqualTo(RequestHumanApprovalTool.SUSPENDED_PLACEHOLDER_TEXT);
        SuspensionSignal signal = context.getSuspensionSignal();
        assertThat(signal).isNotNull();
        assertThat(signal.getKind()).isEqualTo(PendingCheckpointKind.APPROVAL);
        assertThat(signal.getToolName()).isEqualTo(RequestHumanApprovalTool.NAME);
        assertThat(signal.getRequestId()).isEqualTo("id-ap-1");
        // 审批卡片载荷：action/summary/riskLevel 三件（SSE/检查点 payload 的源头）
        // The approval-card payload: the action/summary/riskLevel triple (the
        // source of both the SSE and checkpoint payloads)
        assertThat(signal.getAction()).isEqualTo("提交报销单");
        assertThat(signal.getSummary()).isEqualTo("金额 3200 元，事由部门聚餐");
        assertThat(signal.getRiskLevel()).isEqualTo("MEDIUM");
        // 问句由三者拼装（挂起轮合成收尾的消息内容与卡片正文兜底）
        // The question is assembled from the three (the suspending round's
        // synthesized message content and the card-body fallback)
        assertThat(signal.getQuestion())
                .contains("提交报销单")
                .contains("金额 3200 元，事由部门聚餐")
                .contains("MEDIUM")
                .endsWith("请确认是否同意执行。");
        // 显式审批不携带批准凭证（无后续真实工具调用的放行语义，区别于 MCP_APPROVAL）
        // An explicit approval carries no grant (nothing real follows to let
        // through — the contrast with MCP_APPROVAL)
        assertThat(signal.getApprovalGrant()).isNull();
        assertThat(signal.getCheckpointUuid()).isNull();
    }

    @Test
    void blankRiskLevelOmitsClauseInAssembledQuestion() throws Exception {
        ToolContext context = ToolContext.builder().build();

        tool.execute(request("id-ap-2",
                "{\"action\":\"删除知识库\",\"summary\":\"删除 KB-A 及其全部片段\"}"), context);

        SuspensionSignal signal = context.getSuspensionSignal();
        assertThat(signal.getRiskLevel()).isNull();
        assertThat(signal.getQuestion())
                .contains("删除知识库")
                .doesNotContain("风险等级");
    }

    @Test
    void blankActionOrSummaryFailsExplicitlyWithoutTouchingContext() {
        ToolContext context = ToolContext.builder().build();

        assertThatThrownBy(() -> tool.execute(request("id-1",
                "{\"action\":\"  \",\"summary\":\"有内容\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
        assertThatThrownBy(() -> tool.execute(request("id-2",
                "{\"action\":\"有动作\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
        assertThat(context.getSuspensionSignal()).isNull();
    }

    @Test
    void blankOrNonObjectArgumentsFailExplicitly() {
        assertThatThrownBy(() -> tool.execute(request("id-3", ""), ToolContext.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(request("id-4", "\"just a string\""),
                ToolContext.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingContextFailsExplicitlyInsteadOfFakingSuccess() {
        // 无请求级上下文即无处写挂起信号：明确失败，绝不假装已发起审批
        // Without a request-scoped context there is nowhere to put the signal:
        // fail explicitly, never fake a filed approval
        assertThatThrownBy(() -> tool.execute(request("id-5",
                "{\"action\":\"提交报销单\",\"summary\":\"金额 3200 元\"}"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool context");
    }

    private static ToolExecutionRequest request(String id, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(RequestHumanApprovalTool.NAME)
                .arguments(arguments).build();
    }
}
