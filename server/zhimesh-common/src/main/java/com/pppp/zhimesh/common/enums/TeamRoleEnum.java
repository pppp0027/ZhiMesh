package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 团队成员角色。OWNER 管理成员与团队全部知识库（MANAGE）；CONTRIBUTOR 可写入共建；
 * READER 只读。权限从成员关系推导，成员关系断开即全部回收。
 */
@Getter
@AllArgsConstructor
public enum TeamRoleEnum {

    OWNER("OWNER"),
    CONTRIBUTOR("CONTRIBUTOR"),
    READER("READER");

    private final String value;

    public static TeamRoleEnum getByValue(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        return Arrays.stream(TeamRoleEnum.values())
                .filter(item -> item.value.equalsIgnoreCase(val.trim()))
                .findFirst()
                .orElse(null);
    }
}
