package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.workflow.WfRuntimeMetricsSummary;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.WorkflowRuntime;
import com.pppp.zhimesh.common.entity.WorkflowRuntimeNode;
import com.pppp.zhimesh.common.mapper.WorkflowRunMapper;
import com.pppp.zhimesh.common.mapper.WorkflowRuntimeNodeMapper;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.NODE_PROCESS_STATUS_DOING;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_DOING;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_SUCCESS;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_CANCELLED;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.NODE_PROCESS_STATUS_CANCELLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRuntimeStatusTest {

    @Test
    void emptyWorkflowInputStillTransitionsToDoing() {
        WorkflowRuntimeService service = new WorkflowRuntimeService();
        WorkflowRunMapper mapper = mock(WorkflowRunMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(7L)).thenReturn(new WorkflowRuntime());

        WfState state = new WfState(new User(), List.of(), "runtime");
        service.updateInput(7L, state);

        ArgumentCaptor<WorkflowRuntime> captor = ArgumentCaptor.forClass(WorkflowRuntime.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(WORKFLOW_PROCESS_STATUS_DOING, captor.getValue().getStatus());
        assertEquals(WORKFLOW_PROCESS_STATUS_DOING, state.getProcessStatus());
        assertNull(captor.getValue().getInput());
    }

    @Test
    void resumingWaitingWorkflowTransitionsBackToDoing() {
        WorkflowRuntimeService service = new WorkflowRuntimeService();
        WorkflowRunMapper mapper = mock(WorkflowRunMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.update(any(WorkflowRuntime.class), any())).thenReturn(1);

        assertEquals(true, service.claimWaitingInput(10L));

        ArgumentCaptor<WorkflowRuntime> captor = ArgumentCaptor.forClass(WorkflowRuntime.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(WORKFLOW_PROCESS_STATUS_DOING, captor.getValue().getStatus());
    }

    @Test
    void secondResumeClaimIsRejected() {
        WorkflowRuntimeService service = new WorkflowRuntimeService();
        WorkflowRunMapper mapper = mock(WorkflowRunMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.update(any(WorkflowRuntime.class), any())).thenReturn(0);

        assertEquals(false, service.claimWaitingInput(10L));
    }

    @Test
    void terminalSuccessCannotPersistReadyStatus() {
        WorkflowRuntimeService service = new WorkflowRuntimeService();
        WorkflowRunMapper mapper = mock(WorkflowRunMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.update(any(WorkflowRuntime.class), any())).thenReturn(1);

        WfState state = new WfState(new User(), List.of(), "runtime");
        service.completeSuccess(8L, state, System.currentTimeMillis(),
                new WfRuntimeMetricsSummary(0, 0, 0));

        ArgumentCaptor<WorkflowRuntime> captor = ArgumentCaptor.forClass(WorkflowRuntime.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(WORKFLOW_PROCESS_STATUS_SUCCESS, captor.getValue().getStatus());
        assertEquals(WORKFLOW_PROCESS_STATUS_SUCCESS, state.getProcessStatus());
    }

    @Test
    void emptyNodeInputStillTransitionsToDoing() {
        WorkflowRuntimeNodeService service = new WorkflowRuntimeNodeService();
        WorkflowRuntimeNodeMapper mapper = mock(WorkflowRuntimeNodeMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        WfNodeState state = new WfNodeState();
        state.setProcessStatus(NODE_PROCESS_STATUS_DOING);
        service.updateInput(9L, state);

        ArgumentCaptor<WorkflowRuntimeNode> captor = ArgumentCaptor.forClass(WorkflowRuntimeNode.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(NODE_PROCESS_STATUS_DOING, captor.getValue().getStatus());
        assertNull(captor.getValue().getInput());
    }

    @Test
    void cancellationUsesDedicatedTerminalStatus() {
        WorkflowRuntimeService service = new WorkflowRuntimeService();
        WorkflowRunMapper mapper = mock(WorkflowRunMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.update(any(WorkflowRuntime.class), any())).thenReturn(1);

        service.completeCancellation(12L, System.currentTimeMillis(), new WfRuntimeMetricsSummary(3, 4, 0));

        ArgumentCaptor<WorkflowRuntime> captor = ArgumentCaptor.forClass(WorkflowRuntime.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(WORKFLOW_PROCESS_STATUS_CANCELLED, captor.getValue().getStatus());
    }

    @Test
    void cancellingRunMarksOnlyOpenNodesAsCancelled() {
        WorkflowRuntimeNodeService service = new WorkflowRuntimeNodeService();
        WorkflowRuntimeNodeMapper mapper = mock(WorkflowRuntimeNodeMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        service.cancelOpenNodes(15L);

        ArgumentCaptor<WorkflowRuntimeNode> captor = ArgumentCaptor.forClass(WorkflowRuntimeNode.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(NODE_PROCESS_STATUS_CANCELLED, captor.getValue().getStatus());
    }
}
