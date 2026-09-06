package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import lombok.Getter;
import lombok.Setter;

import java.net.InetSocketAddress;

public class CommonModelService {
    protected InetSocketAddress proxyAddress;
    @Getter
    protected AiModel aiModel;
    @Setter
    @Getter
    protected ModelPlatform platform;

    public CommonModelService(AiModel aiModel, ModelPlatform modelPlatform) {
        this.aiModel = aiModel;
        this.platform = modelPlatform;
    }
}
