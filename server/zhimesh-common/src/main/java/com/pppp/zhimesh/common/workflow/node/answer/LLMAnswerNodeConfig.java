package com.pppp.zhimesh.common.workflow.node.answer;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
@Data
public class LLMAnswerNodeConfig {
    /**
     * Optional instruction appended by the workflow author. When blank, the
     * answer node sends its first upstream text input directly to the model.
     * This matches the workflow editor's "leave empty to use previous output"
     * behaviour.
     */
    private String prompt;
    @NotBlank
    @JsonProperty("model_name")
    private String modelName;
    @JsonProperty("model_platform")
    private String modelPlatform;
    private Boolean streaming;
}
