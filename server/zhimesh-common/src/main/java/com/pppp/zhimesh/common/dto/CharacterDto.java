package com.pppp.zhimesh.common.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.vo.AudioConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CharacterDto {

    private Long id;
    private String uuid;

    @NotBlank
    private String title;

    private String remark;

    @Schema(title = "set the system message to ai, ig: you are a lawyer")
    private String aiSystemMessage;

    private Boolean understandContextEnable;

    private List<Long> mcpIds;
    private List<Long> kbIds;
    private List<CharacterKnowledge> characterKnowledgeList;
    /** Whether this character receives non-disclosed system knowledge through a preset binding. */
    private Boolean systemKnowledgeEnabled;
    /** Number of non-disclosed system knowledge bases consuming the character scope. */
    private Integer systemKnowledgeCount;
    /** Effective per-character knowledge-base limit, including system bindings. */
    private Integer knowledgeBaseLimit;
    private Integer answerContentType;
    private Boolean isAutoplayAnswer;
    private Boolean isEnableThinking;
    private Boolean isEnableWebSearch;
    private Boolean isAgentic;
    private String toolPolicy;
    private AudioConfig audioConfig;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
