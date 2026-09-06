package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeDto;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeSummaryDto;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.WorkflowRuntime;
import com.pppp.zhimesh.common.entity.WorkflowRuntimeNode;
import com.pppp.zhimesh.common.mapper.WorkflowRuntimeNodeMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.PrivilegeUtil;
import com.pppp.zhimesh.common.util.NumberUtil;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Collection;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.NODE_PROCESS_STATUS_CANCELLED;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.NODE_PROCESS_STATUS_DOING;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.NODE_PROCESS_STATUS_READY;

@Slf4j
@Service
public class WorkflowRuntimeNodeService extends ServiceImpl<WorkflowRuntimeNodeMapper, WorkflowRuntimeNode> {


    public List<WfRuntimeNodeDto> listByWfRuntimeId(long runtimeId) {
        List<WorkflowRuntimeNode> workflowNodeList = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(!ThreadContext.getCurrentUser().getIsAdmin(), WorkflowRuntimeNode::getUserId, ThreadContext.getCurrentUser().getId())
                .eq(WorkflowRuntimeNode::getWorkflowRuntimeId, runtimeId)
                
                .orderByAsc(WorkflowRuntimeNode::getId)
                .list();
        List<WfRuntimeNodeDto> result = MPPageUtil.convertToList(workflowNodeList, WfRuntimeNodeDto.class);
        for (WfRuntimeNodeDto dto : result) {
            fillInputOutput(dto);
        }
        return result;
    }

    public List<WfRuntimeNodeSummaryDto> listSummariesByWfRuntimeId(long runtimeId) {
        List<WorkflowRuntimeNode> workflowNodeList = ChainWrappers.lambdaQueryChain(baseMapper)
                .select(WorkflowRuntimeNode::getId,
                        WorkflowRuntimeNode::getUuid,
                        WorkflowRuntimeNode::getWorkflowRuntimeId,
                        WorkflowRuntimeNode::getNodeId,
                        WorkflowRuntimeNode::getStatus,
                        WorkflowRuntimeNode::getStatusRemark,
                        WorkflowRuntimeNode::getDuration,
                        WorkflowRuntimeNode::getMetadata,
                        WorkflowRuntimeNode::getCreateTime,
                        WorkflowRuntimeNode::getUpdateTime)
                .eq(!ThreadContext.getCurrentUser().getIsAdmin(), WorkflowRuntimeNode::getUserId,
                        ThreadContext.getCurrentUser().getId())
                .eq(WorkflowRuntimeNode::getWorkflowRuntimeId, runtimeId)
                .orderByAsc(WorkflowRuntimeNode::getId)
                .list();
        return MPPageUtil.convertToList(workflowNodeList, WfRuntimeNodeSummaryDto.class);
    }

    public WfRuntimeNodeDto getDetail(long runtimeId, String runtimeNodeUuid) {
        WorkflowRuntimeNode runtimeNode = PrivilegeUtil.checkAndGetByUuid(runtimeNodeUuid,
                this.query().eq("workflow_runtime_id", runtimeId),
                com.pppp.zhimesh.common.enums.ErrorEnum.A_WF_NODE_NOT_FOUND);
        WfRuntimeNodeDto result = MPPageUtil.convertTo(runtimeNode, WfRuntimeNodeDto.class);
        fillInputOutput(result);
        return result;
    }

    public WfRuntimeNodeDto createByState(User user, long wfNodeId, long wfRuntimeId, WfNodeState state) {
        WorkflowRuntimeNode runtimeNode = new WorkflowRuntimeNode();
        runtimeNode.setUuid(state.getUuid());
        runtimeNode.setWorkflowRuntimeId(wfRuntimeId);
        runtimeNode.setStatus(state.getProcessStatus());
        runtimeNode.setUserId(user.getId());
        runtimeNode.setNodeId(wfNodeId);
        baseMapper.insert(runtimeNode);
        runtimeNode = baseMapper.selectById(runtimeNode.getId());

        WfRuntimeNodeDto result = new WfRuntimeNodeDto();
        BeanUtils.copyProperties(runtimeNode, result);
        fillInputOutput(result);
        return result;
    }

