package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 团队成员记录。团队知识库的访问权限完全由本表推导：删除成员记录即同时回收
 * 该用户在本团队全部知识库上的权限，无需逐库清理授权。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("adi_team_member")
@Schema(title = "团队成员实体 | Team Member Entity", description = "团队成员表 | Team Member Table")
public class TeamMember extends BaseEntity {

    @Schema(title = "团队id | Team ID")
    @TableField("team_id")
    private Long teamId;

    @Schema(title = "用户id | User ID")
    @TableField("user_id")
    private Long userId;

    @Schema(title = "角色: OWNER/CONTRIBUTOR/READER | Role")
    @TableField("role")
    private String role;
}
