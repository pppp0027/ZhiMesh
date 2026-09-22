package com.pppp.zhimesh.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
public class TeamMemberUpdateReq {

    @NotBlank
    private String teamUuid;

    @NotNull
    private Long userId;

    /** 目标角色 OWNER/CONTRIBUTOR/READER。 */
    @NotBlank
    private String role;
}
