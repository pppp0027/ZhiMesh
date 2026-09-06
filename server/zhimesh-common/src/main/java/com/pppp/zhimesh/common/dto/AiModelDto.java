package com.pppp.zhimesh.common.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.interfaces.AiModelAddGroup;
import com.pppp.zhimesh.common.interfaces.AiModelEditGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

@Validated
@Data
public class AiModelDto {

    @NotNull(groups = AiModelEditGroup.class)
    private Long id;

    @NotBlank(groups = AiModelAddGroup.class)
    private String type;

    @NotBlank(groups = AiModelAddGroup.class)
    private String name;

    private String title;
    @NotBlank(groups = AiModelAddGroup.class)
    private String platform;

    private String remark;

    private Boolean isEnable;

    private Boolean isFree;

    private Integer maxInputTokens;

    private String inputTypes;

    private Boolean isReasoner;

    private Boolean isThinkingClosable;

    private String responseFormatTypes;

    private Boolean isSupportWebSearch;

    private ObjectNode properties;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
