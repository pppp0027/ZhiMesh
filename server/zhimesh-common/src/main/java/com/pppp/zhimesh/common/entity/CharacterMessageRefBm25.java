package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("adi_character_message_ref_bm25")
public class CharacterMessageRefBm25 implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("message_id")
    private Long messageId;

    @TableField("query_terms")
    private String queryTerms;

    @TableField("chunk_uuid")
    private String chunkUuid;

    @TableField("kb_uuid")
    private String kbUuid;

    @TableField("kb_item_uuid")
    private String kbItemUuid;

    @TableField("content_snapshot")
    private String contentSnapshot;

    @TableField("score")
    private Double score;

    @TableField("hit_rank")
    private Integer hitRank;

    @TableField("user_id")
    private Long userId;
}
