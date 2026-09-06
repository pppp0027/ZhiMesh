package com.pppp.zhimesh.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

@Slf4j
public class ZhiMeshPropertiesUtil {

    public static String EMBEDDING_TABLE_SUFFIX = "";

    private ZhiMeshPropertiesUtil() {
    }

    public static Pair<String, Integer> getSuffixAndDimension(ZhiMeshProperties adiProperties) {
        String suffix = "";
        int dimension = 384;
        if (ZhiMeshConstant.EmbeddingModel.BGE_SMALL_ZH_V15.equals(adiProperties.getEmbeddingModel())) {
            dimension = ZhiMeshConstant.EmbeddingModel.BGE_SMALL_ZH_V15_DIMENSION;
            suffix = "bge_" + dimension;
            EMBEDDING_TABLE_SUFFIX = suffix;
        }
        //非本地向量模型
        else if (!ZhiMeshConstant.EmbeddingModel.LOCAL_MODELS.contains(adiProperties.getEmbeddingModel())) {
            AiModel aiModel = getEmbeddingModelByProperty(adiProperties);
            String platform = aiModel.getPlatform();
            String modelName = aiModel.getName();
            JsonNode jsonNode = aiModel.getProperties().get("dimension");
            if (null == jsonNode) {
                log.error("Embedding model dimension property not found, model id:{}, model name:{}", aiModel.getId(), modelName);
                throw new RuntimeException("model dimension is not configured, model name:" + modelName);
            }
            dimension = jsonNode.asInt();
            suffix = platform + "_" + dimension;
            EMBEDDING_TABLE_SUFFIX = suffix;
        }
        Pair<String, Integer> tableSuffixAndDimension = Pair.of(suffix, dimension);
        log.info("getSuffixAndDimension:{}", tableSuffixAndDimension);
        return tableSuffixAndDimension;
    }

    public static AiModel getEmbeddingModelByProperty(ZhiMeshProperties adiProperties) {
        String[] platformAndModel = adiProperties.getEmbeddingModel().split(":");
        String platform = platformAndModel[0];
        String modelName = platformAndModel[1];
        AiModel aiModel = LocalCache.MODEL_ID_TO_OBJ.values().stream()
                .filter(item -> item.getPlatform().equals(platform) && item.getName().equals(modelName))
                .findFirst()
                .orElse(null);
        if (null == aiModel) {
            log.error("Model not found or disabled, platform:{}, name:{}", platform, modelName);
            throw new RuntimeException("Model not found or disabled, name:" + modelName);
        }
        return aiModel;
    }
}
