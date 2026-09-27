package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.AsrModelContext;
import com.pppp.zhimesh.common.helper.ImageModelContext;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.TtsModelContext;
import com.pppp.zhimesh.common.languagemodel.*;
import com.pppp.zhimesh.common.searchengine.GoogleSearchEngineService;
import com.pppp.zhimesh.common.searchengine.SearchEngineServiceContext;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.vo.GoogleSetting;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.ModelType.*;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.SysConfigKey.GOOGLE_SETTING;
import static com.pppp.zhimesh.common.util.LocalCache.MODEL_ID_TO_OBJ;

@Slf4j
@Service
public class AiModelInitializer {

    @Resource
    private ZhiMeshProperties adiProperties;

    @Resource
    private ModelPlatformService modelPlatformService;

    private InetSocketAddress proxyAddress;

    private List<AiModel> all = new ArrayList<>();

    /**
     * 模型及其配置初始化
     *
     * @param allModels
     */
    public void init(List<AiModel> allModels) {
        this.all = allModels.stream()
                .filter(model -> Boolean.TRUE.equals(model.getIsEnable()))
                .collect(Collectors.toCollection(ArrayList::new));
        MODEL_ID_TO_OBJ.clear();
        for (AiModel model : all) {
            MODEL_ID_TO_OBJ.put(model.getId(), model);
        }
        ZhiMeshProperties.Proxy proxy = adiProperties.getProxy();
        if (proxy != null && proxy.isEnable() && StringUtils.isNotBlank(proxy.getHost()) && proxy.getHttpPort() > 0) {
            proxyAddress = new InetSocketAddress(proxy.getHost(), proxy.getHttpPort());
            log.info("Global HTTP proxy configured for platforms that explicitly enable proxy routing");
        } else if (proxy != null && proxy.isEnable()) {
            proxyAddress = null;
            log.warn("Global HTTP proxy is enabled but host or port is invalid; platforms that require a proxy will fail their health check instead of falling back to direct routing");
        } else {
            proxyAddress = null;
        }

        Map<String, ModelPlatform> nameToPlatform = modelPlatformService.listAll().stream().collect(Collectors.toMap(ModelPlatform::getName, Function.identity(), (v1, v2) -> v1));
        long enabledPlatformCount = all.stream().map(AiModel::getPlatform).distinct().count();
        log.info("Loaded {} enabled AI models across {} configured platforms", all.size(), enabledPlatformCount);
        initLLMServiceList(nameToPlatform, TEXT);
        initLLMServiceList(nameToPlatform, VISION);
        initImageModelServiceList(nameToPlatform);
        initAsrModelServiceList(nameToPlatform);
        initTtsModelServiceList(nameToPlatform);
    }

