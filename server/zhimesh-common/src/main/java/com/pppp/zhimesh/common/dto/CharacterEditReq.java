package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.vo.AudioConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class CharacterEditReq {

    private String title;

    private String remark;

    @Schema(title = "set the system message to ai, ig: you are a lawyer")
    @Pattern(regexp = "(?s).*\\S.*", message = "Role instructions / system prompt cannot be empty")
    private String aiSystemMessage;

    private Boolean understandContextEnable;

    private List<Long> mcpIds;

    private List<Long> kbIds;

    private Integer answerContentType;

    private Boolean isAutoplayAnswer;

    private Boolean isEnableThinking;

    private Boolean isEnableWebSearch;

    private Boolean isAgentic;

    private String toolPolicy;

    private AudioConfig audioConfig;
}
