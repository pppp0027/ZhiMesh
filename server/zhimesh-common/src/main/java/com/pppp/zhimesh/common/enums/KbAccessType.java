package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识库访问级别，数值越大能力越强。所有用户工作区的读写判断统一经由
 * KnowledgeBaseAccessService 归结到该枚举，避免各处散落的 owner/归属层级判断。
 */
@Getter
@AllArgsConstructor
public enum KbAccessType {

    NONE(0),
    READ(1),
    WRITE(2),
    MANAGE(3);

    private final int level;

    /** 当前级别是否满足所需级别。 */
    public boolean satisfies(KbAccessType required) {
        return this.level >= required.level;
    }
}
