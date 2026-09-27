package com.pppp.zhimesh.common.languagemodel;

import com.knuddels.jtokkit.api.ModelType;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAi LLM service
 */
@Slf4j
@Accessors(chain = true)
public class OpenAiLLMService extends AbstractLLMService {

    /**
     * 平台级自定义请求头，来自 zhimesh.platform-headers.<platform> 配置。
     * Platform-level custom headers from the zhimesh.platform-headers.<platform> config.
     */
    private Map<String, String> customHeaders;

    public OpenAiLLMService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }

    /**
     * 与 {@link AbstractLLMService#setProxyAddress} 同款的手写链式 setter，
     * 供 AiModelInitializer 按平台配置注入。
     * <p>
     * Hand-written chained setter mirroring {@link AbstractLLMService#setProxyAddress},
     * used by AiModelInitializer to inject per-platform config.
     */
    public OpenAiLLMService setCustomHeaders(Map<String, String> customHeaders) {
        this.customHeaders = customHeaders;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.isNotBlank(platform.getApiKey()) && aiModel.getIsEnable();
    }

    @Override
    protected ChatModel doBuildChatModel(ChatModelBuilderProperties properties) {
        if (StringUtils.isBlank(platform.getApiKey())) {
            throw new BaseException(ErrorEnum.B_LLM_SECRET_KEY_NOT_SET);
        }
        requireConfiguredProxy();
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(platform.getBaseUrl())
                .modelName(aiModel.getName())
                .temperature(properties.getTemperature())
                .maxRetries(properties.getMaxRetriesWithDefault(1))
                .timeout(properties.getTimeoutWithDefault(Duration.ofSeconds(60)))
                .apiKey(platform.getApiKey());
        if (StringUtils.isNotBlank(platform.getBaseUrl())) {
            builder.baseUrl(platform.getBaseUrl());
        }
        if (null != proxyAddress && platform.getIsProxyEnable()) {
            HttpClient.Builder httpClientBuilder = HttpClient.newBuilder().proxy(ProxySelector.of(proxyAddress));
            builder.httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(httpClientBuilder));
        }
        if (customHeaders != null && !customHeaders.isEmpty()) {
            builder.customHeaders(customHeaders);
        }
        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreamingChatModel(ChatModelBuilderProperties properties) {
        if (StringUtils.isBlank(platform.getApiKey())) {
            throw new BaseException(ErrorEnum.B_LLM_SECRET_KEY_NOT_SET);
        }
        requireConfiguredProxy();
        double temperature = properties.getTemperatureWithDefault(0.7);
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel
                .builder()
                .baseUrl(platform.getBaseUrl())
                .modelName(aiModel.getName())
                .temperature(temperature)
                .apiKey(platform.getApiKey())
                .timeout(properties.getTimeoutWithDefault(Duration.ofSeconds(60)));
        if (null != proxyAddress && platform.getIsProxyEnable()) {
            HttpClient.Builder httpClientBuilder = HttpClient.newBuilder().proxy(ProxySelector.of(proxyAddress));
            builder.httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(httpClientBuilder));
        }
        if (customHeaders != null && !customHeaders.isEmpty()) {
            builder.customHeaders(customHeaders);
        }
        return builder.build();
    }

    /**
     * A platform that explicitly requires a proxy must never silently fall
     * back to a direct route. Besides making deployment failures hard to
     * diagnose, that fallback can send requests through an unintended egress.
     */
    private void requireConfiguredProxy() {
        if (Boolean.TRUE.equals(platform.getIsProxyEnable()) && proxyAddress == null) {
            throw new IllegalStateException("Platform " + platform.getName()
                    + " requires an HTTP proxy, but zhimesh.proxy host/port is not configured");
        }
    }

    @Override
    protected ChatRequestParameters doCreateChatRequestParameters(ChatRequestParameters defaultParameters, Map<String, Object> customParameters) {
// Platforms compatible with OpenAI API may need custom parameters
        // 兼容 OpenAi api 的平台可能会需要自定义参数
        // An OpenAI-compatible endpoint only guarantees the standard OpenAI schema.
        // Project-specific switches are rejected by providers such as Google AI Studio.
        // Provider implementations that support custom fields must override this method.
        if (customParameters != null && !customParameters.isEmpty()) {
            log.debug("Ignoring unsupported custom request parameters for OpenAI-compatible platform {}: {}",
                    platform.getName(), customParameters.keySet());
        }
        return defaultParameters;
    }

    /**
     * Builds OpenAI-format parameters for a provider that explicitly supports the
     * supplied custom fields.
     */
    protected ChatRequestParameters createOpenAiChatRequestParameters(ChatRequestParameters defaultParameters,
                                                                       Map<String, Object> customParameters) {
        if (customParameters == null || customParameters.isEmpty()) {
            return defaultParameters;
        }
        ChatRequestParameters openAiChatRequestParameters = OpenAiChatRequestParameters.builder()
                .customParameters(customParameters)
                .build();
        return openAiChatRequestParameters.overrideWith(defaultParameters);
    }

    @Override
    public TokenCountEstimator getTokenEstimator() {
        if (aiModel.getPlatform().equals(ZhiMeshConstant.ModelPlatform.OPENAI)) {
            return new OpenAiTokenCountEstimator(aiModel.getName());
        }
        return new OpenAiTokenCountEstimator(ModelType.GPT_3_5_TURBO.getName());
    }

    @Override
    protected LLMException parseError(Object error) {
        if (error instanceof Exception e) {
            LLMException llmException = new LLMException();
            llmException.setMessage(e.getMessage());
            return llmException;
        }
        return null;
    }

}