    /**
     * 初始化大语言模型列表
     *
     * @param nameToPlatform 模型平台名称与模型平台详情映射
     * @param modelType      模型类型：text | vision
     */
    private synchronized void initLLMServiceList(Map<String, ModelPlatform> nameToPlatform, String modelType) {

// OpenAI API compatible model
        // OpenAi api 兼容模型
        initOpenAiCompatibleService(nameToPlatform, modelType, (model, modelPlatformName) -> new OpenAiCompatibleLLMService(model, nameToPlatform.get(modelPlatformName))
                .setCustomHeaders(headerConfigOf(modelPlatformName))
                .setProxyAddress(proxyAddress));

        //deepseek
        initLLMService(ZhiMeshConstant.ModelPlatform.DEEPSEEK, modelType, model -> new DeepSeekLLMService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.DEEPSEEK)).setProxyAddress(proxyAddress));

        //openai
        initLLMService(ZhiMeshConstant.ModelPlatform.OPENAI, modelType, model -> new OpenAiLLMService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.OPENAI)).setProxyAddress(proxyAddress));

        //dashscope
        initLLMService(ZhiMeshConstant.ModelPlatform.DASHSCOPE, modelType, model -> new DashScopeLLMService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.DASHSCOPE)).setProxyAddress(proxyAddress));

        //ollama
        initLLMService(ZhiMeshConstant.ModelPlatform.OLLAMA, modelType, model -> new OllamaLLMService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.OLLAMA)));

        // 硅基流动
        initLLMService(ZhiMeshConstant.ModelPlatform.SILICONFLOW, modelType, model -> new SiliconflowLLMService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.SILICONFLOW)));
    }

    /**
     * 读取 zhimesh.platform-headers 中某个平台的自定义请求头配置。
     * Reads the custom-header config of one platform from zhimesh.platform-headers.
     */
    private Map<String, String> headerConfigOf(String platformName) {
        Map<String, Map<String, String>> platformHeaders = adiProperties.getPlatformHeaders();
        return platformHeaders == null ? null : platformHeaders.get(platformName);
    }

    /**
     * 初始化图片服务、搜索服务
     *
     * @param nameToPlatform 模型平台名称与模型平台详情映射
     */
    private synchronized void initImageModelServiceList(Map<String, ModelPlatform> nameToPlatform) {

        initImageModelService(ZhiMeshConstant.ModelPlatform.OPENAI, model -> new OpenAiImageService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.OPENAI)).setProxyAddress(proxyAddress));
        initImageModelService(ZhiMeshConstant.ModelPlatform.DASHSCOPE, model -> new DashScopeWanxService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.DASHSCOPE)));
        initImageModelService(ZhiMeshConstant.ModelPlatform.SILICONFLOW, model -> new SiliconflowImageModelService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.SILICONFLOW)));

        initGoogleSearchEngine();
    }

    private void initGoogleSearchEngine() {
        GoogleSetting setting = JsonUtil.fromJson(LocalCache.CONFIGS.get(GOOGLE_SETTING), GoogleSetting.class);
        if (setting == null || StringUtils.isAnyBlank(setting.getKey(), setting.getCx())) {
            log.info("Google Custom Search is not configured, skipping initialization");
            return;
        }
        SearchEngineServiceContext.addWebSearcher(ZhiMeshConstant.SearchEngineName.GOOGLE, new GoogleSearchEngineService(proxyAddress));
    }
    /**
     * 初始化语音识别服务
     *
     * @param nameToPlatform 模型平台名称与模型平台详情映射
     */
    private synchronized void initAsrModelServiceList(Map<String, ModelPlatform> nameToPlatform) {
        initAsrModelService(ZhiMeshConstant.ModelPlatform.DASHSCOPE, model -> new DashScopeAsrService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.DASHSCOPE)));
        initAsrModelService(ZhiMeshConstant.ModelPlatform.SILICONFLOW, model -> new SiliconflowAsrService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.SILICONFLOW)));
        initAsrModelService(ZhiMeshConstant.ModelPlatform.OPENAI, model -> new OpenAiAsrService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.OPENAI)).setProxyAddress(proxyAddress));
    }

    /**
     * 初始化语音合成服务
     *
     * @param nameToPlatform 模型平台名称与模型平台详情映射
     */
    private synchronized void initTtsModelServiceList(Map<String, ModelPlatform> nameToPlatform) {
        initTtsModelService(ZhiMeshConstant.ModelPlatform.DASHSCOPE, model -> new DashScopeTtsService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.DASHSCOPE)));
        initTtsModelService(ZhiMeshConstant.ModelPlatform.SILICONFLOW, model -> new SiliconflowTtsService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.SILICONFLOW)));
        initTtsModelService(ZhiMeshConstant.ModelPlatform.OPENAI, model -> new OpenAiTtsService(model, nameToPlatform.get(ZhiMeshConstant.ModelPlatform.OPENAI)).setProxyAddress(proxyAddress));
    }

    private void initLLMService(String platform, String modelType, Function<AiModel, AbstractLLMService> function) {
        List<AiModel> models = all.stream().filter(item -> item.getType().equals(modelType) && item.getPlatform().equals(platform)).toList();
        List<AbstractLLMService> replacements = new ArrayList<>();
        for (AiModel model : models) {
            log.info("add llm model,model:{}", model);
            replacements.add(function.apply(model));
        }
        LLMContext.replaceByPlatform(platform, modelType, replacements);
    }

    private void initOpenAiCompatibleService(Map<String, ModelPlatform> nameToPlatform, String modelType, BiFunction<AiModel, String, AbstractLLMService> function) {
        log.info("init openai api compatible llm model");
        // The platform list is data-driven. Only rows that actually exist in the
        // database and are explicitly marked OpenAI-compatible are considered.
        List<String> compatiblePlatforms = nameToPlatform.values().stream()
                .filter(platform -> Boolean.TRUE.equals(platform.getIsOpenaiApiCompatible()))
                .map(ModelPlatform::getName)
                .toList();
        for (String platform : compatiblePlatforms) {
            List<AiModel> models = all.stream().filter(item -> item.getType().equals(modelType) && item.getPlatform().equals(platform)).toList();
            List<AbstractLLMService> replacements = new ArrayList<>();
            for (AiModel model : models) {
                log.info("add openai api compatible llm model,model:{}", model);
                replacements.add(function.apply(model, platform));
            }
            LLMContext.replaceByPlatform(platform, modelType, replacements);
        }
    }

    private void initImageModelService(String platform, Function<AiModel, AbstractImageModelService> function) {
        List<AiModel> models = all.stream().filter(item -> item.getType().equals(IMAGE) && item.getPlatform().equals(platform)).toList();
        ImageModelContext.clearByPlatform(platform);
        for (AiModel model : models) {
            log.info("add image model,model:{}", model);
            ImageModelContext.addImageModelService(function.apply(model));
        }

    }

    private void initAsrModelService(String platform, Function<AiModel, AbstractAsrModelService> function) {
        List<AiModel> models = all.stream().filter(item -> item.getType().equals(ZhiMeshConstant.ModelType.ASR) && item.getPlatform().equals(platform)).toList();
        AsrModelContext.clearByPlatform(platform);
        for (AiModel model : models) {
            log.info("add asr model,model:{}", model);
            AsrModelContext.addService(function.apply(model));
        }
    }

    private void initTtsModelService(String platform, Function<AiModel, AbstractTtsModelService> function) {
        List<AiModel> models = all.stream().filter(item -> item.getType().equals(ZhiMeshConstant.ModelType.TTS) && item.getPlatform().equals(platform)).toList();
        TtsModelContext.clearByPlatform(platform);
        for (AiModel model : models) {
            log.info("add tts model,model:{}", model);
            TtsModelContext.addService(function.apply(model));
        }
    }


    public void delete(AiModel aiModel) {
        LLMContext.remove(aiModel.getPlatform(), aiModel.getName());
        ImageModelContext.remove(aiModel.getName());
        MODEL_ID_TO_OBJ.remove(aiModel.getId());
        all.removeIf(item -> item.getId().equals(aiModel.getId()));
    }

    public void addOrUpdate(AiModel aiModel) {
        delete(aiModel);
        if (!Boolean.TRUE.equals(aiModel.getIsEnable())) {
            return;
        }
        all.add(aiModel);
        Map<String, ModelPlatform> nameToPlatform = modelPlatformService.listAll().stream().collect(Collectors.toMap(ModelPlatform::getName, Function.identity(), (v1, v2) -> v1));
        String modelType = aiModel.getType();
        if (TEXT.equalsIgnoreCase(modelType) || VISION.equalsIgnoreCase(modelType)) {
            initLLMServiceList(nameToPlatform, aiModel.getType());
        } else if (IMAGE.equalsIgnoreCase(modelType)) {
            initImageModelServiceList(nameToPlatform);
        } else if (ASR.equalsIgnoreCase(modelType)) {
            initAsrModelServiceList(nameToPlatform);
        } else if (TTS.equalsIgnoreCase(modelType)) {
            initTtsModelServiceList(nameToPlatform);
        } else if (!EMBEDDING.equalsIgnoreCase(modelType) && !RERANK.equalsIgnoreCase(modelType)) {
            throw new BaseException(ErrorEnum.A_MODEL_NOT_FOUND);
        }
        MODEL_ID_TO_OBJ.put(aiModel.getId(), aiModel);
    }
}
