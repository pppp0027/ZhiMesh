package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Provenance of an AGE graph element. One shared KB graph can therefore be sliced reliably by document. */
@Data
@TableName("adi_knowledge_base_graph_element_source")
public class KnowledgeBaseGraphElementSource extends BaseEntity {
    @TableField("kb_uuid") private String kbUuid;
    @TableField("kb_item_uuid") private String kbItemUuid;
    @TableField("graph_segment_uuid") private String graphSegmentUuid;
    @TableField("chunk_set_uuid") private String chunkSetUuid;
    @TableField("chunk_uuid") private String chunkUuid;
    @TableField("graph_model_id") private Long graphModelId;
    @TableField("graph_index_version_uuid") private String graphIndexVersionUuid;
    @TableField("element_type") private String elementType;
    @TableField("element_id") private String elementId;
    /** Description contributed by this one graph segment. */
    @TableField("contribution_description") private String contributionDescription;
    /** Relationship strength contributed by this segment; null for vertices. */
    @TableField("contribution_weight") private Double contributionWeight;
    /** Canonical entity identity contributed by this segment; empty for edges. */
    @TableField("contribution_canonical_name") private String contributionCanonicalName;
    /** JSON array of source-backed aliases. */
    @TableField("contribution_aliases") private String contributionAliases;
    /** JSON object of entity/relationship attributes that should not become vertices. */
    @TableField("contribution_properties") private String contributionProperties;
    @TableField("contribution_salience") private Double contributionSalience;
    @TableField("relation_type") private String relationType;
    @TableField("relation_polarity") private Boolean relationPolarity;
    @TableField("relation_status") private String relationStatus;
}
