package com.pppp.zhimesh.common.dto;

import lombok.Builder;
import lombok.Data;

/** Result of parsing one file in an administrator batch upload. */
@Data
@Builder
public class KbUploadResult {

    private String fileName;

    private String fileUuid;

    private String itemUuid;

    private boolean parsed;

    private boolean indexQueued;

    private String message;
}
