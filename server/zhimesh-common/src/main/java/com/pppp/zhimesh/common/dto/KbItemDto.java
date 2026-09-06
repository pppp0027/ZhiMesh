package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.enums.EmbeddingStatusEnum;
import com.pppp.zhimesh.common.enums.FulltextStatusEnum;
import com.pppp.zhimesh.common.enums.GraphicalStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbItemDto {

    private Long kbId;

    private String kbUuid;

    private Long sourceFileId;

    private Long id;

    private String uuid;

    private String title;

    private String brief;

    private String remark;

    private EmbeddingStatusEnum embeddingStatus;

    private LocalDateTime embeddingStatusChangeTime;

    private GraphicalStatusEnum graphicalStatus;

    private LocalDateTime graphicalStatusChangeTime;

    private FulltextStatusEnum fulltextStatus;

    private LocalDateTime fulltextStatusChangeTime;

    private Long embeddingModelId;

    private Long graphicalModelId;

    private LocalDateTime embeddingStartedAt;

    private LocalDateTime embeddingCompletedAt;

    private LocalDateTime graphicalStartedAt;

    private LocalDateTime graphicalCompletedAt;

    private LocalDateTime fulltextStartedAt;

    private LocalDateTime fulltextCompletedAt;

    private String fulltextChunkSetUuid;

    private String sourceFileName;

    private String sourceFileUuid;

    private String sourceFileUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
