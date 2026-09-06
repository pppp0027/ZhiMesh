package com.pppp.zhimesh.common.dto.workflow;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/** Lightweight workflow run row used by history pages. Large JSON input/output is detail-only. */
@Data
public class WfRuntimeSummaryResp {
    private Long id;
    private String uuid;
    private Long workflowId;
    private Integer status;
    private String statusRemark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    private Long inputTokens;
    private Long outputTokens;
    private Long duration;
}
