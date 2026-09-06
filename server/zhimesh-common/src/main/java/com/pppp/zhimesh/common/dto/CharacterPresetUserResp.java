package com.pppp.zhimesh.common.dto;

import lombok.Data;

/**
 * Preset-role data exposed to the user workspace.
 *
 * <p>System knowledge-base identifiers and metadata remain in the
 * administration boundary. The user workspace only receives the capability
 * state needed to explain the role's behavior.</p>
 */
@Data
public class CharacterPresetUserResp {
    private Long id;
    private String uuid;
    private String title;
    private String remark;
    private String aiSystemMessage;
    private String mcpIds;
    private String type;
    private Boolean isSystem;
    private Boolean systemKnowledgeEnabled;
}
