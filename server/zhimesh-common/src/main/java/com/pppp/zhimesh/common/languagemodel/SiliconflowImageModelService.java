package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Draw;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.image.ImageModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.ProxySelector;
import java.net.http.HttpClient;

/**
 * 硅基流动 图像模型服务
 */
@Slf4j
public class SiliconflowImageModelService extends AbstractImageModelService {

    public SiliconflowImageModelService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.isNotBlank(platform.getApiKey()) && aiModel.getIsEnable();
    }

    @Override
    protected ImageModel buildImageModel(User user, Draw draw) {
        SiliconflowImageModel.SiliconflowImageModelBuilder builder = SiliconflowImageModel.builder()
                .baseUrl(platform.getBaseUrl())
                .apiKey(platform.getApiKey())
                .modelName(draw.getAiModelName())
                .seed(draw.getGenerateSeed().longValue())
                .imageSize(draw.getGenerateSize())
                .negativePrompt(draw.getNegativePrompt());
        if (null != proxyAddress && platform.getIsProxyEnable()) {
            HttpClient.Builder httpClientBuilder = HttpClient.newBuilder().proxy(ProxySelector.of(proxyAddress));
            builder.httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(httpClientBuilder));
        }
        return builder.build();
    }

    @Override
    protected LLMException parseError(Object error) {
        return LLMException.builder().message(error.toString()).build();
    }
}
