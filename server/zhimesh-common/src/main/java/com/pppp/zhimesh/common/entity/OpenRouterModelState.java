package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.base.ObjectNodeTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "adi_openrouter_model_state", autoResultMap = true)
public class OpenRouterModelState extends BaseEntity {

    private String platform;
    private String modelName;
    private Long modelId;
    private Boolean isManaged;
    private String lifecycleStatus;
    private String catalogStatus;
    private String probeStatus;
    private String lastDecision;
    private String disableReason;
    private Integer catalogLatencyP50Ms;
    private BigDecimal catalogThroughputP50;
    /**
     * Explicitly map the numeric suffix because MyBatis-Plus' camel-case
     * conversion turns {@code catalogUptime1d} into {@code catalog_uptime1d},
     * while the migration uses the unambiguous {@code catalog_uptime_1d} name.
     */
    @TableField("catalog_uptime_1d")
    private BigDecimal catalogUptime1d;
    private Integer actualTtftMs;
    private Integer actualTotalLatencyMs;
    private Integer consecutiveFailures;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastProbeAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastDisabledAt;

    @TableField(value = "raw_metadata", jdbcType = JdbcType.JAVA_OBJECT,
            typeHandler = ObjectNodeTypeHandler.class)
    private ObjectNode rawMetadata;
}
