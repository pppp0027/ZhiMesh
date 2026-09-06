package com.pppp.zhimesh.common.dto.workflow;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.pppp.zhimesh.common.workflow.NodeExecutionMetrics;
import lombok.Data;

import java.time.LocalDateTime;

/** Node timeline row. Input/output JSON is loaded only when the user expands a node. */
@Data
public class WfRuntimeNodeSummaryDto {
    private Long id;
    private String uuid;
    private Long workflowRuntimeId;
    private Long nodeId;
    private Integer status;
    private String statusRemark;
    private Integer duration;
    private NodeExecutionMetrics metadata;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
