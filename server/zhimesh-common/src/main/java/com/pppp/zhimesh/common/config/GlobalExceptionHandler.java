package com.pppp.zhimesh.common.config;

import com.pppp.zhimesh.common.base.BaseResponse;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 参数校验异常
     *
     * @return BaseResponse
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    private BaseResponse handleMethodArgumentNotValidException(
            final MethodArgumentNotValidException exception) {
        Map<Object, Object> error = wrapperError(exception.getBindingResult());
        log.error("Parameter validation error:{}", error);
        return new BaseResponse(ErrorEnum.A_PARAMS_ERROR.getCode(), SpringUtil.getMessage(ErrorEnum.A_PARAMS_ERROR.getInfo()), error);
    }

    @ExceptionHandler(BaseException.class)
    private BaseResponse handleBaseException(final BaseException exception) {
        log.error("Business exception intercepted:{}", exception);
        return new BaseResponse(exception.getCode(), exception.getInfo(), exception.getData());
    }

    /**
     * SSE/异步请求超时后响应通常已经提交，不能再写普通 BaseResponse。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    private void handleAsyncRequestTimeoutException(final AsyncRequestTimeoutException exception) {
        log.warn("Async request timed out after the response was committed");
    }

    /**
     * 兜底
     *
     * @return BaseResponse
     */
    @ExceptionHandler(Exception.class)
    private BaseResponse handleException(final Exception exception) {
        log.error("Global exception intercepted:", exception);
        return new BaseResponse(ErrorEnum.B_GLOBAL_ERROR.getCode(), SpringUtil.getMessage(ErrorEnum.B_GLOBAL_ERROR.getInfo()), null);
    }

    private Map<Object, Object> wrapperError(BindingResult result) {
        Map<Object, Object> errorMap = new HashMap<>(5);
        result.getFieldErrors().forEach(x -> errorMap.put(x.getField(), x.getDefaultMessage()));
        return errorMap;
    }
}
