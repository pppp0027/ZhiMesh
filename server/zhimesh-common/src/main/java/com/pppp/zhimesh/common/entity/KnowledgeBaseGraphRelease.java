package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("adi_knowledge_base_graph_release")
public class KnowledgeBaseGraphRelease extends BaseEntity {
    private String uuid;
    @TableField("kb_id") private Long kbId;
    @TableField("kb_uuid") private String kbUuid;
    private String namespace;
    @TableField("base_release_uuid") private String baseReleaseUuid;
    @TableField("manifest_hash") private String manifestHash;
    private String status;
    @TableField("expected_item_count") private Integer expectedItemCount;
    @TableField("ready_item_count") private Integer readyItemCount;
    @TableField("is_active") private Boolean isActive;
    @TableField("started_at") private LocalDateTime startedAt;
    @TableField("completed_at") private LocalDateTime completedAt;
    @TableField("activated_at") private LocalDateTime activatedAt;
    @TableField("error_message") private String errorMessage;
}
