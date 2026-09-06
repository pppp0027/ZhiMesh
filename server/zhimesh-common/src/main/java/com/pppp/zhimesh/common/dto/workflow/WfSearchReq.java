package com.pppp.zhimesh.common.dto.workflow;

import lombok.Data;

@Data
public class WfSearchReq {
    private String title;
    private Boolean isEnable;
    private Boolean isPublic;
}
