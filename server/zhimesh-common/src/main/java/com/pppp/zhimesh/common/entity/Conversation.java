package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("adi_conversation")
@Schema(title = "Conversation", description = "Independent chat session under a Character")
public class Conversation extends BaseEntity {

    @TableField("uuid")
    private String uuid;

    @TableField("user_id")
    private Long userId;

    @TableField("character_id")
    private Long characterId;

    @TableField("title")
    private String title;

    @TableField("status")
    private Integer status;

    @TableField("is_default")
    private Boolean isDefault;

    @TableField("last_message_time")
    private LocalDateTime lastMessageTime;
}
