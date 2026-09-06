package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("adi_knowledge_base_chunk_set")
public class KnowledgeBaseChunkSet extends BaseEntity {
    private String uuid;
    @TableField("kb_id") private Long kbId;
    @TableField("kb_uuid") private String kbUuid;
    @TableField("kb_item_id") private Long kbItemId;
    @TableField("kb_item_uuid") private String kbItemUuid;
    @TableField("source_content_hash") private String sourceContentHash;
    @TableField("split_strategy") private String splitStrategy;
    @TableField("max_segment_size") private Integer maxSegmentSize;
    private Integer overlap;
    @TableField("custom_separator") private String customSeparator;
    @TableField("token_estimator") private String tokenEstimator;
    @TableField("splitter_version") private String splitterVersion;
    @TableField("preprocessor_version") private String preprocessorVersion;
    @TableField("split_config_hash") private String splitConfigHash;
    @TableField("chunk_count") private Integer chunkCount;
    @TableField("total_tokens") private Integer totalTokens;
    private String status;
    @TableField("is_active") private Boolean isActive;
    @TableField("error_type") private String errorType;
    @TableField("error_message") private String errorMessage;
    @TableField("started_at") private LocalDateTime startedAt;
    @TableField("completed_at") private LocalDateTime completedAt;
}
