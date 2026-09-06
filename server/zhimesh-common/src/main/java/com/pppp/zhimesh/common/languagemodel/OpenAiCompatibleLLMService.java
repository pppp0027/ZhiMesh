package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * API格式兼容OpenAi的各种模型平台
 */
@Slf4j
@Accessors(chain = true)
public class OpenAiCompatibleLLMService extends OpenAiLLMService {
    public OpenAiCompatibleLLMService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }
}
