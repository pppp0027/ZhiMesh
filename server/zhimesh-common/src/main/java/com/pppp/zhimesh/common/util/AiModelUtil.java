package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import org.apache.commons.lang3.StringUtils;

public class AiModelUtil {

    private AiModelUtil() {
    }

    public static boolean checkModelType(String modelType) {
        return ZhiMeshConstant.ModelType.getModelType().contains(modelType);
    }

    public static boolean checkModelPlatform(String platform) {
        return ZhiMeshConstant.ModelPlatform.getModelConstants().contains(platform);
    }
}
