package com.pppp.zhimesh.common.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KeywordHitDto {
    private String chunkUuid;
    private String kbUuid;
    private String kbItemUuid;
    private String text;
    private Double score;
    private Integer rank;
}
