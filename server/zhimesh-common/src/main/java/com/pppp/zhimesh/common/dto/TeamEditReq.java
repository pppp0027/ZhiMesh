package com.pppp.zhimesh.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
public class TeamEditReq {

    /** 编辑时必传；为空表示新建。 */
    private Long id;

    private String uuid;

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String remark;
}
