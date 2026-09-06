package com.pppp.zhimesh.common.dto;

import lombok.Data;

/**
 * 配置信息
 */
@Data
public class ConfigResp {
    private UserQuota userQuota;
    private CostStatResp quotaCost;
    private String locale;
}
