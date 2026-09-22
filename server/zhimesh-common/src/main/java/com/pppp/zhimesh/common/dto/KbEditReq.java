package com.pppp.zhimesh.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
public class KbEditReq {

    private Long id;

    private String uuid;

    /** 归属层级 PERSONAL/TEAM/COMPANY；缺省 PERSONAL。仅创建时生效，编辑忽略。 */
    private String ownerType;

    /** TEAM 归属的目标团队 uuid；ownerType=TEAM 时必填。 */
    private String teamUuid;

    @NotBlank
    private String title;

    private String remark;

    /** 企业库可见范围 STAFF/EXECUTIVE；仅 ownerType=COMPANY 时生效，缺省 STAFF。 */
    private String companyScope;

    /** 仅管理员可以设置；普通用户请求中的该字段会被忽略。 */
    private Boolean isSystem;

    private Boolean isEnabled;

    private Boolean isStrict;

    private Integer retrieveMaxResults;

    private Double retrieveMinScore;

    @Min(1)
    @Max(2)
    private Integer graphHopDepth;

    private Long rerankModelId;

    @Min(1)
    @Max(10)
    private Integer rerankTopN;

    private Integer ingestMaxOverlap;

    private String ingestSplitStrategy;

    private Integer ingestMaxSegmentSize;

    private String ingestCustomSeparator;

    private Long ingestModelId;

    private String ingestTokenEstimator;

    private Double queryLlmTemperature;

    private String querySystemMessage;
}
