package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具循环的单轮执行层：把一轮模型工具请求解析、执行并转成结果消息列表。
 * 职责边界——本类只负责「一轮」：按名解析缺失工具、协作类工具分派（挂起/上限内联
 * 回答/同轮忽略）、连续失败熔断、超时与结果截断护栏、执行轨迹、SSE 步骤事件；
 * 循环骨架（深度递归、轮数上限收尾、挂起检查点合成）仍属于
 * {@link AbstractLLMService} 的流式/阻塞两条调用循环。
 * <p>
 * The per-round execution layer of the tool loop: parses one round of model
 * tool requests, executes them, and turns them into result messages. Scope
 * boundary — this class owns a single "round" only: resolving missing
 * executors by name, dispatching collaborative tools (suspend / cap-reached
 * inline answer / same-round skip), the consecutive-failure breaker, timeout
 * and result-truncation guardrails, execution traces, and SSE step events.
 * The loop skeleton itself (depth recursion, iteration-cap wrap-up, suspension
 * checkpoint synthesis) stays with the streaming/blocking call loops in
 * {@link AbstractLLMService}.
 */
@Slf4j
public class ToolRoundExecutor {

    /**
     * 内置工具超时执行线程池：守护线程避免阻塞 JVM 退出，仅在有内置工具执行时按需创建线程
     * <p>
     * Pool enforcing timeouts for builtin tool execution: daemon threads avoid
     * blocking JVM shutdown, and threads are created on demand only.
     */
    private static final ExecutorService TOOL_EXECUTION_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "builtin-tool-executor");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 一轮工具执行的产物：parsedRequests 是补全 id/name 后的请求快照（供挂起恢复
     * 重建用），resultMessages 只含已执行工具的结果——被挂起的请求与其余协作请求
     * 的结果由恢复轮统一注入。ToolExecutionRequest 的 builder 对空 id 非幂等
     * （每次调用生成新随机 id，重复解析会破坏请求/结果配对）
     * <p>
     * Outcome of one tool round: parsedRequests is the id/name-completed
     * request snapshot (used to rebuild state at resume); resultMessages holds
     * only the executed tools' results — the suspended request and the other
     * collaborative requests get theirs injected at resume time.
     */
    public record ToolRoundOutcome(List<ToolExecutionRequest> parsedRequests,
                                   List<ToolExecutionResultMessage> resultMessages) {
    }

    /**
     * 执行一轮工具请求：请求先统一解析补全 id/name（快照与执行共用同一批对象，见
     * {@link ToolRoundOutcome}），非协作类工具照常执行；协作类工具（ask_user 等）交由
     * {@link #executeCollaborativeRequest} 处理（挂起/上限内联回答/同轮忽略）
     * <p>
     * Executes one round of tool requests: every request is parsed first to
     * complete its id/name (snapshot and execution share the same objects, see
     * {@link ToolRoundOutcome}); non-collaborative tools run exactly as before;
     * collaborative tools (ask_user etc.) go through
     * {@link #executeCollaborativeRequest} (suspend / cap-reached inline answer
     * / same-round skip).
     */
    public ToolRoundOutcome executeToolRound(AiMessage aiMessage, Map<String, ToolExecutor> toolExecutorMap, ToolContext toolContext, String sseUuid) {
        ZhiMeshProperties.Agent agentSettings = resolveAgentSettings();
        List<ToolExecutionRequest> parsedRequests = new ArrayList<>();
        List<ToolExecutionResultMessage> toolExecutionMessages = new ArrayList<>();
        for (ToolExecutionRequest rawRequest : aiMessage.toolExecutionRequests()) {
            ToolExecutionRequest req = parseToolRequest(rawRequest);
            parsedRequests.add(req);
            log.info("Tool execution requested, toolName:{}", req.name());
            ToolExecutor executor = toolExecutorMap.get(req.name());
            if (null == executor) {
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req,
                        "No Tool executor found for this tool request"));
                recordToolCallTrace(toolContext, req, 0L, false, "No Tool executor found for this tool request");
                continue;
            }
            if (executor.isCollaborative()) {
                executeCollaborativeRequest(req, executor, toolContext, sseUuid, agentSettings, toolExecutionMessages);
                continue;
            }
            // 连续失败熔断：达到 max-tool-failures-per-tool 后本请求不再真正执行该工具，
            // 熔断说明回喂模型——否则模型会每轮重试一个已死的外部工具直到轮数上限
            // Consecutive-failure breaker: at max-tool-failures-per-tool the tool
            // is no longer executed in this request and the breaker notice is fed
            // back to the model — otherwise it retries a dead external tool every
            // round until the iteration cap
            int maxToolFailures = agentSettings.getMaxToolFailuresPerTool();
            Integer priorFailures = null == toolContext ? null
                    : toolContext.getToolFailureCounts().get(req.name());
            if (maxToolFailures > 0 && null != priorFailures && priorFailures >= maxToolFailures) {
                String breakerNotice = "Tool " + req.name() + " failed " + priorFailures
                        + " consecutive times in this request and is now disabled for it;"
                        + " answer from the information already collected";
                log.warn("Tool disabled by consecutive-failure breaker, toolName:{}, failures:{}",
                        req.name(), priorFailures);
                SseManager.sendToolCall(sseUuid, req.name(), 0L, false, req.arguments(), breakerNotice);
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, breakerNotice));
                recordToolCallTrace(toolContext, req, 0L, false, breakerNotice);
                continue;
            }
            // 执行前先发 TOOL_STARTED：前端实时点亮该工具的 running 步骤（与下方 TOOL_CALL
            // 完成事件配对）。「No Tool executor found」分支不发——没有可点亮的真实执行
            // Send TOOL_STARTED before execution so the frontend lights up the
            // running step in real time (paired with the TOOL_CALL completion
            // event below). The "No Tool executor found" branch sends nothing —
            // there is no real execution to light up.
            SseManager.sendToolStarted(sseUuid, req.name(), req.arguments());
            long toolStart = System.currentTimeMillis();
            try {
                final String result = executeToolWithGuardrails(executor, req, toolContext, agentSettings);
                long toolDuration = System.currentTimeMillis() - toolStart;
                log.info("Tool execution completed, toolName:{}, resultLength:{}, duration:{}ms",
                        req.name(), result == null ? 0 : result.length(), toolDuration);
                if (null != toolContext) {
                    toolContext.getToolFailureCounts().remove(req.name());
                }
                SseManager.sendToolCall(sseUuid, req.name(), toolDuration, true, req.arguments(), result);
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, result));
                recordToolCallTrace(toolContext, req, toolDuration, true, result);
            } catch (Exception e) {
                long toolDuration = System.currentTimeMillis() - toolStart;
                log.warn("Tool execution failed, toolName:{}, duration:{}ms, errorType:{}",
                        req.name(), toolDuration, e.getClass().getSimpleName());
                if (null != toolContext) {
                    toolContext.getToolFailureCounts().merge(req.name(), 1, Integer::sum);
                }
                SseManager.sendToolCall(sseUuid, req.name(), toolDuration, false, req.arguments(), e.getMessage());
                toolExecutionMessages.add(ToolExecutionResultMessage.from(req, e.getMessage()));
                recordToolCallTrace(toolContext, req, toolDuration, false, e.getMessage());
            }
        }
        return new ToolRoundOutcome(parsedRequests, toolExecutionMessages);
    }

    /**
     * 协作类工具请求的处理分支（ask_user 等）：
     * <ol>
     *   <li>同轮已有协作工具挂起 → 本请求不执行、不产生结果消息（“一轮只挂第一个”），
     *       「已被忽略，请单独重新发起」占位结果由恢复轮统一注入；</li>
     *   <li>挂起能力不可用（无 ToolContext / 未接线 suspensionSink）或挂起次数已达
     *       zhimesh.agent.max-suspensions 上限（防无限追问）→ 不挂起，返回引导文本，
     *       工具循环照常继续，模型基于已有信息直接作答；</li>
     *   <li>正常挂起 → 工具执行置位 ToolContext.suspensionSignal，轨迹记录
     *       （toolName、success=true、resultSummary=问题文本），本轮不产生
     *       结果消息，由 onCompleteResponse 的挂起分支落检查点并合成收尾；挂起事件
     *       （agent_question / approval_request，按 kind 分发）由挂起分支在检查点
     *       持久化成功后才发出（见 suspendToolLoop）。</li>
     * </ol>
     * <p>
     * Handling branch for collaborative tool requests (ask_user etc.):
     * <ol>
     *   <li>another collaborative tool already suspended this round → this
     *       request is neither executed nor given a result message ("only the
     *       first suspends"); its "ignored, re-ask alone" placeholder is
     *       injected at resume time;</li>
     *   <li>suspension unavailable (no ToolContext / no suspensionSink wired)
     *       or the zhimesh.agent.max-suspensions cap already hit (infinite
     *       asking guard) → no suspension; a guidance text is returned as the
     *       tool result and the loop continues normally so the model answers
     *       directly from what it has;</li>
     *   <li>ordinary suspension → the tool's execution sets
     *       ToolContext.suspensionSignal, the trace is recorded (toolName,
     *       success = true, resultSummary = the question), and this
     *       round yields no result message — onCompleteResponse's suspension
     *       branch persists the checkpoint and produces the synthesized
     *       wrap-up; the suspension event (agent_question / approval_request,
     *       dispatched by kind) is emitted by that branch only
     *       after the checkpoint is durably persisted (see suspendToolLoop).</li>
     * </ol>
     */
    private void executeCollaborativeRequest(ToolExecutionRequest req, ToolExecutor executor, ToolContext toolContext,
                                             String sseUuid, ZhiMeshProperties.Agent agentSettings,
                                             List<ToolExecutionResultMessage> toolExecutionMessages) {
        if (null != toolContext && null != toolContext.getSuspensionSignal()) {
            log.info("Collaborative tool request skipped: another suspension already raised in this round, toolName:{}",
                    req.name());
            return;
        }
        if (null == toolContext || null == toolContext.getSuspensionSink()
                || toolContext.getSuspensionCount() >= agentSettings.getMaxSuspensions()) {
            String guidance = null != toolContext && null != toolContext.getSuspensionSink()
                    ? AbstractLLMService.SUSPENSION_LIMIT_REACHED_TEXT : AbstractLLMService.SUSPENSION_UNSUPPORTED_TEXT;
            log.info("Collaborative tool answered inline instead of suspending, toolName:{}, guidance:{}",
                    req.name(), guidance);
            SseManager.sendToolCall(sseUuid, req.name(), 0L, true, req.arguments(), guidance);
            toolExecutionMessages.add(ToolExecutionResultMessage.from(req, guidance));
            recordToolCallTrace(toolContext, req, 0L, true, guidance);
            return;
        }
        // 通过协作闸门后才发 TOOL_STARTED：内联回答分支（不真正挂起/执行）不发，
        // 避免前端点亮一个从未执行的步骤
        // Send TOOL_STARTED only after the collaborative gates pass: the
        // inline-answer branches (no real suspension/execution) send nothing,
        // so the frontend never lights up a step that never ran.
        SseManager.sendToolStarted(sseUuid, req.name(), req.arguments());
        long toolStart = System.currentTimeMillis();
        try {
            final String result = executeToolWithGuardrails(executor, req, toolContext, agentSettings);
            long toolDuration = System.currentTimeMillis() - toolStart;
            SuspensionSignal raised = toolContext.getSuspensionSignal();
            if (null != raised) {
                log.info("Collaborative tool raised suspension, toolName:{}, duration:{}ms", req.name(), toolDuration);
                recordToolCallTrace(toolContext, req, toolDuration, true, raised.getQuestion());
                // agent_question 事件不在此发：挂起分支（suspendToolLoop）落检查点成功后
                // 才发卡，杜绝「前端已见卡片但无检查点可恢复」的半挂起态
                // The agent_question event is NOT emitted here: the suspension
                // branch (suspendToolLoop) sends the card only after the
                // checkpoint is durably persisted, precluding a half-suspended
                // state (card shown yet nothing to resume from)
                return;
            }
            // 防御：协作工具执行成功却未置挂起信号——按普通工具结果继续本轮循环
            // Defensive: a collaborative tool finished without raising the
            // suspension signal — treat it as an ordinary tool result
            log.info("Collaborative tool finished without suspension, toolName:{}, duration:{}ms",
                    req.name(), toolDuration);
            SseManager.sendToolCall(sseUuid, req.name(), toolDuration, true, req.arguments(), result);
            toolExecutionMessages.add(ToolExecutionResultMessage.from(req, result));
            recordToolCallTrace(toolContext, req, toolDuration, true, result);
        } catch (Exception e) {
            long toolDuration = System.currentTimeMillis() - toolStart;
            log.warn("Collaborative tool execution failed, toolName:{}, duration:{}ms, errorType:{}",
                    req.name(), toolDuration, e.getClass().getSimpleName());
            SseManager.sendToolCall(sseUuid, req.name(), toolDuration, false, req.arguments(), e.getMessage());
            toolExecutionMessages.add(ToolExecutionResultMessage.from(req, e.getMessage()));
            recordToolCallTrace(toolContext, req, toolDuration, false, e.getMessage());
        }
    }

    /**
     * 执行工具并应用超时与结果截断保护：内置工具始终受保护；MCP 工具是否纳入同一保护
     * 由 zhimesh.agent.mcp-guardrails-enabled 决定（默认关，保持原有直调零回归，
     * 开启后同样走下方超时——含池线程 run_workflow 递归标记传播——与结果截断）。
     * 轨迹（recordToolCallTrace）在调用点对 MCP 工具本就生效，此处不重复记录
     * <p>
     * Execute a tool, applying timeout and result-truncation guardrails:
     * builtin tools are always guarded; whether MCP tools join the same
     * guardrails is decided by zhimesh.agent.mcp-guardrails-enabled (default
     * off, preserving the legacy direct call with zero regression; once on,
     * MCP tools go through the timeout below — including the run_workflow
     * recursion-flag propagation on pool threads — and result truncation).
     * Tracing (recordToolCallTrace) already covers MCP tools at the call
     * sites, so it is not duplicated here.
     */
    private String executeToolWithGuardrails(ToolExecutor executor, ToolExecutionRequest req,
                                             ToolContext toolContext, ZhiMeshProperties.Agent agentSettings) throws Exception {
        if (!agentSettings.isMcpGuardrailsEnabled() && executor.isMcpTool()) {
            return executor.execute(req, toolContext);
        }
        String result;
        long timeoutMs = agentSettings.getToolTimeoutMs();
        if (timeoutMs > 0) {
            // 工具在池线程上执行：把提交线程的 run_workflow 递归标记带过去。工作流线程
            // （标记已置位）内再起 agent 时，其工具也走本执行器——不传播的话嵌套
            // run_workflow 会在池线程上被放行，递归防护失效；结束时一律清除，
            // 池线程复用不会携带脏标记
            // Tools execute on pool threads: carry the submitting thread's
            // run_workflow recursion flag across. An agent started inside a
            // workflow thread (flag set) also runs its tools through this
            // executor — without propagation a nested run_workflow would be
            // allowed on the pool thread and the guard would be moot; always
            // clear on exit so a reused pool thread never carries a stale flag
            Boolean inRunWorkflow = RunWorkflowTool.IN_RUN_WORKFLOW.get();
            Future<String> future = TOOL_EXECUTION_EXECUTOR.submit(() -> {
                if (Boolean.TRUE.equals(inRunWorkflow)) {
                    RunWorkflowTool.IN_RUN_WORKFLOW.set(Boolean.TRUE);
                } else {
                    RunWorkflowTool.IN_RUN_WORKFLOW.remove();
                }
                try {
                    return executor.execute(req, toolContext);
                } finally {
                    RunWorkflowTool.IN_RUN_WORKFLOW.remove();
                }
            });
            try {
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException(
                        "Tool execution timed out after " + timeoutMs + "ms, toolName:" + req.name(), e);
            } catch (ExecutionException e) {
                Throwable cause = null != e.getCause() ? e.getCause() : e;
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(cause);
            }
        } else {
            result = executor.execute(req, toolContext);
        }
        return truncateToolResult(result, agentSettings.getToolResultMaxChars());
    }

    /**
     * 超长工具结果截断到 maxChars，截断时末尾追加标记
     * Truncate an oversized tool result to maxChars, appending a marker when truncated
     */
    private String truncateToolResult(String result, int maxChars) {
        if (null == result || result.length() <= maxChars) {
            return result;
        }
        return result.substring(0, maxChars) + "\n...[truncated]";
    }

    /**
     * 记录一次工具调用轨迹到请求级上下文（上下文或收集器为空时静默跳过）
     * Record one tool-call trace into the request-scoped context (silently
     * skipped when the context or collector is absent)
     */
    private void recordToolCallTrace(ToolContext toolContext, ToolExecutionRequest req,
                                     long durationMs, boolean success, String resultSummary) {
        if (null == toolContext || null == toolContext.getToolTraces()) {
            return;
        }
        List<ToolCallTrace> traces = toolContext.getToolTraces();
        traces.add(ToolCallTrace.builder()
                .seq(traces.size())
                .toolName(req.name())
                .args(req.arguments())
                .resultSummary(resultSummary)
                .durationMs(durationMs)
                .success(success)
                .build());
    }

    /**
     * 读取工具调用循环配置；容器或配置不可用时回退到默认值
     * Resolve tool-loop settings; falls back to defaults when the container or
     * the properties bean is unavailable
     */
    public ZhiMeshProperties.Agent resolveAgentSettings() {
        try {
            ZhiMeshProperties properties = SpringUtil.getBean(ZhiMeshProperties.class);
            if (null != properties && null != properties.getAgent()) {
                return properties.getAgent();
            }
        } catch (Exception e) {
            log.debug("ZhiMeshProperties unavailable, using default agent tool-loop settings", e);
        }
        return new ZhiMeshProperties.Agent();
    }

    /**
     * 如果工具请求参数中没有包含id，则手动解析该参数以补充 id 和 name
     * 部分模型（如硅基流动）返回的工具请求可能没有id和name，需要手动解析，如 ToolExecutionRequest { id = "", name = "", arguments = "maps_weather {"city": "广州"}" }
     * If the tool request parameter does not contain an id, manually parse it to supplement id and name
     * Some models (e.g., SiliconFlow) may return tool requests without id and name, requiring manual parsing
     *
     * @param req 工具请求参数 / Tool request parameter
     */
    private ToolExecutionRequest parseToolRequest(ToolExecutionRequest req) {
        if (StringUtils.isBlank(req.id())) {
            String arguments = req.arguments();
            String name = req.name();
            if (StringUtils.isBlank(name) && StringUtils.isNotBlank(arguments) && !arguments.startsWith("{")) {
                String[] args = arguments.split(" ");
                if (args.length > 0) {
                    name = args[0];
                    arguments = arguments.substring(name.length()).trim();
                } else {
                    name = "name_" + UuidUtil.createShort();
                }
            }
            return ToolExecutionRequest.builder().id("id_" + UuidUtil.createShort()).name(name).arguments(arguments).build();
        }
        return req;
    }
}
