package com.pppp.zhimesh.common.languagemodel;

import com.alibaba.dashscope.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Draw;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.languagemodel.wanx.ZhiMeshImageGenerationModel;
import com.pppp.zhimesh.common.languagemodel.wanx.ZhiMeshWanxImageModel;
import com.pppp.zhimesh.common.languagemodel.data.LLMException;
import com.pppp.zhimesh.common.vo.WanxBackgroundGenerationParams;
import dev.langchain4j.model.image.ImageModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 通义万相
 * Tongyi Wanx (DashScope image generation)
 */
@Slf4j
public class DashScopeWanxService extends AbstractImageModelService {

    public DashScopeWanxService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.isNotBlank(platform.getApiKey()) && aiModel.getIsEnable();
    }

    @Override
    protected ImageModel buildImageModel(User user, Draw draw) {
        if (draw.getAiModelName().contains("wanx-background-generation")) {
            JsonNode dynamicParams = draw.getDynamicParams();
            if (dynamicParams.isEmpty()) {
                log.error("Dynamic parameters cannot be empty");
                throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
            }
            WanxBackgroundGenerationParams dynamicParamsObj = JsonUtil.fromJson(dynamicParams, WanxBackgroundGenerationParams.class);
            if (null == dynamicParamsObj) {
                log.error("Failed to parse dynamic parameters");
                throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
            }
            if (StringUtils.isAllBlank(dynamicParamsObj.getRefImageUrl(), dynamicParamsObj.getRefPrompt())) {
                log.error("Reference image and prompt cannot both be empty for Wanx background generation");
                throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
            }
            ZhiMeshWanxImageModel model = ZhiMeshWanxImageModel.builder()
                    .baseUrl(platform.getBaseUrl())
                    .apiKey(platform.getApiKey())
                    .modelName(draw.getAiModelName())
                    .task("background-generation")
                    .function("generation")
                    .size(draw.getGenerateSize())
                    .build();
            model.setImageSynthesisParamCustomizer((paramBuilder -> {
                paramBuilder.extraInput("base_image_url", dynamicParamsObj.getBaseImageUrl());
                if (StringUtils.isNotBlank(dynamicParamsObj.getRefImageUrl())) {
                    paramBuilder.extraInput("ref_image_url", dynamicParamsObj.getRefImageUrl());
                }
                if (StringUtils.isNotBlank(dynamicParamsObj.getRefPrompt())) {
                    paramBuilder.extraInput("ref_prompt", dynamicParamsObj.getRefPrompt());
                }
                paramBuilder.parameter("model_version", "v3");
            }));
            return model;
        } else if (draw.getAiModelName().startsWith("wan2.7")) {
            // wan2.7 走新一代 image-generation 接口(同步调用,不支持 negative_prompt)
            // wan2.7 uses the new image-generation API (synchronous, negative_prompt not supported)
            List<String> allowedSizes = aiModel.getSizes();
            if (!allowedSizes.isEmpty() && !allowedSizes.contains(draw.getGenerateSize())) {
                log.warn("Invalid size {} for model {}, allowed: {}",
                        draw.getGenerateSize(), draw.getAiModelName(), allowedSizes);
                throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
            }
            ZhiMeshImageGenerationModel.ZhiMeshImageGenerationModelBuilder builder = ZhiMeshImageGenerationModel.builder()
                    .apiKey(platform.getApiKey())
                    .modelName(draw.getAiModelName())
                    .size(draw.getGenerateSize())
                    .baseUrl(platform.getBaseUrl());
            if (null != draw.getGenerateSeed() && draw.getGenerateSeed() > 0) {
                builder.seed(draw.getGenerateSeed());
            }
            return builder.build();
        } else {
            ZhiMeshWanxImageModel.WanxImageModelBuilder builder = ZhiMeshWanxImageModel.builder()
                    .baseUrl(platform.getBaseUrl())
                    .apiKey(platform.getApiKey())
                    .modelName(draw.getAiModelName())
                    .size(draw.getGenerateSize())
                    .negativePrompt(draw.getNegativePrompt());
            if (null != draw.getGenerateSeed() && draw.getGenerateSeed() > 0) {
                builder.seed(draw.getGenerateSeed());
            }
            return builder.build();
        }

    }

    @Override
    protected LLMException parseError(Object error) {
        if (error instanceof ApiException apiException) {
            LLMException llmException = new LLMException();
            llmException.setType(apiException.getStatus().getCode());
            llmException.setCode(apiException.getStatus().getCode());
            llmException.setMessage(apiException.getStatus().getMessage());
            return llmException;
        }
        return null;
    }
}
