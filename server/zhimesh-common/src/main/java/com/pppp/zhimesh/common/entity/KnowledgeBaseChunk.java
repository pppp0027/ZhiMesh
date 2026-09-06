package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("adi_knowledge_base_chunk")
public class KnowledgeBaseChunk extends BaseEntity {
    private String uuid;
    @TableField("chunk_set_id") private Long chunkSetId;
    @TableField("chunk_set_uuid") private String chunkSetUuid;
    @TableField("kb_id") private Long kbId;
    @TableField("kb_uuid") private String kbUuid;
    @TableField("kb_item_id") private Long kbItemId;
    @TableField("kb_item_uuid") private String kbItemUuid;
    @TableField("chunk_index") private Integer chunkIndex;
    private String content;
    @TableField("content_hash") private String contentHash;
    @TableField("token_count") private Integer tokenCount;
    @TableField("char_start") private Integer charStart;
    @TableField("char_end") private Integer charEnd;
}
