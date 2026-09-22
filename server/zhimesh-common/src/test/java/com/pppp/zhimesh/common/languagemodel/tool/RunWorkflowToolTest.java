package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.workflow.WorkflowStarter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * run_workflow 的行为验证：标题精确解析（可见清单是唯一入口，未知/不可见标题明确报错并
 * 提示可见清单）、递归防护（ThreadLocal 置位拒绝且零调用 WorkflowStarter、正常执行后
 * 清除、标志传播进工作流线程）、超时路径（返回"仍在执行"状态摘要且工作流线程不被取消、
 * 最终完成）、正常路径（输出包装格式与软截断）、ObjectNode 输入包装与 spec 形态。
 * <p>
 * Behavioral verification for run_workflow: exact title resolution (the visible
 * catalog is the only entry; unknown/invisible titles fail with a visible-
 * catalog hint), recursion guard (rejection with zero WorkflowStarter calls
 * while the ThreadLocal flag is set, cleared after normal execution, and
 * propagated onto the workflow thread), timeout path (a "still running"
 * summary is returned while the workflow thread is never cancelled and runs to
 * completion), normal path (output wrapping format and soft truncation),
 * ObjectNode input wrapping, and the spec shape.
 */
class RunWorkflowToolTest {

    private WorkflowStarter workflowStarter;
    private User user;

    @BeforeEach
    void setUp() {
        workflowStarter = mock(WorkflowStarter.class);
        user = new User();
        user.setId(7L);
        user.setUuid("user-uuid-7");
        user.setLocale("zh-CN");
    }

    @AfterEach
    void tearDown() {
        RunWorkflowTool.IN_RUN_WORKFLOW.remove();
    }

    // ==================== spec 形态 / spec shape ====================

