package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import dev.langchain4j.model.embedding.EmbeddingModel;

public abstract class AbstractEmbeddingModelService extends CommonModelService {

    protected AbstractEmbeddingModelService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }

    abstract public EmbeddingModel buildModel();
}
