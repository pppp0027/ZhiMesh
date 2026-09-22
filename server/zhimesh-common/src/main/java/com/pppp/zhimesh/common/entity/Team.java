package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("adi_team")
@Schema(title = "团队实体 | Team Entity", description = "团队表 | Team Table")
public class Team extends BaseEntity {

    @Schema(title = "uuid")
    @TableField("uuid")
    private String uuid;

    @Schema(title = "团队名称 | Team Name")
    @TableField("name")
    private String name;

    @Schema(title = "团队描述 | Team Description")
    @TableField("remark")
    private String remark;

    @Schema(title = "创建者用户id | Creator User ID")
    @TableField("creator_id")
    private Long creatorId;
}
