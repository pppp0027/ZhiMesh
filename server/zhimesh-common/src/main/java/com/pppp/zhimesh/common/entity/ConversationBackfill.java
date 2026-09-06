package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("adi_conversation_backfill")
public class ConversationBackfill extends BaseEntity {

    @TableField("uuid")
    private String uuid;
    @TableField("user_id")
    private Long userId;
    @TableField("character_id")
    private Long characterId;
    @TableField("conversation_id")
    private Long conversationId;
    @TableField("high_water_message_id")
    private Long highWaterMessageId;
    @TableField("last_processed_message_id")
    private Long lastProcessedMessageId;
    @TableField("scanned_count")
    private Long scannedCount;
    @TableField("updated_count")
    private Long updatedCount;
    @TableField("status")
    private String status;
    @TableField("error_message")
    private String errorMessage;
}
