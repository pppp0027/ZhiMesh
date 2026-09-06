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

    @NotBlank
    private String title;

    private String remark;

    private Boolean isPublic;

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
