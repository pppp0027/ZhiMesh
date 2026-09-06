package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.onnx.HuggingFaceTokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class TokenEstimatorFactory {

    public static TokenCountEstimator create(String tokenEstimator) {
        if (StringUtils.isBlank(tokenEstimator)) {
            return new OpenAiTokenCountEstimator(OpenAiChatModelName.GPT_3_5_TURBO);
        }
        if (ZhiMeshConstant.TokenEstimator.OPENAI.equals(tokenEstimator)) {
            return new OpenAiTokenCountEstimator(OpenAiChatModelName.GPT_3_5_TURBO);
        } else if (ZhiMeshConstant.TokenEstimator.HUGGING_FACE.equals(tokenEstimator)) {
            return new HuggingFaceTokenCountEstimator();
        } else if (ZhiMeshConstant.TokenEstimator.QWEN.equals(tokenEstimator)) {
            AbstractLLMService llmService = LLMContext.getAllServices()
                    .stream()
                    .filter(item -> {
                        AiModel aiModel = item.getAiModel();
                        return aiModel.getPlatform().equals(ZhiMeshConstant.ModelPlatform.DASHSCOPE) && aiModel.getType().equals(ZhiMeshConstant.ModelType.TEXT);
                    })
                    .findFirst().orElse(null);
            if (null != llmService) {
                return llmService.getTokenEstimator();
            } else {
                log.warn("Qwen model tokenizer not found, using default OpenAiTokenizer");
                return new OpenAiTokenCountEstimator(OpenAiChatModelName.GPT_3_5_TURBO);
            }
        }
        return new OpenAiTokenCountEstimator(OpenAiChatModelName.GPT_3_5_TURBO);
    }

}
