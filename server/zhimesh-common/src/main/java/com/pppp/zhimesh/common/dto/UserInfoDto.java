package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.enums.UserStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserInfoDto{

    private Long id;

    private String name;

    private String email;

    private String uuid;

    private String avatar;

    private Integer quotaByTokenDaily;

    private Integer quotaByTokenMonthly;

    private Integer quotaByRequestDaily;

    private Integer quotaByRequestMonthly;

    private Integer quotaByImageDaily;

    private Integer quotaByImageMonthly;

    private UserStatusEnum userStatus;

    private LocalDateTime activeTime;

    private Boolean isAdmin;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
