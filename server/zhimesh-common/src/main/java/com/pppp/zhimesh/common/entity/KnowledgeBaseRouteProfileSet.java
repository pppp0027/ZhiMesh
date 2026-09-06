package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("adi_knowledge_base_route_profile_set")
public class KnowledgeBaseRouteProfileSet extends BaseEntity {
    private String uuid;
    @TableField("kb_id") private Long kbId;
    @TableField("kb_uuid") private String kbUuid;
    private Long generation;
    @TableField("source_manifest_hash") private String sourceManifestHash;
    @TableField("embedding_model_id") private Long embeddingModelId;
    @TableField("embedding_model_identity") private String embeddingModelIdentity;
    @TableField("embedding_dimension") private Integer embeddingDimension;
    @TableField("generator_version") private String generatorVersion;
    @TableField("profile_limit") private Integer profileLimit;
    @TableField("profile_count") private Integer profileCount;
    private String status;
    @TableField("is_active") private Boolean isActive;
    @TableField("error_type") private String errorType;
    @TableField("error_message") private String errorMessage;
    @TableField("started_at") private LocalDateTime startedAt;
    @TableField("completed_at") private LocalDateTime completedAt;
    @TableField("activated_at") private LocalDateTime activatedAt;
}
