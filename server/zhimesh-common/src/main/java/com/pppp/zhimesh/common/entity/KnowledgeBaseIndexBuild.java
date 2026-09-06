package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("adi_knowledge_base_index_build")
public class KnowledgeBaseIndexBuild extends BaseEntity {
    private String uuid;
    @TableField("kb_id") private Long kbId;
    @TableField("kb_uuid") private String kbUuid;
    @TableField("kb_item_id") private Long kbItemId;
    @TableField("kb_item_uuid") private String kbItemUuid;
    @TableField("chunk_set_uuid") private String chunkSetUuid;
    @TableField("index_type") private String indexType;
    @TableField("model_id") private Long modelId;
    @TableField("model_identity") private String modelIdentity;
    @TableField("build_key_hash") private String buildKeyHash;
    @TableField("prompt_version") private String promptVersion;
    @TableField("graph_release_uuid") private String graphReleaseUuid;
    @TableField("graph_namespace") private String graphNamespace;
    private String status;
    private Integer attempt;
    @TableField("is_active") private Boolean isActive;
    @TableField("error_type") private String errorType;
    @TableField("error_message") private String errorMessage;
    @TableField("started_at") private LocalDateTime startedAt;
    @TableField("completed_at") private LocalDateTime completedAt;
    @TableField("activated_at") private LocalDateTime activatedAt;
}
