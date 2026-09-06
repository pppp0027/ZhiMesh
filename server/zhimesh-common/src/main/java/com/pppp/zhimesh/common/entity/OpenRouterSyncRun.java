package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.base.ObjectNodeTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "adi_openrouter_sync_run", autoResultMap = true)
public class OpenRouterSyncRun extends BaseEntity {

    private String uuid;
    private String triggerType;
    private String status;
    private Integer catalogCount;
    private Integer freeCount;
    private Integer eligibleCount;
    private Integer probedCount;
    private Integer addedCount;
    private Integer updatedCount;
    private Integer enabledCount;
    private Integer disabledCount;
    private Integer skippedCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorCode;
    private String errorMessage;

    @TableField(value = "summary", jdbcType = JdbcType.JAVA_OBJECT,
            typeHandler = ObjectNodeTypeHandler.class)
    private ObjectNode summary;
}
