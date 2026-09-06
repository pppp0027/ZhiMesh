package com.pppp.zhimesh.common.openrouter;

import lombok.Getter;

@Getter
public class OpenRouterClientException extends RuntimeException {
    private final int httpStatus;
    private final String errorCode;

    public OpenRouterClientException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public OpenRouterClientException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
        this.errorCode = errorCode;
    }
}
