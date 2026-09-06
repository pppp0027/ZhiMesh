package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.vo.RequestRateLimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalCache {
    public static final Map<String, String> CONFIGS = new ConcurrentHashMap<>();

    public static RequestRateLimit TEXT_RATE_LIMIT_CONFIG;

    public static RequestRateLimit IMAGE_RATE_LIMIT_CONFIG;

    public static Map<Long, AiModel> MODEL_ID_TO_OBJ = new ConcurrentHashMap<>();
}
