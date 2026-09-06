package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeDto;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeNodeSummaryDto;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeResp;
import com.pppp.zhimesh.common.dto.workflow.WfRuntimeSummaryResp;
import com.pppp.zhimesh.common.dto.workflow.WorkflowResumeReq;
import com.pppp.zhimesh.common.service.WorkflowRuntimeService;
import com.pppp.zhimesh.common.workflow.WorkflowStarter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/workflow/runtime")
@Validated
public class WorkflowRuntimeController {

    @Resource
    private WorkflowRuntimeService workflowRuntimeService;

    @Resource
    private WorkflowStarter workflowStarter;

    @Operation(summary = "接收用户输入以继续执行剩余流程 | Receive User Input to Resume Workflow")
    @PostMapping(value = "/resume/{runtimeUuid}")
    public void resume(@PathVariable String runtimeUuid, @RequestBody WorkflowResumeReq resumeReq) {
        workflowStarter.resumeFlow(runtimeUuid, resumeReq.getFeedbackContent());
    }

    @GetMapping("/page")
    public Page<WfRuntimeSummaryResp> search(@RequestParam String wfUuid,
                                       @NotNull @Min(1) Integer currentPage,
                                       @NotNull @Min(10) @Max(100) Integer pageSize) {
        return workflowRuntimeService.page(wfUuid, currentPage, pageSize);
    }

    @GetMapping("/{runtimeUuid}")
    public WfRuntimeResp detail(@PathVariable String runtimeUuid) {
        return workflowRuntimeService.detail(runtimeUuid);
    }

    @GetMapping("/nodes/{runtimeUuid}")
    public List<WfRuntimeNodeSummaryDto> listByRuntimeId(@PathVariable String runtimeUuid) {
        return workflowRuntimeService.listNodeSummariesByRuntimeUuid(runtimeUuid);
    }

    @GetMapping("/nodes/{runtimeUuid}/{runtimeNodeUuid}")
    public WfRuntimeNodeDto nodeDetail(@PathVariable String runtimeUuid,
                                       @PathVariable String runtimeNodeUuid) {
        return workflowRuntimeService.nodeDetail(runtimeUuid, runtimeNodeUuid);
    }

    @PostMapping("/cancel/{runtimeUuid}")
    public WfRuntimeSummaryResp cancel(@PathVariable String runtimeUuid) {
        return workflowStarter.cancelFlow(runtimeUuid);
    }

    @PostMapping("/clear")
    public boolean clear(@RequestParam String wfUuid) {
        return workflowRuntimeService.deleteAll(wfUuid);
    }

    @PostMapping("/del/{wfRuntimeUuid}")
    public boolean delete(@PathVariable String wfRuntimeUuid) {
        return workflowRuntimeService.softDelete(wfRuntimeUuid);
    }
}
