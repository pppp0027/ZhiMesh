package com.pppp.zhimesh.common.memory.shortterm;

import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.SpringUtil;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CONVERSATION_BUSY;

public class ShortTermMemoryBusyException extends BaseException {

    public ShortTermMemoryBusyException() {
        super(A_CONVERSATION_BUSY.getCode(), resolveMessage());
    }

    private static String resolveMessage() {
        try {
            return SpringUtil.getMessage(A_CONVERSATION_BUSY.getInfo());
        } catch (RuntimeException exception) {
            return "This conversation is already processing another request. Please retry shortly.";
        }
    }
}
