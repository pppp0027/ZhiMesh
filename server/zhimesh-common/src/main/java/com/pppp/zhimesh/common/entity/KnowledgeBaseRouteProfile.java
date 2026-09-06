package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.base.JsonNodeTypeHandler;
import com.pppp.zhimesh.common.config.FloatArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "adi_knowledge_base_route_profile", autoResultMap = true)
public class KnowledgeBaseRouteProfile extends BaseEntity {
    private String uuid;
    @TableField("profile_set_id") private Long profileSetId;
    @TableField("profile_set_uuid") private String profileSetUuid;
    @TableField("kb_id") private Long kbId;
    @TableField("kb_uuid") private String kbUuid;
    @TableField("profile_type") private String profileType;
    @TableField("profile_key") private String profileKey;
    @TableField("profile_text") private String profileText;
    @TableField(value = "profile_embedding", jdbcType = JdbcType.ARRAY,
            typeHandler = FloatArrayTypeHandler.class)
    private float[] profileEmbedding;
    @TableField("embedding_dimension") private Integer embeddingDimension;
    @TableField(value = "source_refs", jdbcType = JdbcType.OTHER,
            typeHandler = JsonNodeTypeHandler.class)
    private JsonNode sourceRefs;
    private Integer ordinal;
}
