package com.pppp.zhimesh.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TeamInfoResp {
    private Long id;
    private String uuid;
    private String name;
    private String remark;
    /** 当前用户在该团队中的角色 OWNER/CONTRIBUTOR/READER。 */
    private String myRole;
    private Integer memberCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
