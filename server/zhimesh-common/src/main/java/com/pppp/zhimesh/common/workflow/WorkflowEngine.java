package com.pppp.zhimesh.common.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeMetricsSummary;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeDto;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeResp;
import com.pppp.zhimesh.common.entity.*;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.WorkflowRuntimeNodeService;
import com.pppp.zhimesh.common.service.WorkflowRuntimeService;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.workflow.NodeExecutionMetrics;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.def.WfNodeIO;
import com.pppp.zhimesh.common.workflow.metrics.LLMMetrics;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.generators.StreamingChatGenerator;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import com.pppp.zhimesh.common.helper.SseManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.*;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;
import static com.pppp.zhimesh.common.workflow.WfComponentNameEnum.HUMAN_FEEDBACK;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
public class WorkflowEngine {
    private CompiledGraph<WfNodeState> app;
    private final Workflow workflow;
    private final List<WorkflowComponent> components;
    private final List<WorkflowNode> wfNodes;
    private final List<WorkflowEdge> wfEdges;
    private final SseManager sseManager;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final WorkflowRuntimeNodeService workflowRuntimeNodeService;
    private final WorkflowRuntimeExecutionRegistry workflowRuntimeExecutionRegistry;

    private final ObjectStreamStateSerializer<WfNodeState> stateSerializer = new ObjectStreamStateSerializer<>(WfNodeState::new);
    private final Map<String, List<StateGraph<WfNodeState>>> stateGraphNodes = new HashMap<>();
    private final Map<String, List<StateGraph<WfNodeState>>> stateGraphEdges = new HashMap<>();
    private final Map<String, String> rootToSubGraph = new HashMap<>();
    private final Map<String, GraphCompileNode> nodeToParallelBranch = new HashMap<>();

    private String sseUuid;
    private User user;
    private WfState wfState;
    private WfRuntimeResp wfRuntimeResp;
    /**
     * Wall-clock start of this run (epoch millis), captured once on entry. Used to compute total
     * runtime duration at terminal status — sum-of-node durations would inflate when parallel
     * branches execute concurrently.
     */
    private long runStartedMillis;

    public WorkflowEngine(
            Workflow workflow,
            SseManager sseManager,
            List<WorkflowComponent> components,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> wfEdges,
            WorkflowRuntimeService workflowRuntimeService,
            WorkflowRuntimeNodeService workflowRuntimeNodeService,
            WorkflowRuntimeExecutionRegistry workflowRuntimeExecutionRegistry) {
        this.workflow = workflow;
        this.sseManager = sseManager;
        this.components = components;
        this.wfNodes = nodes;
        this.wfEdges = wfEdges;
        this.workflowRuntimeService = workflowRuntimeService;
        this.workflowRuntimeNodeService = workflowRuntimeNodeService;
        this.workflowRuntimeExecutionRegistry = workflowRuntimeExecutionRegistry;
    }

    public void run(User user, List<ObjectNode> userInputs, String sseUuid) {
        this.user = user;
        this.sseUuid = sseUuid;
        this.runStartedMillis = System.currentTimeMillis();
        log.info("WorkflowEngine run,userId:{},workflowUuid:{},userInputs:{}", user.getId(), workflow.getUuid(), userInputs);
        if (!this.workflow.getIsEnable()) {
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage(ErrorEnum.A_WF_DISABLED.getInfo()));
            throw new BaseException(ErrorEnum.A_WF_DISABLED);
        }

