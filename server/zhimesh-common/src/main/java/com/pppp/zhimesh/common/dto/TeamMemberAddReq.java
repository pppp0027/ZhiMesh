package com.pppp.zhimesh.common.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
public class TeamMemberAddReq {

    @NotBlank
    private String teamUuid;

    /** 邀请对象邮箱；email 是全站唯一身份标识（adi_user.name 无唯一索引）。 */
    @NotBlank
    @Email
    private String email;

    /** 可选角色 CONTRIBUTOR/READER；缺省 CONTRIBUTOR。不允许直接以 OWNER 身份邀请。 */
    private String role;
}
