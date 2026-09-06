package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeMetricsSummary;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeDto;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeSummaryDto;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeResp;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeSummaryResp;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.Workflow;
import com.pppp.zhimesh.common.entity.WorkflowRuntime;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.WorkflowRunMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.NumberUtil;
import com.pppp.zhimesh.common.util.PrivilegeUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.*;

@Slf4j
@Service
public class WorkflowRuntimeService extends ServiceImpl<WorkflowRunMapper, WorkflowRuntime> {

    @Resource
    private WorkflowService workflowService;

    @Resource
    private WorkflowRuntimeNodeService workflowRuntimeNodeService;

    public WfRuntimeResp create(User user, Long workflowId) {
        WorkflowRuntime one = new WorkflowRuntime();
        one.setUuid(UuidUtil.createShort());
        one.setUserId(user.getId());
        one.setWorkflowId(workflowId);
        one.setStatus(WORKFLOW_PROCESS_STATUS_READY);
        baseMapper.insert(one);

        one = baseMapper.selectById(one.getId());
        return changeToDTO(one);
    }

    public void updateInput(long id, WfState wfState) {
        WorkflowRuntime node = baseMapper.selectById(id);
        if (null == node) {
            log.error("Workflow runtime not found, id:{}", id);
            return;
        }
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        if (!CollectionUtils.isEmpty(wfState.getInput())) {
            ObjectNode ob = JsonUtil.createObjectNode();
            for (NodeIOData data : wfState.getInput()) {
                ob.set(data.getName(), JsonUtil.classToJsonNode(data.getContent()));
            }
            updateOne.setInput(ob);
        }
        wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_DOING);
        updateOne.setStatus(WORKFLOW_PROCESS_STATUS_DOING);
        baseMapper.update(updateOne, Wrappers.<WorkflowRuntime>lambdaUpdate()
                .eq(WorkflowRuntime::getId, id)
                .eq(WorkflowRuntime::getStatus, WORKFLOW_PROCESS_STATUS_READY));
    }

    /** Claim a persisted WAITING_INPUT run for exactly one resume request. */
    public boolean claimWaitingInput(long id) {
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setStatus(WORKFLOW_PROCESS_STATUS_DOING);
        updateOne.setStatusRemark("");
        return baseMapper.update(updateOne, Wrappers.<WorkflowRuntime>lambdaUpdate()
                .eq(WorkflowRuntime::getId, id)
                .eq(WorkflowRuntime::getStatus, WORKFLOW_PROCESS_STATUS_WAITING_INPUT)) > 0;
    }

    /** Read the persisted cancellation state from an async worker without relying on ThreadContext. */
    public boolean isCancellationRequested(long id) {
        WorkflowRuntime runtime = baseMapper.selectById(id);
        return runtime != null
                && (Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED).equals(runtime.getStatus())
                || Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCELLED).equals(runtime.getStatus()));
    }

    /**
     * Persist the run output and a terminal metrics snapshot. Returns the same WorkflowRuntime
     * instance with all written fields populated (id, output, status, snapshot columns) so the
     * caller can push it via SSE without an extra round-trip; null only if no row matched the id.
     *
     * @param runStartedMillis epoch millis when the run started, used for wall-clock duration
     * @param tokenSummary     pre-computed token totals from the caller (engine has them in memory)
     */
    public WorkflowRuntime updateOutput(long id, WfState wfState, long runStartedMillis, WfRuntimeMetricsSummary tokenSummary) {
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        ObjectNode ob = JsonUtil.createObjectNode();
        for (NodeIOData data : wfState.getOutput()) {
            ob.set(data.getName(), JsonUtil.classToJsonNode(data.getContent()));
        }
        updateOne.setOutput(ob);
        updateOne.setStatus(wfState.getProcessStatus());
        applyMetricsSnapshot(updateOne, runStartedMillis, tokenSummary);
        return updateOpenRuntime(updateOne);
    }

    /** Terminal success entry point; callers cannot accidentally persist READY/DOING as completed. */
    public WorkflowRuntime completeSuccess(long id, WfState wfState, long runStartedMillis,
                                           WfRuntimeMetricsSummary tokenSummary) {
        wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_SUCCESS);
        return updateOutput(id, wfState, runStartedMillis, tokenSummary);
    }

    /**
     * Update status and persist a terminal metrics snapshot. Returns the same WorkflowRuntime
     * instance carrying status / status_remark / snapshot columns so the caller can push it via
     * SSE without an extra round-trip; null only if no row matched the id.
     *
     * @param runStartedMillis epoch millis when the run started, used for wall-clock duration
     * @param tokenSummary     pre-computed token totals from the caller
     */
    public WorkflowRuntime updateStatus(long id, int processStatus, String statusRemark, long runStartedMillis, WfRuntimeMetricsSummary tokenSummary) {
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        updateOne.setStatus(processStatus);
        updateOne.setStatusRemark(StringUtils.substring(statusRemark, 0, 250));
        applyMetricsSnapshot(updateOne, runStartedMillis, tokenSummary);
        return updateOpenRuntime(updateOne);
    }

    public WorkflowRuntime getByUuid(String uuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(!ThreadContext.getCurrentUser().getIsAdmin(), WorkflowRuntime::getUserId, ThreadContext.getCurrentUserId())
                .eq(WorkflowRuntime::getUuid, uuid)
                
                .last("limit 1")
                .one();
    }

    public Page<WfRuntimeSummaryResp> page(String wfUuid, Integer currentPage, Integer pageSize) {
        Workflow workflow = workflowService.getOrThrow(wfUuid);
        User user = ThreadContext.getCurrentUser();
        Page<WorkflowRuntime> page = ChainWrappers.lambdaQueryChain(baseMapper)
                .select(WorkflowRuntime::getId,
                        WorkflowRuntime::getUuid,
                        WorkflowRuntime::getWorkflowId,
                        WorkflowRuntime::getStatus,
                        WorkflowRuntime::getStatusRemark,
                        WorkflowRuntime::getInputTokens,
                        WorkflowRuntime::getOutputTokens,
                        WorkflowRuntime::getDuration,
                        WorkflowRuntime::getCreateTime,
                        WorkflowRuntime::getUpdateTime)
                .eq(WorkflowRuntime::getWorkflowId, workflow.getId())
                .eq(!user.getIsAdmin(), WorkflowRuntime::getUserId, user.getId())
                .orderByDesc(WorkflowRuntime::getUpdateTime)
                .orderByDesc(WorkflowRuntime::getId)
                .page(new Page<>(currentPage, pageSize));
        Page<WfRuntimeSummaryResp> result = new Page<>();
        MPPageUtil.convertToPage(page, result, WfRuntimeSummaryResp.class, (source, target) -> {
            fillMetricsFromEntity(source, target);
            return target;
        });
        return result;
    }

    public WfRuntimeResp detail(String runtimeUuid) {
        WorkflowRuntime runtime = PrivilegeUtil.checkAndGetByUuid(runtimeUuid, this.query(), ErrorEnum.A_WF_RUNTIME_NOT_FOUND);
        return changeToDTO(runtime);
    }

    public List<WfRuntimeNodeSummaryDto> listNodeSummariesByRuntimeUuid(String runtimeUuid) {
        WorkflowRuntime runtime = PrivilegeUtil.checkAndGetByUuid(runtimeUuid, this.query(), ErrorEnum.A_WF_RUNTIME_NOT_FOUND);
        return workflowRuntimeNodeService.listSummariesByWfRuntimeId(runtime.getId());
    }

    public WfRuntimeNodeDto nodeDetail(String runtimeUuid, String runtimeNodeUuid) {
        WorkflowRuntime runtime = PrivilegeUtil.checkAndGetByUuid(runtimeUuid, this.query(), ErrorEnum.A_WF_RUNTIME_NOT_FOUND);
        return workflowRuntimeNodeService.getDetail(runtime.getId(), runtimeNodeUuid);
    }

    /** Move an open run into CANCEL_REQUESTED without racing a concurrent terminal write. */
    public WorkflowRuntime requestCancellation(String runtimeUuid) {
        WorkflowRuntime runtime = PrivilegeUtil.checkAndGetByUuid(runtimeUuid, this.query(), ErrorEnum.A_WF_RUNTIME_NOT_FOUND);
        if (!isOpenStatus(runtime.getStatus())) {
            return runtime;
        }
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(runtime.getId());
        updateOne.setStatus(WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED);
        updateOne.setStatusRemark("正在取消工作流");
        baseMapper.update(updateOne, Wrappers.<WorkflowRuntime>lambdaUpdate()
                .eq(WorkflowRuntime::getId, runtime.getId())
                .in(WorkflowRuntime::getStatus,
                        WORKFLOW_PROCESS_STATUS_READY,
                        WORKFLOW_PROCESS_STATUS_DOING,
                        WORKFLOW_PROCESS_STATUS_WAITING_INPUT));
        return baseMapper.selectById(runtime.getId());
    }

    public WorkflowRuntime completeCancellation(long id, long runStartedMillis,
                                                WfRuntimeMetricsSummary tokenSummary) {
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        updateOne.setStatus(WORKFLOW_PROCESS_STATUS_CANCELLED);
        updateOne.setStatusRemark("工作流已取消");
        applyMetricsSnapshot(updateOne, runStartedMillis, tokenSummary);
        int updated = baseMapper.update(updateOne, Wrappers.<WorkflowRuntime>lambdaUpdate()
                .eq(WorkflowRuntime::getId, id)
                .in(WorkflowRuntime::getStatus,
                        WORKFLOW_PROCESS_STATUS_READY,
                        WORKFLOW_PROCESS_STATUS_DOING,
                        WORKFLOW_PROCESS_STATUS_WAITING_INPUT,
                        WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED));
        return updated > 0 ? updateOne : baseMapper.selectById(id);
    }

    public WfRuntimeSummaryResp toSummary(WorkflowRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        WfRuntimeSummaryResp result = new WfRuntimeSummaryResp();
        BeanUtils.copyProperties(runtime, result);
        fillMetricsFromEntity(runtime, result);
        return result;
    }

    @Transactional
    public boolean deleteAll(String wfUuid) {
        Workflow workflow = workflowService.getOrThrow(wfUuid);
        User user = ThreadContext.getCurrentUser();
        List<WorkflowRuntime> runtimes = this.lambdaQuery()
                .eq(WorkflowRuntime::getWorkflowId, workflow.getId())
                .eq(!user.getIsAdmin(), WorkflowRuntime::getUserId, user.getId())
                .in(WorkflowRuntime::getStatus,
                        WORKFLOW_PROCESS_STATUS_SUCCESS,
                        WORKFLOW_PROCESS_STATUS_FAIL,
                        WORKFLOW_PROCESS_STATUS_CANCELLED)
                .list();
        if (runtimes.isEmpty()) {
            return false;
        }
        List<Long> runtimeIds = runtimes.stream().map(WorkflowRuntime::getId).toList();
        workflowRuntimeNodeService.deleteByRuntimeIds(runtimeIds);
        return baseMapper.delete(new LambdaQueryWrapper<WorkflowRuntime>()
                .eq(WorkflowRuntime::getWorkflowId, workflow.getId())
                .eq(!user.getIsAdmin(), WorkflowRuntime::getUserId, user.getId())
                .in(WorkflowRuntime::getStatus,
                        WORKFLOW_PROCESS_STATUS_SUCCESS,
                        WORKFLOW_PROCESS_STATUS_FAIL,
                        WORKFLOW_PROCESS_STATUS_CANCELLED)) > 0;
    }

    private WfRuntimeResp changeToDTO(WorkflowRuntime runtime) {
        WfRuntimeResp result = new WfRuntimeResp();
        BeanUtils.copyProperties(runtime, result);
        fillInputOutput(result);
        fillMetricsFromEntity(runtime, result);
        return result;
    }

    private void fillInputOutput(WfRuntimeResp target) {
        if (null == target.getInput()) {
            target.setInput(JsonUtil.createObjectNode());
        }
        if (null == target.getOutput()) {
            target.setOutput(JsonUtil.createObjectNode());
        }
    }

    /**
     * Apply the pre-computed token summary and wall-clock duration onto the update DTO.
     */
    private void applyMetricsSnapshot(WorkflowRuntime updateOne, long runStartedMillis, WfRuntimeMetricsSummary summary) {
        updateOne.setInputTokens(NumberUtil.saturatedCastToInt(summary.inputTokens()));
        updateOne.setOutputTokens(NumberUtil.saturatedCastToInt(summary.outputTokens()));
        long wallClockMs = Math.max(0, System.currentTimeMillis() - runStartedMillis);
        updateOne.setDuration(NumberUtil.saturatedCastToInt(wallClockMs));
    }

    /**
     * Terminal writes may only close an open run. This prevents a late network callback from
     * overwriting SUCCESS with FAIL (or vice versa) after the state machine already converged.
     */
    private WorkflowRuntime updateOpenRuntime(WorkflowRuntime updateOne) {
        int updated = baseMapper.update(updateOne, Wrappers.<WorkflowRuntime>lambdaUpdate()
                .eq(WorkflowRuntime::getId, updateOne.getId())
                .in(WorkflowRuntime::getStatus,
                        WORKFLOW_PROCESS_STATUS_READY,
                        WORKFLOW_PROCESS_STATUS_DOING,
                        WORKFLOW_PROCESS_STATUS_WAITING_INPUT));
        if (updated > 0) {
            return updateOne;
        }
        WorkflowRuntime existing = baseMapper.selectById(updateOne.getId());
        if (existing == null) {
            log.error("Workflow runtime not found, id:{}", updateOne.getId());
        }
        return existing;
    }

    /**
     * Copy the persisted flat snapshot onto the resp DTO. Null columns leave the resp fields null
     * so the UI hides them (e.g. still DOING, or legacy row).
     */
    private void fillMetricsFromEntity(WorkflowRuntime source, WfRuntimeResp target) {
        target.setInputTokens(source.getInputTokens() == null ? null : source.getInputTokens().longValue());
        target.setOutputTokens(source.getOutputTokens() == null ? null : source.getOutputTokens().longValue());
        target.setDuration(source.getDuration() == null ? null : source.getDuration().longValue());
    }

    private void fillMetricsFromEntity(WorkflowRuntime source, WfRuntimeSummaryResp target) {
        target.setInputTokens(source.getInputTokens() == null ? null : source.getInputTokens().longValue());
        target.setOutputTokens(source.getOutputTokens() == null ? null : source.getOutputTokens().longValue());
        target.setDuration(source.getDuration() == null ? null : source.getDuration().longValue());
    }

    @Transactional
    public boolean softDelete(String uuid) {
        WorkflowRuntime workflowRuntime = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), ErrorEnum.A_WF_RUNTIME_NOT_FOUND);
        if (isOpenStatus(workflowRuntime.getStatus())
                || Integer.valueOf(WORKFLOW_PROCESS_STATUS_CANCEL_REQUESTED).equals(workflowRuntime.getStatus())) {
            throw new BaseException(ErrorEnum.A_WF_RUNTIME_ACTIVE);
        }
        workflowRuntimeNodeService.deleteByRuntimeId(workflowRuntime.getId());
        return baseMapper.deleteById(workflowRuntime.getId()) > 0;
    }

    private boolean isOpenStatus(Integer status) {
        return Integer.valueOf(WORKFLOW_PROCESS_STATUS_READY).equals(status)
                || Integer.valueOf(WORKFLOW_PROCESS_STATUS_DOING).equals(status)
                || Integer.valueOf(WORKFLOW_PROCESS_STATUS_WAITING_INPUT).equals(status);
    }
}
