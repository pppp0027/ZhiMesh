package com.pppp.zhimesh.common.dto.conversation;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationDto {
    private Long id;
    private String uuid;
    private Long characterId;
    private String title;
    private Integer status;
    private Boolean isDefault;
    private LocalDateTime lastMessageTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
