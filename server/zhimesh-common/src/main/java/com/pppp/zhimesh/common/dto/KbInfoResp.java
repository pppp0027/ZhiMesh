package com.pppp.zhimesh.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbInfoResp {
    private Long id;
    private String uuid;
    private String title;
    private String remark;
    private Boolean isPublic;
    private Boolean isSystem;
    private Boolean isEnabled;
    private Boolean isStrict;
    private Integer starCount;
    private Integer ingestMaxOverlap;
    private String ingestSplitStrategy;
    private Integer ingestMaxSegmentSize;
    private String ingestCustomSeparator;
    private String ingestModelName;
    private Long ingestModelId;
    private String ingestTokenEstimator;
    private String ingestEmbeddingModel;
    private Integer retrieveMaxResults;
    private Double retrieveMinScore;
    private Integer graphHopDepth;
    private Long rerankModelId;
    private Integer rerankTopN;
    private Double queryLlmTemperature;
    private String querySystemMessage;
    private String ownerUuid;
    private String ownerName;
    private Integer itemCount;
    private Integer embeddingCount;
    private String routeProfileStatus;
    private Long routeProfileGeneration;
    private Long routeProfileActiveGeneration;
    private String routeProfileSetUuid;
    private String routeProfileSourceHash;
    private Long routeProfileModelId;
    private String routeProfileModelIdentity;
    private LocalDateTime routeProfileStatusChangeTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
