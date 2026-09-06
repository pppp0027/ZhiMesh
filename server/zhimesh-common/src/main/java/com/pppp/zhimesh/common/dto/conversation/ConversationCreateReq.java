package com.pppp.zhimesh.common.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.Length;

public record ConversationCreateReq(
        @NotBlank @Length(min = 32, max = 32) String characterUuid,
        @Length(max = 100) String title) {
}
