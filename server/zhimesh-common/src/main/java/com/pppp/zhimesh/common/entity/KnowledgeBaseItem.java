package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pppp.zhimesh.common.enums.EmbeddingStatusEnum;
import com.pppp.zhimesh.common.enums.FulltextStatusEnum;
import com.pppp.zhimesh.common.enums.GraphicalStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("adi_knowledge_base_item")
@Schema(title = "知识库条目实体 | Knowledge Base Item Entity", description = "知识库条目表 | Knowledge Base Item Table")
public class KnowledgeBaseItem extends BaseEntity {

    @Schema(title = "知识库id | Knowledge Base ID")
    @TableField("kb_id")
    private Long kbId;

    @Schema(title = "知识库uuid | Knowledge Base UUID")
    @TableField("kb_uuid")
    private String kbUuid;

    @Schema(title = "名称 | Name")
    @TableField("source_file_id")
    private Long sourceFileId;

    @Schema(title = "uuid")
    @TableField("uuid")
    private String uuid;

    @Schema(title = "标题 | Title")
    @TableField("title")
    private String title;

    @Schema(title = "内容摘要 | Content Summary")
    @TableField("brief")
    private String brief;

    @Schema(title = "内容 | Content")
    @TableField("remark")
    private String remark;

    @Schema(title = "向量化状态 | Embedding Status")
    @TableField("embedding_status")
    private EmbeddingStatusEnum embeddingStatus;

    @Schema(title = "向量化状态变更时间点 | Embedding Status Change Time")
    @TableField("embedding_status_change_time")
    private LocalDateTime embeddingStatusChangeTime;

    @Schema(title = "图谱化状态 | Graphical Status")
    @TableField("graphical_status")
    private GraphicalStatusEnum graphicalStatus;

    @Schema(title = "图谱化状态变更时间点 | Graphical Status Change Time")
    @TableField("graphical_status_change_time")
    private LocalDateTime graphicalStatusChangeTime;

    @Schema(title = "BM25 keyword index status | BM25 keyword index status")
    @TableField("fulltext_status")
    private FulltextStatusEnum fulltextStatus;

    @Schema(title = "BM25 keyword index status change time | BM25 status change time")
    @TableField("fulltext_status_change_time")
    private LocalDateTime fulltextStatusChangeTime;

    @TableField("active_chunk_set_uuid")
    private String activeChunkSetUuid;
    @TableField("embedding_chunk_set_uuid")
    private String embeddingChunkSetUuid;
    @TableField("graphical_chunk_set_uuid")
    private String graphicalChunkSetUuid;
    @TableField("fulltext_chunk_set_uuid")
    private String fulltextChunkSetUuid;
    @TableField("embedding_model_id")
    private Long embeddingModelId;
    @TableField("graphical_model_id")
    private Long graphicalModelId;
    @TableField("embedding_started_at")
    private LocalDateTime embeddingStartedAt;
    @TableField("embedding_completed_at")
    private LocalDateTime embeddingCompletedAt;
    @TableField("graphical_started_at")
    private LocalDateTime graphicalStartedAt;
    @TableField("graphical_completed_at")
    private LocalDateTime graphicalCompletedAt;
    @TableField("fulltext_started_at")
    private LocalDateTime fulltextStartedAt;
    @TableField("fulltext_completed_at")
    private LocalDateTime fulltextCompletedAt;
}
