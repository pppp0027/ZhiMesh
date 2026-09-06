package com.pppp.zhimesh.common.dto.extapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExtApiChatReq {
    @NotBlank(message = "query is required")
    private String query;
    private String model;
    @JsonProperty("conversation_uuid")
    @Length(min = 32, max = 32)
    private String conversationUuid;
    @JsonProperty("response_mode")
    @Builder.Default
    private String responseMode = "streaming";
}
