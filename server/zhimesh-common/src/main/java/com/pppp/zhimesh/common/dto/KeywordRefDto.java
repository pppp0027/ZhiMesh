package com.pppp.zhimesh.common.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KeywordRefDto {
    private List<String> terms;
    private List<KeywordHitDto> hits;
}
