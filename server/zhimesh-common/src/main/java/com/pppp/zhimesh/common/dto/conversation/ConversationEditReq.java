package com.pppp.zhimesh.common.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.Length;

public record ConversationEditReq(
        @NotBlank @Length(max = 100) String title) {
}
