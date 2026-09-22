package com.pppp.zhimesh.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
public class KbTransferReq {

    @NotBlank
    private String kbUuid;

    /** 目标归属层级；仅支持 PERSONAL/TEAM 互转，COMPANY 不参与转移。 */
    @NotBlank
    private String ownerType;

    /** ownerType=TEAM 时的目标团队 uuid。 */
    private String teamUuid;
}
