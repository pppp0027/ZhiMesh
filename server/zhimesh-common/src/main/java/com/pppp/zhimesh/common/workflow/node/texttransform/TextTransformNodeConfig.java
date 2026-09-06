package com.pppp.zhimesh.common.workflow.node.texttransform;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * A deliberately small, deterministic alternative to a code node. It covers
 * the text clean-up operations a workflow commonly needs without exposing a
 * server-side script execution surface.
 */
@Data
public class TextTransformNodeConfig {
    private String operation = "trim";

    @JsonProperty("find_text")
    private String findText;

    @JsonProperty("replace_text")
    private String replaceText;
}
