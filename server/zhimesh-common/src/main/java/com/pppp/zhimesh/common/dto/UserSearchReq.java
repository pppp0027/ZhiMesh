package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.enums.UserStatusEnum;
import lombok.Data;

@Data
public class UserSearchReq {
    private String name;
    private String email;
    private String uuid;
    private Integer userStatus;
    private Boolean isAdmin;
    private Long[] createTime;
    private Long[] updateTime;
}
