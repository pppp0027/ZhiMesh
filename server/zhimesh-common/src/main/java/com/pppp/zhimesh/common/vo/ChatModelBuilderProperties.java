package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.Objects;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatModelBuilderProperties {
    private Double temperature;

    /**
     * 是否捕获并返回模型的思考内容（如 DeepSeek 的 reasoning_content）
     */
    private Boolean returnThinking;

    /** Optional request timeout for non-streaming/internal model calls. */
    private Duration timeout;

    /** Optional provider-level retries; callers with their own retry policy use zero. */
    private Integer maxRetries;

    /**
     * 获取采样温度，如果温度不合法则获取默认温度
     */
    public Double getTemperatureWithDefault(double defaultTemperature) {
        if (defaultTemperature < 0 || defaultTemperature > 1) {
            throw new BaseException(ErrorEnum.B_LLM_TEMPERATURE_ERROR);
        }
        if (Objects.isNull(temperature)) {
            return defaultTemperature;
        }
        if (temperature < 0 || temperature > 1) {
            return defaultTemperature;
        }
        return temperature;
    }

    public Duration getTimeoutWithDefault(Duration defaultTimeout) {
        Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        return timeout == null || timeout.isZero() || timeout.isNegative() ? defaultTimeout : timeout;
    }

    public int getMaxRetriesWithDefault(int defaultMaxRetries) {
        int safeDefault = Math.max(0, defaultMaxRetries);
        return maxRetries == null || maxRetries < 0 ? safeDefault : maxRetries;
    }

}
