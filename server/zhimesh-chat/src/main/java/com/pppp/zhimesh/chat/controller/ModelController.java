package com.pppp.zhimesh.chat.controller;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.helper.ImageModelContext;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.languagemodel.data.ImageModelInfo;
import com.pppp.zhimesh.common.languagemodel.data.LLMModelInfo;
import com.pppp.zhimesh.common.languagemodel.data.ModelInfo;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.service.ModelHealthService;
import com.pppp.zhimesh.common.service.ModelPlatformService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.ModelType.TEXT;

@RestController
@RequestMapping("/model")
public class ModelController {

    @Resource
    private ModelPlatformService modelPlatformService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private ModelHealthService healthService;

    @Operation(summary = "支持的大语言模型列表 | Supported LLM List")
    @GetMapping(value = "/llms")
    public List<LLMModelInfo> llms() {
        Map<String, ModelPlatform> platforms = platformMap();
        return aiModelService.listEnabledByType(TEXT).stream().map(aiModel -> {
            LLMModelInfo modelInfo = new LLMModelInfo();
            modelInfo.setModelId(aiModel.getId());
            modelInfo.setModelName(aiModel.getName());
            modelInfo.setModelTitle(aiModel.getTitle());
            modelInfo.setModelPlatform(aiModel.getPlatform());
            modelInfo.setEnable(aiModel.getIsEnable());
            BeanUtils.copyProperties(aiModel, modelInfo);
            applyPlatformMetadata(modelInfo, platforms.get(normalizePlatform(aiModel.getPlatform())));
            modelInfo.setIsSupportWebSearch(Boolean.TRUE.equals(aiModel.getIsSupportWebSearch())
                    && LLMContext.getAllServices().stream()
                    .filter(service -> service.getAiModel().getId().equals(aiModel.getId()))
                    .findFirst()
                    .map(service -> service.supportsWebSearch())
                    .orElse(false));
            // 只反映真实用户调用产生的短期被动状态，不发送额外探测请求。
            ModelHealthService.HealthCheckResult health = healthService.getResult(
                    aiModel.getPlatform(), aiModel.getName());
            modelInfo.setHealthStatus(health.getStatus().name());
            modelInfo.setHealthReason(health.getFailReason());
            return modelInfo;
        }).toList();
    }

    @Operation(summary = "支持的图片模型列表 | Supported Image Model List")
    @GetMapping(value = "/imageModels")
    public List<ImageModelInfo> imageModels() {
        Map<String, ModelPlatform> platforms = platformMap();
        return ImageModelContext.LLM_SERVICES.stream()
                .filter(item -> Boolean.TRUE.equals(item.getAiModel().getIsEnable()))
                .map(item -> {
                    AiModel aiModel = item.getAiModel();
                    ImageModelInfo modelInfo = new ImageModelInfo();
                    modelInfo.setModelId(aiModel.getId());
                    modelInfo.setModelName(aiModel.getName());
                    modelInfo.setModelTitle(aiModel.getTitle());
                    modelInfo.setModelPlatform(aiModel.getPlatform());
                    modelInfo.setEnable(aiModel.getIsEnable());
                    BeanUtils.copyProperties(aiModel, modelInfo);
                    applyPlatformMetadata(modelInfo, platforms.get(normalizePlatform(aiModel.getPlatform())));
                    return modelInfo;
                }).toList();
    }

    @Operation(summary = "可用的重排模型列表 | Enabled Rerank Model List")
    @GetMapping(value = "/rerankModels")
    public List<ModelInfo> rerankModels() {
        Map<String, ModelPlatform> platforms = platformMap();
        return aiModelService.listEnabledByType("rerank").stream().map(aiModel -> {
            ModelInfo modelInfo = new ModelInfo();
            modelInfo.setModelId(aiModel.getId());
            modelInfo.setModelName(aiModel.getName());
            modelInfo.setModelTitle(aiModel.getTitle());
            modelInfo.setModelPlatform(aiModel.getPlatform());
            modelInfo.setEnable(aiModel.getIsEnable());
            modelInfo.setType(aiModel.getType());
            modelInfo.setIsFree(aiModel.getIsFree());
            applyPlatformMetadata(modelInfo, platforms.get(normalizePlatform(aiModel.getPlatform())));
            return modelInfo;
        }).toList();
    }

    @Operation(summary = "模型平台列表 | Model Platform List")
    @GetMapping(value = "/platforms")
    public List<ModelPlatform> platforms() {
        List<ModelPlatform> platforms = modelPlatformService.listAll();
        platforms.forEach(item -> {
            item.setApiKey("");
        });
        return platforms;
    }

    private Map<String, ModelPlatform> platformMap() {
        return modelPlatformService.listAll().stream()
                .collect(Collectors.toMap(
                        item -> normalizePlatform(item.getName()),
                        Function.identity(),
                        (first, ignored) -> first));
    }

    private static String normalizePlatform(String platform) {
        return platform == null ? "" : platform.strip().toLowerCase(Locale.ROOT);
    }

    private static void applyPlatformMetadata(ModelInfo modelInfo, ModelPlatform platform) {
        if (platform == null) {
            return;
        }
        modelInfo.setPlatformTitle(platform.getTitle());
        try {
            URI baseUri = new URI(platform.getBaseUrl());
            String scheme = baseUri.getScheme();
            String host = baseUri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return;
            }
            modelInfo.setPlatformHost(host);
            modelInfo.setPlatformIconUrl(new URI(
                    scheme, null, host, baseUri.getPort(), "/favicon.ico", null, null).toASCIIString());
        } catch (NullPointerException | URISyntaxException ignored) {
            // A missing or non-HTTP base URL simply falls back to the frontend's platform monogram.
        }
    }
}