    public void updateInput(Long id, WfNodeState state) {
        WorkflowRuntimeNode updateOne = new WorkflowRuntimeNode();
        updateOne.setId(id);
        if (!CollectionUtils.isEmpty(state.getInputs())) {
            ObjectNode ob = JsonUtil.createObjectNode();
            for (NodeIOData data : state.getInputs()) {
                ob.set(data.getName(), JsonUtil.classToJsonNode(data.getContent()));
            }
            updateOne.setInput(ob);
        }
        updateOne.setStatus(state.getProcessStatus());
        updateOne.setStatusRemark(state.getProcessStatusRemark());
        updateOpenNode(updateOne);
    }

    public void updateOutput(Long id, WfNodeState state) {
        WorkflowRuntimeNode updateOne = new WorkflowRuntimeNode();
        updateOne.setId(id);
        if (!CollectionUtils.isEmpty(state.getOutputs())) {
            ObjectNode ob = JsonUtil.createObjectNode();
            for (NodeIOData data : state.getOutputs()) {
                ob.set(data.getName(), JsonUtil.classToJsonNode(data.getContent()));
            }
            updateOne.setOutput(ob);
        }
        updateOne.setStatus(state.getProcessStatus());
        updateOne.setStatusRemark(state.getProcessStatusRemark());
        //更新可观测指标 | Update observability metrics: persist metadata whenever available,
        // regardless of whether durationMs is > 0, so token / model info is never silently dropped
        // for sub-millisecond or cache-hit edge cases.
        if (state.getMetrics() != null) {
            updateOne.setDuration(NumberUtil.saturatedCastToInt(state.getMetrics().getDurationMs()));
            updateOne.setMetadata(state.getMetrics());
        }
        updateOpenNode(updateOne);
    }

    /**
     * Persist node progress only while the node is still open. A cancellation can race with a
     * provider callback; late input/output callbacks must never turn CANCELLED back into SUCCESS
     * or FAIL.
     */
    private void updateOpenNode(WorkflowRuntimeNode updateOne) {
        int updated = baseMapper.update(updateOne, new LambdaQueryWrapper<WorkflowRuntimeNode>()
                .eq(WorkflowRuntimeNode::getId, updateOne.getId())
                .in(WorkflowRuntimeNode::getStatus, NODE_PROCESS_STATUS_READY, NODE_PROCESS_STATUS_DOING));
        if (updated == 0) {
            log.debug("Skip late workflow node update for closed node, id:{}", updateOne.getId());
        }
    }

    /** Remove node execution details together with their parent runtime. */
    public int deleteByRuntimeId(Long runtimeId) {
        if (runtimeId == null) {
            return 0;
        }
        return baseMapper.delete(new LambdaQueryWrapper<WorkflowRuntimeNode>()
                .eq(WorkflowRuntimeNode::getWorkflowRuntimeId, runtimeId));
    }

    public int deleteByRuntimeIds(Collection<Long> runtimeIds) {
        if (CollectionUtils.isEmpty(runtimeIds)) {
            return 0;
        }
        return baseMapper.delete(new LambdaQueryWrapper<WorkflowRuntimeNode>()
                .in(WorkflowRuntimeNode::getWorkflowRuntimeId, runtimeIds));
    }

    public int cancelOpenNodes(Long runtimeId) {
        if (runtimeId == null) {
            return 0;
        }
        WorkflowRuntimeNode updateOne = new WorkflowRuntimeNode();
        updateOne.setStatus(NODE_PROCESS_STATUS_CANCELLED);
        updateOne.setStatusRemark("工作流已取消");
        return baseMapper.update(updateOne, new LambdaQueryWrapper<WorkflowRuntimeNode>()
                .eq(WorkflowRuntimeNode::getWorkflowRuntimeId, runtimeId)
                .in(WorkflowRuntimeNode::getStatus, NODE_PROCESS_STATUS_READY, NODE_PROCESS_STATUS_DOING));
    }

    private void fillInputOutput(WfRuntimeNodeDto dto) {
        if (null == dto.getInput()) {
            dto.setInput(JsonUtil.createObjectNode());
        }
        if (null == dto.getOutput()) {
            dto.setOutput(JsonUtil.createObjectNode());
        }
    }

}