    @Test
    void specInjectsVisibleTitleCatalogCappedAtTwenty() {
        List<RunWorkflowTool.WorkflowOption> options = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> option(String.format("工作流%02d", i), "wf-uuid-" + i, "var_user_input", null))
                .toList();
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter, options, 55_000L);

        var spec = tool.spec();
        assertThat(spec.name()).isEqualTo("run_workflow");
        // 清单截断到前 20 个标题：防 description 膨胀
        // The catalog is capped at the first 20 titles to bound the description
        assertThat(spec.description())
                .contains("工作流01")
                .contains("工作流20")
                .doesNotContain("工作流21")
                .contains("精确")
                .contains("不要对同一工作流重复调用");
        assertThat(spec.parameters().properties()).containsOnlyKeys("workflowTitle", "input");
        assertThat(spec.parameters().required()).containsExactly("workflowTitle");
        assertThat(tool.isMcpTool()).isFalse();
    }

    // ==================== 标题解析 / title resolution ====================

    @Test
    void executeMatchesExactTitleIgnoringSurroundingWhitespace() throws Exception {
        when(workflowStarter.blocking(eq(user), eq("wf-uuid-1"), any()))
                .thenReturn(completedResult("周报正文"));
        RunWorkflowTool tool = defaultTool();

        String result = tool.execute(request("{\"workflowTitle\":\" 周报生成 \",\"input\":\"本周素材\"}"),
                context());

        assertThat(result).isEqualTo("工作流《周报生成》执行完成，输出如下：\n周报正文");
    }

    @Test
    void unknownTitleFailsWithVisibleCatalogHint() {
        RunWorkflowTool tool = defaultTool();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"月报汇总\"}"), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("月报汇总")
                .hasMessageContaining("可用工作流标题")
                .hasMessageContaining("周报生成")
                .hasMessageContaining("旅行攻略");
        // 匹配不到绝不触发工作流执行
        // A title miss never triggers a workflow execution
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void nearTitleIsSuggestedForMisspelledTitle() {
        RunWorkflowTool tool = defaultTool();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"周报\"}"), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相近标题")
                .hasMessageContaining("周报生成");
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void invisibleWorkflowTitleIsRejectedLikeAnyUnknownTitle() {
        // 可见性由清单构造保证：不在清单里的工作流（如他人私有工作流）与未知标题同等拒绝，不提权
        // Visibility is guaranteed by catalog construction: a workflow outside
        // the catalog (e.g. someone else's private one) is rejected exactly
        // like an unknown title — no escalation
        RunWorkflowTool tool = defaultTool();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"别人的私有工作流\"}"), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能调用当前用户可见的工作流");
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void blankOrMalformedArgumentsAreRejected() {
        RunWorkflowTool tool = defaultTool();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"  \"}"), context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(request("{}"), context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(request("not-json"), context()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void missingUserContextIsRejected() {
        RunWorkflowTool tool = defaultTool();
        ToolContext contextWithoutUser = ToolContext.builder().build();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), contextWithoutUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current user");
        verifyNoInteractions(workflowStarter);
    }

    // ==================== 递归防护 / recursion guard ====================

    @Test
    void recursionFlagRejectsNestedCallWithoutTouchingWorkflowStarter() throws Exception {
        RunWorkflowTool.IN_RUN_WORKFLOW.set(Boolean.TRUE);
        RunWorkflowTool tool = defaultTool();

        String result = tool.execute(request("{\"workflowTitle\":\"周报生成\",\"input\":\"x\"}"), context());

        // 固定拒绝文案；WorkflowStarter 零调用（连参数解析都不需要发生副作用）
        // Fixed rejection text; zero WorkflowStarter calls
        assertThat(result).isEqualTo(RunWorkflowTool.RECURSION_REJECT_TEXT);
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void recursionFlagIsClearedAfterNormalExecution() throws Exception {
        when(workflowStarter.blocking(any(), any(), any())).thenReturn(completedResult("ok"));
        RunWorkflowTool tool = defaultTool();

        tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        // finally 清除：不留置位污染同线程后续调用
        // Cleared in finally: no stale flag polluting later calls on this thread
        assertThat(RunWorkflowTool.IN_RUN_WORKFLOW.get()).isNull();
    }

    @Test
    void recursionFlagIsClearedEvenWhenWorkflowFails() {
        when(workflowStarter.blocking(any(), any(), any())).thenThrow(new RuntimeException("db down"));
        RunWorkflowTool tool = defaultTool();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行失败")
                .hasMessageContaining("db down");
        assertThat(RunWorkflowTool.IN_RUN_WORKFLOW.get()).isNull();
    }

    @Test
    void recursionFlagPropagatesOntoWorkflowThread() throws Exception {
        // blocking 在工具自建的工作流线程上执行：线程内应看到置位标志；主线程执行完成后清除
        // blocking runs on the tool's own workflow thread: the flag must be
        // visible there; the calling thread's flag is cleared afterwards
        AtomicReference<Boolean> flagSeenInWorkflowThread = new AtomicReference<>();
        when(workflowStarter.blocking(any(), any(), any())).thenAnswer(invocation -> {
            flagSeenInWorkflowThread.set(RunWorkflowTool.IN_RUN_WORKFLOW.get());
            return completedResult("ok");
        });
        RunWorkflowTool tool = defaultTool();

        tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        assertThat(flagSeenInWorkflowThread.get()).isEqualTo(Boolean.TRUE);
        assertThat(RunWorkflowTool.IN_RUN_WORKFLOW.get()).isNull();
    }

    // ==================== 超时路径 / timeout path ====================

    @Test
    void timeoutReturnsStillRunningSummaryAndWorkflowThreadRunsToCompletion() throws Exception {
        // blocking 阻塞远超 internalTimeoutMs：工具返回"仍在执行"摘要；随后释放，工作流
        // 线程完整跑完（未被 cancel/中断）
        // blocking blocks far beyond internalTimeoutMs: the tool returns the
        // "still running" summary; once released, the workflow thread runs to
        // completion (never cancelled or interrupted)
        CountDownLatch blockingEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);
        CountDownLatch blockingFinished = new CountDownLatch(1);
        when(workflowStarter.blocking(any(), any(), any())).thenAnswer(invocation -> {
            blockingEntered.countDown();
            releaseBlocking.await();
            blockingFinished.countDown();
            return completedResult("迟到的输出");
        });
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(option("周报生成", "wf-uuid-1", "var_user_input", null)), 200L);

        String result = tool.execute(request("{\"workflowTitle\":\"周报生成\",\"input\":\"素材\"}"), context());

        assertThat(blockingEntered.await(2L, TimeUnit.SECONDS)).isTrue();
        assertThat(result)
                .contains("工作流《周报生成》仍在执行中")
                .contains("已等待")
                .contains("可稍后在工作流页面查看执行记录")
                .doesNotContain("迟到的输出");

        // 释放阻塞：工作流线程必须能完整结束（未被取消/中断的证明）
        // Release the block: the workflow thread must be able to finish
        // (proof it was never cancelled or interrupted)
        releaseBlocking.countDown();
        assertThat(blockingFinished.await(5L, TimeUnit.SECONDS))
                .as("workflow thread must complete after the tool already returned")
                .isTrue();
    }

    @Test
    void timeoutSummaryReportsWaitedSeconds() throws Exception {
        when(workflowStarter.blocking(any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(10_000L);
            return completedResult("迟到的输出");
        });
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(option("周报生成", "wf-uuid-1", "var_user_input", null)), 2_500L);

        String result = tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        // internalTimeoutMs=2500 → 已等待 2s（毫秒换算为整秒）
        // internalTimeoutMs=2500 → reports 2s
        assertThat(result).contains("已等待 2s");
    }

    // ==================== 正常路径渲染 / normal-path rendering ====================

    @Test
    void completedOutputWrapsStatusLineAndJoinsMultipleValues() throws Exception {
        ObjectNode outputs = JsonUtil.createObjectNode();
        outputs.set("output", textContent("第一部分"));
        outputs.set("summary", textContent("第二部分"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", "rt-1");
        result.put("status", "completed");
        result.put("outputs", outputs);
        when(workflowStarter.blocking(any(), any(), any())).thenReturn(result);
        RunWorkflowTool tool = defaultTool();

        String rendered = tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        assertThat(rendered).isEqualTo("工作流《周报生成》执行完成，输出如下：\n第一部分\n\n第二部分");
    }

    @Test
    void oversizedOutputIsSoftTruncatedWithMarker() throws Exception {
        when(workflowStarter.blocking(any(), any(), any()))
                .thenReturn(completedResult("x".repeat(6_000)));
        RunWorkflowTool tool = defaultTool();

        String rendered = tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        assertThat(rendered).contains("...[truncated]");
        // 前缀 + 4000 字符 + 截断尾注，总量有界
        // prefix + 4000 chars + marker keeps the total bounded
        assertThat(rendered.length()).isLessThan(4_200);
        assertThat(rendered).startsWith("工作流《周报生成》执行完成，输出如下：");
    }

    @Test
    void failureMapFromBlockingRunSurfacesAsIllegalArgument() {
        // blockingRun 内部失败形态 {message}：转为失败轨迹（IllegalArgumentException）
        // The internal failure shape {message} becomes a failed trace
        when(workflowStarter.blocking(any(), any(), any()))
                .thenReturn(Map.of("message", "工作流节点配置错误"));
        RunWorkflowTool tool = defaultTool();

        assertThatThrownBy(() -> tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作流《周报生成》执行失败")
                .hasMessageContaining("工作流节点配置错误");
    }

    // ==================== ObjectNode 输入包装 / input wrapping ====================

    @Test
    void buildUserInputsWrapsTextUnderResolvedParamName() {
        List<ObjectNode> inputs = RunWorkflowTool.buildUserInputs(
                option("周报生成", "wf-uuid-1", "var_user_input", 1_000), "本周素材");

        assertThat(inputs).hasSize(1);
        ObjectNode userInput = inputs.get(0);
        assertThat(userInput.get("name").asText()).isEqualTo("var_user_input");
        assertThat(userInput.get("content").get("type").asInt()).isEqualTo(1);
        assertThat(userInput.get("content").get("value").asText()).isEqualTo("本周素材");
    }

    @Test
    void buildUserInputsTruncatesToDefinitionMaxLength() {
        List<ObjectNode> inputs = RunWorkflowTool.buildUserInputs(
                option("周报生成", "wf-uuid-1", "var_user_input", 10), "这一段素材超过十个字符肯定被截断");

        assertThat(inputs.get(0).get("content").get("value").asText()).hasSize(10);
    }

    @Test
    void buildUserInputsEmptyWithoutTextInputDefinition() {
        // 起始节点无文本输入定义：input 无处投递，按空输入运行（不猜测参数名）
        // No TEXT input defined: input has nowhere to go; run with no input
        // instead of guessing a parameter name
        assertThat(RunWorkflowTool.buildUserInputs(option("旅行攻略", "wf-uuid-2", null, null), "素材"))
                .isEmpty();
    }

    // ==================== 门卫前置 / input preflight ====================

    @Test
    void preflightRefusesUndeliverableRequiredInputs() throws Exception {
        // 简历筛选形态：首文本参数可投递，但接收邮箱（第二个必填文本）与简历附件（必填
        // 文件）对话给不了——门卫前置拒绝启动并给出可行动指引，而不是放行后在引擎处
        // 撞上泛化的必填缺失错误
        // The resume-screening shape: the first TEXT param is deliverable, but
        // the required email (a second TEXT) and the required resume (FILE)
        // cannot pass through chat — preflight refuses to start and returns
        // actionable guidance instead of failing later at the engine gate
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("简历筛选", "wf-uuid-9", "var_jd", null, "筛选简历并邮件发送结果",
                        param("var_jd", "岗位JD", 1, true),
                        param("var_email", "接收邮箱", 1, true),
                        param("var_resume", "简历附件", 4, true))), 5_000L);

        String result = tool.execute(request("{\"workflowTitle\":\"简历筛选\",\"input\":\"Java后端3年\"}"), context());

        assertThat(result)
                .contains("简历筛选")
                .contains("接收邮箱")
                .contains("简历附件")
                .contains("手动运行")
                .contains("不要再次尝试调用");
        // 拒绝路径零副作用：不触发工作流，也不置位递归标记
        // The refusal path has zero side effects: no execution, no recursion flag
        verifyNoInteractions(workflowStarter);
        assertThat(RunWorkflowTool.IN_RUN_WORKFLOW.get()).isNull();
    }

    @Test
    void preflightRefusesBlankInputForRequiredDeliverableParam() throws Exception {
        // 单一必填文本参数但 input 为空：这是可补全的缺失——返回"向用户索取"指引，
        // 模型可将其转成对用户的追问
        // A single required TEXT param with a blank input: recoverable — return
        // the ask-the-user guidance the model can relay as a clarifying question
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("周报生成", "wf-uuid-1", "var_user_input", null, null,
                        param("var_user_input", "本周素材", 1, true))), 5_000L);

        String result = tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        assertThat(result)
                .contains("周报生成")
                .contains("本周素材")
                .contains("向用户索取");
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void preflightAllowsOptionalOnlyWorkflowWithBlankInput() throws Exception {
        // 全可选参数 + 空 input：门卫放行（存量行为），照常执行
        // All-optional params with a blank input: preflight lets it through
        // (legacy behavior); execution proceeds as before
        when(workflowStarter.blocking(any(), any(), any())).thenReturn(completedResult("ok"));
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("旅行攻略", "wf-uuid-2", "var_user_input", null, null,
                        param("var_user_input", "目的地", 1, false))), 5_000L);

        String result = tool.execute(request("{\"workflowTitle\":\"旅行攻略\"}"), context());

        assertThat(result).contains("执行完成");
    }

    @Test
    void preflightAllowsRequiredDeliverableParamWithProvidedInput() throws Exception {
        // 单一必填文本参数 + 非空 input：正常路径不受门卫影响
        // A required deliverable param with a non-blank input: the normal path
        // is unaffected by the preflight
        when(workflowStarter.blocking(any(), any(), any())).thenReturn(completedResult("ok"));
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("周报生成", "wf-uuid-1", "var_user_input", null, null,
                        param("var_user_input", "本周素材", 1, true))), 5_000L);

        String result = tool.execute(request("{\"workflowTitle\":\"周报生成\",\"input\":\"本周做了门卫前置\"}"), context());

        assertThat(result).contains("执行完成");
    }

    @Test
    void legacyOptionWithoutManifestBehavesAsBefore() throws Exception {
        // 旧 4 参构造（无参数清单）：门卫不设防，行为与旧版逐字节一致
        // The legacy 4-arg constructor (no manifest): the preflight is inert,
        // byte-for-byte legacy behavior
        when(workflowStarter.blocking(any(), any(), any())).thenReturn(completedResult("ok"));
        RunWorkflowTool tool = defaultTool();

        String result = tool.execute(request("{\"workflowTitle\":\"周报生成\"}"), context());

        assertThat(result).contains("执行完成");
    }

    // ==================== 目录清单 / catalog manifest ====================

    @Test
    void specDescriptionCarriesParamManifestRemarkAndManualRunHint() {
        // 描述升级（L2/L4）：注入参数清单（名称[类型·必填]）、备注摘要；含对话无法
        // 传递的必填输入的工作流标注"手动运行"；无参数工作流标注"无输入参数"
        // Description upgrade (L2/L4): injects the param manifest
        // (name[type·required]) and a remark digest; workflows with
        // undeliverable required inputs are annotated for manual run; workflows
        // without params say so
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("简历筛选", "wf-uuid-9", "var_jd", null, "筛选简历并邮件发送结果",
                                param("var_jd", "岗位JD", 1, true),
                                param("var_resume", "简历附件", 4, true)),
                        richOption("旅行攻略", "wf-uuid-2", null, null, null)), 5_000L);

        assertThat(tool.spec().description())
                .contains("岗位JD[文本·必填]")
                .contains("简历附件[文件·必填]")
                .contains("筛选简历并邮件发送结果")
                .contains("手动运行")
                .contains("旅行攻略")
                .contains("无输入参数")
                .contains("向用户索取")
                .contains("精确")
                .contains("不要对同一工作流重复调用");
    }

    @Test
    void undeliverableRefusalTakesPriorityOverBlankInputRefusal() throws Exception {
        // 双门卫同时命中（存在不可投递必填 + 可投递必填文本为空）：不可投递优先——
        // 后者补全了也跑不起来，先给"手动运行"指引
        // Both preflights hit (an undeliverable required input plus a blank
        // deliverable required text): undeliverable wins — completing the
        // blank input still would not make the workflow runnable
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("简历筛选", "wf-uuid-9", "var_jd", null, null,
                        param("var_jd", "岗位JD", 1, true),
                        param("var_resume", "简历附件", 4, true))), 5_000L);

        String result = tool.execute(request("{\"workflowTitle\":\"简历筛选\"}"), context());

        assertThat(result)
                .contains("手动运行")
                .doesNotContain("向用户索取");
        verifyNoInteractions(workflowStarter);
    }

    @Test
    void catalogRemarkIsCapped() {
        // 备注摘要截到 REMARK_MAX_CHARS：长备注不撑爆描述
        // The remark digest is capped at REMARK_MAX_CHARS: long remarks cannot
        // bloat the description
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("周报生成", "wf-uuid-1", "var_user_input", null,
                        "详".repeat(120), param("var_user_input", "素材", 1, true))), 5_000L);

        assertThat(tool.spec().description())
                .contains("...")
                .doesNotContain("详".repeat(RunWorkflowTool.REMARK_MAX_CHARS));
    }

    @Test
    void unknownParamTypeFallsBackToReferenceLabel() {
        // 未知/引用类型参数兜底"引用"标签，目录不因未知类型出错
        // Unknown/reference-typed params fall back to the "引用" label; the
        // catalog never breaks on an unknown type
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("周报生成", "wf-uuid-1", "var_user_input", null, null,
                        param("var_ref", "上游引用", 99, false))), 5_000L);

        assertThat(tool.spec().description()).contains("上游引用[引用]");
    }

    @Test
    void catalogManifestCollapsesBeyondSixParams() {
        // 参数超过 MANIFEST_MAX_PARAMS：目录折叠为"等N个输入"，单行不随参数数无限膨胀
        // Beyond MANIFEST_MAX_PARAMS the manifest collapses into "等N个输入":
        // one catalog line never scales with an unbounded param count
        RunWorkflowTool.WorkflowParamInfo[] params = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> param("var_p" + i, "参数" + String.format("%02d", i), 1, false))
                .toArray(RunWorkflowTool.WorkflowParamInfo[]::new);
        RunWorkflowTool tool = new RunWorkflowTool(workflowStarter,
                List.of(richOption("大杂烩", "wf-uuid-9", "var_p1", null, null, params)), 5_000L);

        assertThat(tool.spec().description())
                .contains("参数06")
                .doesNotContain("参数07")
                .contains("等8个输入");
    }

    // ==================== 构造与桩 / construction and stubs ====================

    /** 默认被测工具：两个可见工作流（一个带文本输入定义、一个不带）/ Default tool under test */
    private RunWorkflowTool defaultTool() {
        return new RunWorkflowTool(workflowStarter,
                List.of(option("周报生成", "wf-uuid-1", "var_user_input", null),
                        option("旅行攻略", "wf-uuid-2", null, null)),
                5_000L);
    }

    private RunWorkflowTool.WorkflowOption option(String title, String uuid, String inputParamName,
                                                  Integer inputMaxLength) {
        return new RunWorkflowTool.WorkflowOption(title, uuid, inputParamName, inputMaxLength);
    }

    /** 带参数清单与备注的清单条目（门卫前置/目录清单用例）/ Catalog entry with manifest + remark */
    private RunWorkflowTool.WorkflowOption richOption(String title, String uuid, String inputParamName,
                                                      Integer inputMaxLength, String remark,
                                                      RunWorkflowTool.WorkflowParamInfo... params) {
        return new RunWorkflowTool.WorkflowOption(title, uuid, inputParamName, inputMaxLength,
                remark, List.of(params));
    }

    /** 参数清单条目（type：1=文本 2=数字 3=下拉 4=文件 5=布尔）/ Manifest entry */
    private RunWorkflowTool.WorkflowParamInfo param(String name, String title, int type, boolean required) {
        return new RunWorkflowTool.WorkflowParamInfo(name, title, type, required);
    }

    private ToolContext context() {
        return ToolContext.builder().user(user).build();
    }

    private ToolExecutionRequest request(String arguments) {
        return ToolExecutionRequest.builder()
                .id("req-1")
                .name(RunWorkflowTool.NAME)
                .arguments(arguments)
                .build();
    }

    /** blocking 成功形态 {task_id, status, outputs:{output:{type,title,value}}} / Success shape */
    private Map<String, Object> completedResult(String outputValue) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", "rt-1");
        result.put("status", "completed");
        result.put("outputs", textOutputs("output", outputValue));
        return result;
    }

    private ObjectNode textOutputs(String paramName, String value) {
        ObjectNode outputs = JsonUtil.createObjectNode();
        outputs.set(paramName, textContent(value));
        return outputs;
    }

    private ObjectNode textContent(String value) {
        ObjectNode content = JsonUtil.createObjectNode();
        content.put("type", 1);
        content.put("title", "输出");
        content.put("value", value);
        return content;
    }
}
