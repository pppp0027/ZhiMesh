package com.pppp.zhimesh.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbInfoResp {
    private Long id;
    private String uuid;
    private String title;
    private String remark;
    /** 企业库可见范围 STAFF/EXECUTIVE；非 COMPANY 归属恒为 STAFF。 */
    private String companyScope;
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
    /** 归属层级 PERSONAL/TEAM/COMPANY；旧行未回填时按 PERSONAL 处理。 */
    private String ownerType;
    /** TEAM 归属的团队 id，其余为 0 或 null。 */
    private Long teamId;
    /** TEAM 归属的团队 uuid（列表查询联表填充）。 */
    private String teamUuid;
    /** TEAM 归属的团队名称（列表查询联表填充）。 */
    private String teamName;
    /** 当前用户在该库所属团队中的角色，仅 TEAM 归属且为成员时返回。 */
    private String myRole;
    /** 当前用户的访问级别 READ/WRITE/MANAGE，由 KnowledgeBaseAccessService 裁决后填充。 */
    private String accessLevel;
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