        try {
            Long workflowId = this.workflow.getId();
            this.wfRuntimeResp = workflowRuntimeService.create(user, workflowId);
            workflowRuntimeExecutionRegistry.register(this.wfRuntimeResp.getId());
            this.sseManager.startSse(user, sseUuid, JsonUtil.toJson(wfRuntimeResp));
            throwIfCancellationRequested();

            String runtimeUuid = this.wfRuntimeResp.getUuid();
            Pair<WorkflowNode, Set<WorkflowNode>> startAndEnds = findStartAndEndNode();
            WorkflowNode startNode = startAndEnds.getLeft();
            List<NodeIOData> wfInputs = getAndCheckUserInput(userInputs, startNode);
            //工作流运行实例状态
            this.wfState = new WfState(user, wfInputs, runtimeUuid);
            workflowRuntimeService.updateInput(this.wfRuntimeResp.getId(), wfState);
            CompileNode rootCompileNode = new CompileNode();
            rootCompileNode.setId(startNode.getUuid());

            //构建整棵树（检测节点重复访问防止无限循环）
            Map<String, Integer> nodeVisitCount = new HashMap<>();
            buildCompileNode(rootCompileNode, startNode, nodeVisitCount);

//Main state graph
            //主状态图
            StateGraph<WfNodeState> mainStateGraph = new StateGraph<>(stateSerializer);
            this.wfState.addEdge(START, startNode.getUuid());
            //构建包括所有节点的状态图
            buildStateGraph(null, mainStateGraph, rootCompileNode);

            MemorySaver saver = new MemorySaver();
            CompileConfig compileConfig = CompileConfig.builder()
                    .checkpointSaver(saver)
                    .interruptBefore(wfState.getInterruptNodes().toArray(String[]::new))
                    .build();
            app = mainStateGraph.compile(compileConfig);
            RunnableConfig invokeConfig = RunnableConfig.builder()
                    .build();
            exe(invokeConfig, false);
        } catch (Throwable e) {
            if (isCancellation(e) || cancellationRequested()) {
                cancelWhenExe();
            } else {
                errorWhenExe(e);
            }
        }
    }

    private void exe(RunnableConfig invokeConfig, boolean resume) {
        throwIfCancellationRequested();
//Do not use langgraph4j state update methods, no need to pass input
        //不使用langgraph4j state的update相关方法，无需传入input
        AsyncGenerator<NodeOutput<WfNodeState>> outputs = app.stream(resume ? null : Map.of(), invokeConfig);
        streamingResult(wfState, outputs, sseUuid);
        throwIfCancellationRequested();

        StateSnapshot<WfNodeState> stateSnapshot = app.getState(invokeConfig);
        String nextNode = stateSnapshot.config().nextNode().orElse("");
        //还有下个节点，表示进入中断状态，等待用户输入后继续执行
        if (StringUtils.isNotBlank(nextNode) && !nextNode.equalsIgnoreCase(END)) {
            throwIfCancellationRequested();
            String intTip = WorkflowUtil.getHumanFeedbackTip(nextNode, wfNodes);
            pushSseEvent("[NODE_WAIT_FEEDBACK_BY_" + nextNode + "]", intTip);
            InterruptedFlow.put(wfState.getUuid(), this);
            //更新状态
            wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_WAITING_INPUT);
            WorkflowRuntime interruptedRuntime = workflowRuntimeService.updateOutput(wfRuntimeResp.getId(), wfState, runStartedMillis, computeTokenSummary());
            throwIfRuntimeCancelling(interruptedRuntime);
            throwIfCancellationRequested();
            pushRuntimeMetrics(interruptedRuntime);
            stopExecutionHeartbeat();
        } else {
            throwIfCancellationRequested();
            WorkflowRuntime updatedRuntime = workflowRuntimeService.completeSuccess(
                    wfRuntimeResp.getId(), wfState, runStartedMillis, computeTokenSummary());
            throwIfRuntimeCancelling(updatedRuntime);
            throwIfCancellationRequested();
            // Push the terminal metrics snapshot before completing the stream
            pushRuntimeMetrics(updatedRuntime);
            // updatedRuntime is null only if the runtime row was concurrently deleted; fall back
            // to the in-memory wfState output so the client still gets a complete event.
            ObjectNode output = updatedRuntime != null ? updatedRuntime.getOutput() : null;
            sseManager.sendComplete(user.getId(), sseUuid, JsonUtil.toJson(output != null ? output : JsonUtil.createObjectNode()));
            InterruptedFlow.remove(wfState.getUuid());
            stopExecutionHeartbeat();
        }
    }

    /**
     * 中断流程等待用户输入时，会进行暂停状态，用户输入后调用本方法执行流程剩余部分
     *
     * @param userInput 用户输入
     */
    public void resume(String userInput) {
        RunnableConfig invokeConfig = RunnableConfig.builder().build();
        workflowRuntimeExecutionRegistry.register(wfRuntimeResp.getId());
        try {
            throwIfCancellationRequested();
            if (!workflowRuntimeService.claimWaitingInput(wfRuntimeResp.getId())) {
                if (workflowRuntimeService.isCancellationRequested(wfRuntimeResp.getId())) {
                    throw new WorkflowCancelledException();
                }
                return;
            }
            wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_DOING);
            app.updateState(invokeConfig, Map.of(HUMAN_FEEDBACK_KEY, userInput), null);
            exe(invokeConfig, true);
        } catch (Throwable e) {
            if (isCancellation(e) || cancellationRequested()) {
                cancelWhenExe();
            } else {
                errorWhenExe(e);
            }
        } finally {
            //有可能多次接收人机交互，待整个流程完全执行后才能删除
            if (wfState.getProcessStatus() != WORKFLOW_PROCESS_STATUS_WAITING_INPUT) {
                InterruptedFlow.remove(wfState.getUuid());
            }
        }
    }

    /**
     * Blocking execution: build the workflow graph, run it synchronously,
     * and return the final output as a JSON response.
     */
    public Map<String, Object> blockingRun(User user, List<ObjectNode> userInputs) {
        this.user = user;
        this.runStartedMillis = System.currentTimeMillis();
        // Create a dummy sseUuid with a dummy emitter and immediately mark it completed,
        // so all SSE send operations in runNode silently skip (COMPLETED_SSE cache check)
        this.sseUuid = "blocking-" + com.pppp.zhimesh.common.util.UuidUtil.createShort();
        SseEmitter dummyEmitter = new SseEmitter(0L);
        SpringUtil.getBean(com.pppp.zhimesh.common.helper.SseManager.class).register(this.sseUuid, dummyEmitter);
        sseManager.sendComplete(user.getId(), this.sseUuid);
        log.info("WorkflowEngine blockingRun,userId:{},workflowUuid:{},userInputs:{}", user.getId(), workflow.getUuid(), userInputs);
        if (!this.workflow.getIsEnable()) {
            throw new BaseException(ErrorEnum.A_WF_DISABLED);
        }

        try {
            Long workflowId = this.workflow.getId();
            this.wfRuntimeResp = workflowRuntimeService.create(user, workflowId);
            workflowRuntimeExecutionRegistry.register(this.wfRuntimeResp.getId());

            String runtimeUuid = this.wfRuntimeResp.getUuid();
            Pair<WorkflowNode, Set<WorkflowNode>> startAndEnds = findStartAndEndNode();
            WorkflowNode startNode = startAndEnds.getLeft();
            List<NodeIOData> wfInputs = getAndCheckUserInput(userInputs, startNode);
            this.wfState = new WfState(user, wfInputs, runtimeUuid);
            if (!wfState.getInterruptNodes().isEmpty()) {
                throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
            }
            workflowRuntimeService.updateInput(this.wfRuntimeResp.getId(), wfState);

            CompileNode rootCompileNode = new CompileNode();
            rootCompileNode.setId(startNode.getUuid());

            Map<String, Integer> nodeVisitCount = new HashMap<>();
            buildCompileNode(rootCompileNode, startNode, nodeVisitCount);

            StateGraph<WfNodeState> mainStateGraph = new StateGraph<>(stateSerializer);
            this.wfState.addEdge(START, startNode.getUuid());
            buildStateGraph(null, mainStateGraph, rootCompileNode);

            MemorySaver saver = new MemorySaver();
            CompileConfig compileConfig = CompileConfig.builder()
                    .checkpointSaver(saver)
                    .build();
            app = mainStateGraph.compile(compileConfig);
            RunnableConfig invokeConfig = RunnableConfig.builder().build();

            // Synchronous invoke instead of streaming
            app.invoke(Map.of(), invokeConfig);

            //Consume any streaming generators to ensure mapResult callbacks fire (e.g. token recording)
            //消费流式生成器以确保 mapResult 回调被触发（如 token 记录）
            for (StreamingChatGenerator<AgentState> generator : wfState.getNodeToStreamingGenerator().values()) {
                for (Object ignored : generator) {
                    // drain the generator to trigger mapResult
                }
            }

            // Collect output from the last completed node (end node or final node)
            List<AbstractWfNode> completedNodes = wfState.getCompletedNodes();
            if (!completedNodes.isEmpty()) {
                wfState.setOutput(completedNodes.get(completedNodes.size() - 1).getState().getOutputs());
            }

            WorkflowRuntime updatedRuntime = workflowRuntimeService.completeSuccess(
                    wfRuntimeResp.getId(), wfState, runStartedMillis, computeTokenSummary());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task_id", runtimeUuid);
            data.put("status", "completed");
            if (null != updatedRuntime) {
                data.put("outputs", updatedRuntime.getOutput());
            }
            stopExecutionHeartbeat();
            return data;
        } catch (Throwable e) {
            log.error("blockingRun execution exception, workflowUuid:{}", workflow.getUuid(), e);
            String errorMsg = safeErrorMessage(e);
            persistFailureStatus(errorMsg);
            stopExecutionHeartbeat();

            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("message", errorMsg);
            return errorResult;
        }
    }

    private void errorWhenExe(Throwable e) {
        log.error("error", e);
        String errorMsg = safeErrorMessage(e);
        if (errorMsg.contains("parallel node doesn't support conditional branch")) {
            errorMsg = "Parallel nodes cannot contain conditional branches";
        }
        // Persist terminal snapshot (incl. already-consumed tokens) as best-effort; the SSE error
        // MUST always reach the client even if persistence fails (DB unavailable, row gone, etc.).
        persistFailureStatus(errorMsg);
        stopExecutionHeartbeat();
        if (wfState != null) {
            InterruptedFlow.remove(wfState.getUuid());
        }
        sseManager.sendErrorAndComplete(user.getId(), sseUuid, errorMsg);
    }

    /** Finalize a flow paused for input, or one whose active worker observed a cancellation flag. */
    public void cancel() {
        cancelWhenExe();
    }

    private synchronized void cancelWhenExe() {
        if (wfRuntimeResp == null || wfRuntimeResp.getId() == null) {
            return;
        }
        WorkflowRuntime cancelledRuntime = workflowRuntimeService.completeCancellation(
                wfRuntimeResp.getId(), runStartedMillis, computeTokenSummary());
        if (cancelledRuntime == null
                || !Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCELLED).equals(cancelledRuntime.getStatus())) {
            stopExecutionHeartbeat();
            return;
        }

        if (wfState != null) {
            wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_CANCELLED);
            InterruptedFlow.remove(wfState.getUuid());
        }
        workflowRuntimeNodeService.cancelOpenNodes(wfRuntimeResp.getId());
        pushRuntimeMetrics(cancelledRuntime);
        pushSseEvent(ZhiMeshConstant.SSEEventName.RUNTIME_CANCELLED,
                JsonUtil.toJson(Map.of("status", WORKFLOW_PROCESS_STATUS_CANCELLED,
                        "message", "工作流已取消")));
        sseManager.close(user.getId(), sseUuid);
        stopExecutionHeartbeat();
    }

    private void throwIfCancellationRequested() {
        if (cancellationRequested()) {
            throw new WorkflowCancelledException();
        }
    }

    private boolean cancellationRequested() {
        return wfRuntimeResp != null
                && workflowRuntimeExecutionRegistry.isCancellationRequested(wfRuntimeResp.getId());
    }

    private void throwIfRuntimeCancelling(WorkflowRuntime runtime) {
        if (runtime != null && (Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED).equals(runtime.getStatus())
                || Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCELLED).equals(runtime.getStatus()))) {
            throw new WorkflowCancelledException();
        }
    }

    private boolean isCancellation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != current) {
            if (current instanceof WorkflowCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void persistFailureStatus(String errorMsg) {
        if (wfRuntimeResp == null || wfRuntimeResp.getId() == null) {
            return;
        }
        try {
            WorkflowRuntime failedRuntime = workflowRuntimeService.updateStatus(
                    wfRuntimeResp.getId(), WORKFLOW_PROCESS_STATUS_FAIL, errorMsg,
                    runStartedMillis, computeTokenSummary());
            pushRuntimeMetrics(failedRuntime);
        } catch (Throwable persistenceEx) {
            log.error("Failed to persist runtime failure status", persistenceEx);
        }
    }

    static String safeErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Workflow execution failed";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return StringUtils.defaultIfBlank(root.getMessage(), root.getClass().getSimpleName());
    }

    /**
     * Push the runtime's terminal metrics snapshot to the client via SSE, so the run list can
     * render stats for the just-finished run without a page refresh. No-op when no metrics have
     * been persisted yet.
     */
    /**
     * Aggregate input/output token totals from completed nodes' in-memory metrics, avoiding a DB
     * round-trip. Only LLM-typed nodes (llm, agent) contribute; other node types are skipped.
     */
    private WfRuntimeMetricsSummary computeTokenSummary() {
        long inputTokens = 0;
        long outputTokens = 0;
        long duration = 0;
        if (wfState == null) {
            return new WfRuntimeMetricsSummary(0, 0, 0);
        }
        for (AbstractWfNode node : wfState.getCompletedNodes()) {
            NodeExecutionMetrics metrics = node.getState().getMetrics();
            if (metrics != null) {
                duration += metrics.getDurationMs();
                if (metrics instanceof LLMMetrics llm) {
                    if (llm.getInputTokens() != null) {
                        inputTokens += llm.getInputTokens();
                    }
                    if (llm.getOutputTokens() != null) {
                        outputTokens += llm.getOutputTokens();
                    }
                }
            }
        }
        return new WfRuntimeMetricsSummary(inputTokens, outputTokens, duration);
    }

    /**
     * Push the runtime's terminal metrics snapshot via SSE. The DB columns are NOT NULL DEFAULT 0
     * so a non-null runtime always has values; null safety is a guard against the caller passing a
     * runtime that was returned null by updateOutput/updateStatus (row concurrently deleted).
     */
    private void pushRuntimeMetrics(WorkflowRuntime runtime) {
        if (runtime == null) {
            return;
        }
        pushSseEvent(ZhiMeshConstant.SSEEventName.RUNTIME_METRICS,
                JsonUtil.toJson(Map.of("inputTokens", runtime.getInputTokens(),
                        "outputTokens", runtime.getOutputTokens(),
                        "duration", runtime.getDuration())));
    }

    /** SSE is observability transport only; a disconnected browser must not fail business work. */
    private void pushSseEvent(String eventName, String content) {
        try {
            SseManager.parseAndSendPartialMsg(sseUuid, eventName, content);
        } catch (RuntimeException e) {
            log.debug("Workflow SSE event dropped,eventName:{},runtime:{}", eventName,
                    wfRuntimeResp == null ? null : wfRuntimeResp.getUuid(), e);
        }
    }

    private void stopExecutionHeartbeat() {
        if (wfRuntimeResp != null) {
            workflowRuntimeExecutionRegistry.unregister(wfRuntimeResp.getId());
        }
    }

    private Map<String, Object> runNode(WorkflowNode wfNode, WfNodeState nodeState) {
        throwIfCancellationRequested();
        Map<String, Object> resultMap = new HashMap<>();
        try {
            WorkflowComponent wfComponent = components.stream().filter(item -> item.getId().equals(wfNode.getWorkflowComponentId())).findFirst().orElseThrow();
            AbstractWfNode abstractWfNode = WfNodeFactory.create(wfComponent, wfNode, wfState, nodeState);
            //节点实例
            WfRuntimeNodeDto runtimeNodeDto = workflowRuntimeNodeService.createByState(user, wfNode.getId(), wfRuntimeResp.getId(), nodeState);
            wfState.getRuntimeNodes().add(runtimeNodeDto);

            pushSseEvent("[NODE_RUN_" + wfNode.getUuid() + "]", JsonUtil.toJson(runtimeNodeDto));

            NodeProcessResult processResult = abstractWfNode.process((is) -> {
                workflowRuntimeNodeService.updateInput(runtimeNodeDto.getId(), nodeState);
                for (NodeIOData input : nodeState.getInputs()) {
                    pushSseEvent("[NODE_INPUT_" + wfNode.getUuid() + "]", JsonUtil.toJson(input));
                }
            }, (is) -> {
                workflowRuntimeNodeService.updateOutput(runtimeNodeDto.getId(), nodeState);

                //并行节点内部的节点执行结束后，需要主动向客户端发送输出结果
                String nodeUuid = wfNode.getUuid();
                List<NodeIOData> nodeOutputs = nodeState.getOutputs();
                for (NodeIOData output : nodeOutputs) {
                    log.info("callback node:{},output:{}", nodeUuid, output.getContent());
                    pushSseEvent("[NODE_OUTPUT_" + nodeUuid + "]", JsonUtil.toJson(output));
                }
                //推送可观测指标（流式节点由 streamingResult 统一推送，因为 token 数据在流完成后才可用）
                //Push observability metrics (streaming nodes are handled by streamingResult since token data is only available after stream completes)
                if (nodeState.getMetrics() != null && nodeState.getMetrics().getDurationMs() > 0
                        && !wfState.getNodeToStreamingGenerator().containsKey(nodeUuid)) {
                    pushSseEvent(ZhiMeshConstant.SSEEventName.NODE_METRICS_PREFIX + nodeUuid + "]", JsonUtil.toJson(nodeState.getMetrics()));
                }
            });
            throwIfCancellationRequested();
            if (StringUtils.isNotBlank(processResult.getNextNodeUuid())) {
                resultMap.put("next", processResult.getNextNodeUuid());
            }
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("Node run error", e);
            throw new BaseException(ErrorEnum.B_WF_RUN_ERROR.getCode(), buildNodeErrorMessage(wfNode, e));
        }
        resultMap.put("name", wfNode.getTitle());
//langgraph4j state data is not stored, only metadata is stored
        //langgraph4j state中的data不做数据存储，只存储元数据
        StreamingChatGenerator<AgentState> generator = wfState.getNodeToStreamingGenerator().get(wfNode.getUuid());
        if (null != generator) {
            resultMap.put("_streaming_messages", generator);
            return resultMap;
        }
        return resultMap;
    }

    private String buildNodeErrorMessage(WorkflowNode wfNode, Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String detail = StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
        return String.format("节点「%s」运行失败：%s", wfNode.getTitle(), detail);
    }

    /**
     * 流式输出结果
     *
     * @param outputs    输出
     * @param sseUuid SSE 请求标识 / SSE request identifier
     */
    private void streamingResult(WfState wfState, AsyncGenerator<NodeOutput<WfNodeState>> outputs, String sseUuid) {
        for (NodeOutput<WfNodeState> out : outputs) {
            throwIfCancellationRequested();
            if (out instanceof StreamingOutput<WfNodeState> streamingOutput) {
                String node = streamingOutput.node();
                String chunk = streamingOutput.chunk();
                log.info("node:{},chunk:{}", node, streamingOutput.chunk());
                pushSseEvent("[NODE_CHUNK_" + node + "]", chunk);
            } else {
                AbstractWfNode abstractWfNode = wfState.getCompletedNodes().stream().filter(item -> item.getNode().getUuid().endsWith(out.node())).findFirst().orElse(null);
                if (null != abstractWfNode) {
                    WfRuntimeNodeDto runtimeNodeDto = wfState.getRuntimeNodeByNodeUuid(out.node());
                    if (null != runtimeNodeDto) {
                        workflowRuntimeNodeService.updateOutput(runtimeNodeDto.getId(), abstractWfNode.getState());
                        wfState.setOutput(abstractWfNode.getState().getOutputs());
                        //流式节点完成时推送可观测指标（token 数据此时才可用）
                        //Push observability metrics when streaming node completes (token data available only now)
                        if (abstractWfNode.getState().getMetrics() != null && abstractWfNode.getState().getMetrics().getDurationMs() > 0) {
                            pushSseEvent(ZhiMeshConstant.SSEEventName.NODE_METRICS_PREFIX + out.node() + "]", JsonUtil.toJson(abstractWfNode.getState().getMetrics()));
                        }
                    } else {
                        log.warn("Can not find runtime node, node uuid:{}", out.node());
                    }
                } else {
                    log.warn("Can not find node state,node uuid:{}", out.node());
                }
            }
            throwIfCancellationRequested();
        }
    }

    /**
     * 校验用户输入并组装成工作流的输入
     *
     * @param userInputs 用户输入
     * @param startNode  开始节点定义
     * @return 正确的用户输入列表
     */
    private List<NodeIOData> getAndCheckUserInput(List<ObjectNode> userInputs, WorkflowNode startNode) {
        List<WfNodeIO> defList = startNode.getInputConfig().getUserInputs();
        List<NodeIOData> wfInputs = new ArrayList<>();
        for (WfNodeIO paramDefinition : defList) {
            String paramNameFromDef = paramDefinition.getName();
            boolean requiredParamMissing = paramDefinition.getRequired();
            for (ObjectNode userInput : userInputs) {
                NodeIOData nodeIOData = WfNodeIODataUtil.createNodeIOData(userInput);
                if (!paramNameFromDef.equalsIgnoreCase(nodeIOData.getName())) {
                    continue;
                }
                Integer dataType = nodeIOData.getContent().getType();
                if (null == dataType) {
                    throw new BaseException(A_WF_INPUT_INVALID);
                }
                requiredParamMissing = false;
                boolean valid = paramDefinition.checkValue(nodeIOData);
                if (!valid) {
                    log.error("Invalid user input, workflowId:{}", startNode.getWorkflowId());
                    throw new BaseException(ErrorEnum.A_WF_INPUT_INVALID);
                }
                wfInputs.add(nodeIOData);
            }
            if (requiredParamMissing) {
                log.error("Required parameter in flow definition not provided, name:{}", paramNameFromDef);
                throw new BaseException(A_WF_INPUT_MISSING);
            }
        }
        return wfInputs;
    }

    /**
     * 查找开始及结束节点 <br/>
     * 开始节点只能有一个，结束节点可能多个
     *
     * @return 开始节点及结束节点列表
     */
    public Pair<WorkflowNode, Set<WorkflowNode>> findStartAndEndNode() {
        WorkflowNode startNode = null;
        Set<WorkflowNode> endNodes = new HashSet<>();
        for (WorkflowNode node : wfNodes) {
            Optional<WorkflowComponent> wfComponent = components.stream().filter(item -> item.getId().equals(node.getWorkflowComponentId())).findFirst();
            if (wfComponent.isPresent() && WfComponentNameEnum.START.getName().equals(wfComponent.get().getName())) {
                if (null != startNode) {
                    throw new BaseException(ErrorEnum.A_WF_MULTIPLE_START_NODE);
                }
                startNode = node;
            } else if (wfComponent.isPresent() && WfComponentNameEnum.END.getName().equals(wfComponent.get().getName())) {
                endNodes.add(node);
            }
        }
        if (null == startNode) {
            log.error("No start node found, workflowId:{}", wfNodes.get(0).getWorkflowId());
            throw new BaseException(ErrorEnum.A_WF_START_NODE_NOT_FOUND);
        }
        //Find all end nodes
        wfNodes.forEach(item -> {
            String nodeUuid = item.getUuid();
            boolean source = false;
            boolean target = false;
            for (WorkflowEdge edgeDef : wfEdges) {
                if (edgeDef.getSourceNodeUuid().equals(nodeUuid)) {
                    source = true;
                } else if (edgeDef.getTargetNodeUuid().equals(nodeUuid)) {
                    target = true;
                }
            }
            if (!source && target) {
                endNodes.add(item);
            }
        });
        log.info("start node:{}", startNode);
        log.info("end nodes:{}", endNodes);
        if (endNodes.isEmpty()) {
            log.error("No end node found, workflowId:{}", startNode.getWorkflowId());
            throw new BaseException(A_WF_END_NODE_NOT_FOUND);
        }
        return Pair.of(startNode, endNodes);
    }

    private static final int MAX_NODE_VISITS = 10;

    private void buildCompileNode(
            CompileNode parentNode,
            WorkflowNode node,
            Map<String, Integer> nodeVisitCount) {
        int visits = nodeVisitCount.merge(node.getUuid(), 1, Integer::sum);
        if (visits > MAX_NODE_VISITS) {
            log.error("Node {} visited more than {} times, possible infinite loop in workflow graph", node.getUuid(), MAX_NODE_VISITS);
            throw new BaseException(ErrorEnum.B_WF_RUN_ERROR);
        }
        log.info("buildByNode, parentNode:{}, node:{},title:{}", parentNode.getId(), node.getUuid(), node.getTitle());
        CompileNode newNode;
        List<String> upstreamNodeUuids = getUpstreamNodeUuids(node.getUuid());
        if (upstreamNodeUuids.isEmpty()) {
            log.error("Node {} has no upstream node", node.getUuid());
            newNode = parentNode;
        } else if (upstreamNodeUuids.size() == 1) {
            String upstreamUuid = upstreamNodeUuids.get(0);
            boolean pointToParallel = pointToParallelBranch(upstreamUuid);
            if (pointToParallel) {
                String rootId = node.getUuid();
                GraphCompileNode graphCompileNode = getOrCreateGraphCompileNode(rootId);
                appendToNextNodes(parentNode, graphCompileNode);
                newNode = graphCompileNode;
            } else if (parentNode instanceof GraphCompileNode graphCompileNode) {
                newNode = CompileNode.builder().id(node.getUuid()).conditional(false).nextNodes(new ArrayList<>()).build();
                graphCompileNode.appendToLeaf(newNode);
            } else {
                newNode = CompileNode.builder().id(node.getUuid()).conditional(false).nextNodes(new ArrayList<>()).build();
                appendToNextNodes(parentNode, newNode);
            }
        } else {
            newNode = CompileNode.builder().id(node.getUuid()).conditional(false).nextNodes(new ArrayList<>()).build();
            GraphCompileNode parallelBranch = nodeToParallelBranch.get(parentNode.getId());
            appendToNextNodes(Objects.requireNonNullElse(parallelBranch, parentNode), newNode);
        }

        if (null == newNode) {
            log.error("Node {} does not exist", node.getUuid());
            return;
        }
        List<String> downstreamUuids = getDownstreamNodeUuids(node.getUuid());
        for (String downstream : downstreamUuids) {
            Optional<WorkflowNode> n = wfNodes.stream().filter(item -> item.getUuid().equals(downstream)).findFirst();
            n.ifPresent(workflowNode -> buildCompileNode(newNode, workflowNode, nodeVisitCount));
        }
    }

    /**
     * 构建完整的stategraph
     *
     * @param upstreamCompileNode 上游节点
     * @param stateGraph          当前状态图
     * @param compileNode         当前节点
     * @throws GraphStateException 状态图异常
     */
    private void buildStateGraph(CompileNode upstreamCompileNode, StateGraph<WfNodeState> stateGraph, CompileNode compileNode) throws GraphStateException {
        log.info("buildStateGraph,upstreamCompileNode:{},node:{}", upstreamCompileNode, compileNode.getId());
        String stateGraphNodeUuid = compileNode.getId();
        if (null == upstreamCompileNode) {
            addNodeToStateGraph(stateGraph, stateGraphNodeUuid);
            addEdgeToStateGraph(stateGraph, START, compileNode.getId());
        } else {
            if (compileNode instanceof GraphCompileNode graphCompileNode) {
                String stateGraphId = graphCompileNode.getId();
                CompileNode root = graphCompileNode.getRoot();
                String rootId = root.getId();
                String existSubGraphId = rootToSubGraph.get(rootId);

                if (StringUtils.isBlank(existSubGraphId)) {
                    StateGraph<WfNodeState> subgraph = new StateGraph<>(stateSerializer);
                    addNodeToStateGraph(subgraph, rootId);
                    addEdgeToStateGraph(subgraph, START, rootId);
                    for (CompileNode child : root.getNextNodes()) {
                        buildStateGraph(root, subgraph, child);
                    }
                    addEdgeToStateGraph(subgraph, graphCompileNode.getTail().getId(), END);
                    stateGraph.addNode(stateGraphId, subgraph.compile());
                    rootToSubGraph.put(rootId, stateGraphId);

                    stateGraphNodeUuid = stateGraphId;
                } else {
                    stateGraphNodeUuid = existSubGraphId;
                }
            } else {
                addNodeToStateGraph(stateGraph, stateGraphNodeUuid);
            }

//ConditionalEdge creation is handled separately
            //ConditionalEdge 的创建另外处理
            if (Boolean.FALSE.equals(upstreamCompileNode.getConditional())) {
                addEdgeToStateGraph(stateGraph, upstreamCompileNode.getId(), stateGraphNodeUuid);
            }
        }
        List<CompileNode> nextNodes = compileNode.getNextNodes();
        if (nextNodes.size() > 1) {
            boolean conditional = nextNodes.stream().noneMatch(item -> item instanceof GraphCompileNode);
            compileNode.setConditional(conditional);
            for (CompileNode nextNode : nextNodes) {
                buildStateGraph(compileNode, stateGraph, nextNode);
            }
            //节点是"条件分支"或"分类"的情况下不支持并行执行，所以直接使用条件ConditionalEdge
            if (conditional) {
                List<String> targets = nextNodes.stream().map(CompileNode::getId).toList();
                Map<String, String> mappings = new HashMap<>();
                for (String target : targets) {
                    mappings.put(target, target);
                }
                stateGraph.addConditionalEdges(
                        stateGraphNodeUuid,
                        edge_async(state -> state.data().get("next").toString()),
                        mappings
                );
            }
        } else if (nextNodes.size() == 1) {
            for (CompileNode nextNode : nextNodes) {
                buildStateGraph(compileNode, stateGraph, nextNode);
            }
        } else {
            addEdgeToStateGraph(stateGraph, stateGraphNodeUuid, END);
        }
    }

    private GraphCompileNode getOrCreateGraphCompileNode(String rootId) {
        GraphCompileNode exist = nodeToParallelBranch.get(rootId);
        if (null == exist) {
            GraphCompileNode graphCompileNode = new GraphCompileNode();
            graphCompileNode.setId("parallel_" + rootId);
            graphCompileNode.setRoot(CompileNode.builder().id(rootId).conditional(false).nextNodes(new ArrayList<>()).build());
            nodeToParallelBranch.put(rootId, graphCompileNode);
            exist = graphCompileNode;
        }
        return exist;

    }

    private List<String> getUpstreamNodeUuids(String nodeUuid) {
        return this.wfEdges.stream()
                .filter(edge -> edge.getTargetNodeUuid().equals(nodeUuid))
                .map(WorkflowEdge::getSourceNodeUuid)
                .toList();
    }

    private List<String> getDownstreamNodeUuids(String nodeUuid) {
        return this.wfEdges.stream()
                .filter(edge -> edge.getSourceNodeUuid().equals(nodeUuid))
                .map(WorkflowEdge::getTargetNodeUuid)
                .toList();
    }

//Determine if node belongs to subgraph
    //判断节点是否属于子图
    private boolean pointToParallelBranch(String nodeUuid) {
        int edgeCount = 0;
        for (WorkflowEdge edge : this.wfEdges) {
            if (edge.getSourceNodeUuid().equals(nodeUuid) && StringUtils.isBlank(edge.getSourceHandle())) {
                edgeCount = edgeCount + 1;
            }
        }
        return edgeCount > 1;
    }

    /**
     * 添加节点到状态图
     *
     * @param stateGraph
     * @param stateGraphNodeUuid
     * @throws GraphStateException
     */
    private void addNodeToStateGraph(StateGraph<WfNodeState> stateGraph, String stateGraphNodeUuid) throws GraphStateException {
        List<StateGraph<WfNodeState>> stateGraphList = stateGraphNodes.computeIfAbsent(stateGraphNodeUuid, k -> new ArrayList<>());
        boolean exist = stateGraphList.stream().anyMatch(item -> item == stateGraph);
        if (exist) {
            log.info("state graph node exist,stateGraphNodeUuid:{}", stateGraphNodeUuid);
            return;
        }
        log.info("addNodeToStateGraph,node uuid:{}", stateGraphNodeUuid);
        WorkflowNode wfNode = getNodeByUuid(stateGraphNodeUuid);
        stateGraph.addNode(stateGraphNodeUuid, node_async((state) -> runNode(wfNode, state)));
        stateGraphList.add(stateGraph);

        //记录人机交互节点
        WorkflowComponent wfComponent = components.stream().filter(item -> item.getId().equals(wfNode.getWorkflowComponentId())).findFirst().orElseThrow();
        if (HUMAN_FEEDBACK.getName().equals(wfComponent.getName())) {
            this.wfState.addInterruptNode(stateGraphNodeUuid);
        }
    }

    private void addEdgeToStateGraph(StateGraph<WfNodeState> stateGraph, String source, String target) throws GraphStateException {
        String key = source + "_" + target;
        List<StateGraph<WfNodeState>> stateGraphList = stateGraphEdges.computeIfAbsent(key, k -> new ArrayList<>());
        boolean exist = stateGraphList.stream().anyMatch(item -> item == stateGraph);
        if (exist) {
            log.info("state graph edge exist,source:{},target:{}", source, target);
            return;
        }
        log.info("addEdgeToStateGraph,source:{},target:{}", source, target);
        stateGraph.addEdge(source, target);
        stateGraphList.add(stateGraph);
    }

    private WorkflowNode getNodeByUuid(String nodeUuid) {
        return wfNodes.stream()
                .filter(item -> item.getUuid().equals(nodeUuid))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorEnum.A_WF_NODE_NOT_FOUND));
    }

    private void appendToNextNodes(CompileNode compileNode, CompileNode newNode) {
        boolean exist = compileNode.getNextNodes().stream().anyMatch(item -> item.getId().equals(newNode.getId()));
        if (!exist) {
            compileNode.getNextNodes().add(newNode);
        }

    }

    public CompiledGraph<WfNodeState> getApp() {
        return app;
    }
}
