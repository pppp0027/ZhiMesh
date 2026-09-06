package com.pppp.zhimesh.common.languagemodel;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;

import java.net.InetSocketAddress;

public abstract class AbstractAsrModelService extends CommonModelService {

    protected AbstractAsrModelService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }

    public abstract String audioToText(String urlOrPath);

    public AbstractAsrModelService setProxyAddress(InetSocketAddress proxyAddress) {
        this.proxyAddress = proxyAddress;
        return this;
    }
}
