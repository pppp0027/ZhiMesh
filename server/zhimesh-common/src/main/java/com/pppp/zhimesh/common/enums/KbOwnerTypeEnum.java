package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 知识库归属层级。PERSONAL 为存量默认；TEAM 依赖 adi_team_member 成员关系推导权限；
 * COMPANY 由管理员维护、全员只读。isSystem 机制与本层级正交。
 */
@Getter
@AllArgsConstructor
public enum KbOwnerTypeEnum {

    PERSONAL("PERSONAL"),
    TEAM("TEAM"),
    COMPANY("COMPANY");

    private final String value;

    public static KbOwnerTypeEnum getByValue(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        return Arrays.stream(KbOwnerTypeEnum.values())
                .filter(item -> item.value.equalsIgnoreCase(val.trim()))
                .findFirst()
                .orElse(null);
    }
}
