package com.pppp.zhimesh.common.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeSummaryResp;
import com.pppp.zhimesh.common.entity.*;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.service.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.SSE_TIMEOUT;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_WAITING_INPUT;

@Slf4j
@Component
public class WorkflowStarter {

    @Lazy
    @Resource
    private WorkflowStarter self;

    @Resource
    private WorkflowService workflowService;

    @Resource
    private WorkflowNodeService workflowNodeService;

    @Resource
    private WorkflowEdgeService workflowEdgeService;

    @Resource
    private WorkflowComponentService workflowComponentService;

    @Resource
    private WorkflowRuntimeService workflowRuntimeService;

    @Resource
    private WorkflowRuntimeNodeService workflowRuntimeNodeService;

    @Resource
    private SseManager sseManager;

    @Resource
    private WorkflowRuntimeExecutionRegistry workflowRuntimeExecutionRegistry;


    public SseEmitter streaming(User user, String workflowUuid, List<ObjectNode> userInputs) {
        String sseUuid = com.pppp.zhimesh.common.util.UuidUtil.createShort();
        SseEmitter sseEmitter = new SseEmitter(SSE_TIMEOUT);
        if (!sseManager.checkOrComplete(user, sseUuid, sseEmitter)) {
            return sseEmitter;
        }
        // 工作流的 START 事件由异步线程在 WorkflowEngine.run 中发送（需携带 wfRuntimeResp 数据），
        // 所以这里不能像聊天那样直接调 startSse；只注册 emitter，供异步线程通过 sseUuid 取回使用。
        // <p>
        // The workflow START event is sent from the async thread inside WorkflowEngine.run (it
        // carries the wfRuntimeResp payload), so unlike chat we cannot call startSse here; we only
        // register the emitter so the async thread can look it up by sseUuid.
        sseManager.register(sseUuid, sseEmitter, user.getId());
        sseManager.registerEventStreamListener(user, sseUuid);
        Workflow workflow = workflowService.getByUuid(workflowUuid);
        if (null == workflow) {
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage(A_WF_NOT_FOUND.getInfo()));
            return sseEmitter;
        } else if (Boolean.FALSE.equals(workflow.getIsEnable())) {
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage(A_WF_DISABLED.getInfo()));
            return sseEmitter;
        }
        try {
            self.asyncRun(user, workflow, userInputs, sseUuid);
        } catch (TaskRejectedException exception) {
            log.warn("Workflow task rejected, userId:{}, workflowUuid:{}, sseUuid:{}",
                    user.getId(), workflow.getUuid(), sseUuid);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage("A_SYSTEM_BUSY"));
        }
        return sseEmitter;
    }

    @Async("workflowExecutor")
    public void asyncRun(User user, Workflow workflow, List<ObjectNode> userInputs, String sseUuid) {
        log.info("WorkflowEngine run,userId:{},workflowUuid:{},userInputs:{}", user.getId(), workflow.getUuid(), userInputs);
        try {
            List<WorkflowComponent> components = workflowComponentService.getAllEnable();
            List<WorkflowNode> nodes = workflowNodeService.lambdaQuery()
                    .eq(WorkflowNode::getWorkflowId, workflow.getId())
                    
                    .list();
            List<WorkflowEdge> edges = workflowEdgeService.lambdaQuery()
                    .eq(WorkflowEdge::getWorkflowId, workflow.getId())
                    
                    .list();
            WorkflowEngine workflowEngine = new WorkflowEngine(workflow,
                    sseManager,
                    components,
                    nodes,
                    edges,
                    workflowRuntimeService,
                    workflowRuntimeNodeService,
                    workflowRuntimeExecutionRegistry);
            workflowEngine.run(user, userInputs, sseUuid);
        } catch (Throwable e) {
            log.error("asyncRun execution exception, workflowUuid:{}", workflow.getUuid(), e);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, "Workflow execution error:" + e.getMessage());
        }
    }

    /**
     * Run a workflow in blocking mode, wait for completion and return the result.
     */
    public Map<String, Object> blocking(User user, String workflowUuid, List<ObjectNode> userInputs) {
        Workflow workflow = workflowService.getByUuid(workflowUuid);
        if (null == workflow) {
            throw new BaseException(A_WF_NOT_FOUND);
        } else if (Boolean.FALSE.equals(workflow.getIsEnable())) {
            throw new BaseException(A_WF_DISABLED);
        }

        List<WorkflowComponent> components = workflowComponentService.getAllEnable();
        List<WorkflowNode> nodes = workflowNodeService.lambdaQuery()
                .eq(WorkflowNode::getWorkflowId, workflow.getId())
                
                .list();
        List<WorkflowEdge> edges = workflowEdgeService.lambdaQuery()
                .eq(WorkflowEdge::getWorkflowId, workflow.getId())
                
                .list();

        WorkflowEngine workflowEngine = new WorkflowEngine(workflow,
                sseManager,
                components,
                nodes,
                edges,
                workflowRuntimeService,
                workflowRuntimeNodeService,
                workflowRuntimeExecutionRegistry);
        return workflowEngine.blockingRun(user, userInputs);
    }

    public void resumeFlow(String runtimeUuid, String userInput) {
        WorkflowEngine workflowEngine = InterruptedFlow.take(runtimeUuid);
        if (null == workflowEngine) {
            log.error("Workflow resume execution failed, runtime:{}", runtimeUuid);
            throw new BaseException(A_WF_RESUME_FAIL);
        }

        WorkflowRuntime runtime = workflowRuntimeService.getByUuid(runtimeUuid);
        if (runtime == null || !Integer.valueOf(WORKFLOW_PROCESS_STATUS_WAITING_INPUT).equals(runtime.getStatus())) {
            throw new BaseException(A_WF_RESUME_FAIL);
        }
        try {
            self.asyncResumeFlow(runtimeUuid, workflowEngine, userInput);
        } catch (TaskRejectedException exception) {
            log.warn("Workflow resume task rejected, runtimeUuid:{}", runtimeUuid);
            WorkflowRuntime current = workflowRuntimeService.getByUuid(runtimeUuid);
            if (current != null && Integer.valueOf(WORKFLOW_PROCESS_STATUS_WAITING_INPUT).equals(current.getStatus())) {
                InterruptedFlow.put(runtimeUuid, workflowEngine);
            }
            throw new BaseException(A_SYSTEM_BUSY);
        }
    }

    /** Request cooperative cancellation and immediately finalize a flow paused for user input. */
    public WfRuntimeSummaryResp cancelFlow(String runtimeUuid) {
        WorkflowRuntime runtime = workflowRuntimeService.requestCancellation(runtimeUuid);
        if (!Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED).equals(runtime.getStatus())) {
            return workflowRuntimeService.toSummary(runtime);
        }

        workflowRuntimeExecutionRegistry.requestCancellation(runtime.getId());
        WorkflowEngine interruptedEngine = InterruptedFlow.get(runtimeUuid);
        if (interruptedEngine != null) {
            interruptedEngine.cancel();
            WorkflowRuntime cancelled = workflowRuntimeService.getByUuid(runtimeUuid);
            return workflowRuntimeService.toSummary(cancelled);
        }
        return workflowRuntimeService.toSummary(runtime);
    }

    @Async("workflowExecutor")
    public void asyncResumeFlow(String runtimeUuid, WorkflowEngine workflowEngine, String userInput) {
        try {
            workflowEngine.resume(userInput);
        } catch (Throwable e) {
            log.error("resumeFlow execution exception, runtimeUuid:{}", runtimeUuid, e);
        }
    }

}
