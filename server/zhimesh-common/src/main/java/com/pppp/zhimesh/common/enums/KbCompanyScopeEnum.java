package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 企业知识库的可见范围。STAFF 对全体登录用户只读、管理员 MANAGE；
 * EXECUTIVE 仅管理员可见。非 COMPANY 归属一律视为 STAFF（CHECK 约束同口径）。
 */
@Getter
@AllArgsConstructor
public enum KbCompanyScopeEnum {

    STAFF("STAFF"),
    EXECUTIVE("EXECUTIVE");

    private final String value;

    public static KbCompanyScopeEnum getByValue(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        return Arrays.stream(KbCompanyScopeEnum.values())
                .filter(item -> item.value.equalsIgnoreCase(val.trim()))
                .findFirst()
                .orElse(null);
    }
}
