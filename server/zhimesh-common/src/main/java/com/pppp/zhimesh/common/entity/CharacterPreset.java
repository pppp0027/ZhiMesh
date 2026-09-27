package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("adi_character_preset")
@Schema(title = "预设角色实体 | Character Preset Entity", description = "预设角色表 | Character Preset Table")
public class CharacterPreset extends BaseEntity {
    private String uuid;
    private String title;
    private String remark;
    private String aiSystemMessage;
    /** Comma-separated system knowledge-base ids available to this preset. */
    private String systemKbIds;
    private String mcpIds;
    private String type;
    private Boolean isSystem;
    /** Tool policy JSON (same shape as Character.toolPolicy); copied into the user character on preset instantiation. */
    private String toolPolicy;
}
