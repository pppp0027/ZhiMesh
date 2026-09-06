package com.pppp.zhimesh.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/** Lifecycle state of the optional BM25/FULLTEXT index for one knowledge item. */
@Getter
@AllArgsConstructor
public enum FulltextStatusEnum implements BaseEnum {
    NONE(1, "Not indexed"),
    DOING(2, "Indexing"),
    DONE(3, "Indexed"),
    FAIL(4, "Indexing failed");

    private final Integer value;
    private final String desc;

    public static FulltextStatusEnum getByValue(Integer val) {
        return Arrays.stream(values()).filter(item -> item.value.equals(val)).findFirst().orElse(null);
    }
}
