package com.pppp.zhimesh.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TeamMemberResp {
    private Long userId;
    private String uuid;
    private String name;
    private String email;
    private String avatar;
    /** 角色 OWNER/CONTRIBUTOR/READER。 */
    private String role;
    private LocalDateTime createTime;
}
