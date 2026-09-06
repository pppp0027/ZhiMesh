package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.entity.OpenRouterModelState;
import com.pppp.zhimesh.common.mapper.OpenRouterModelStateMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OpenRouterModelStateService
        extends ServiceImpl<OpenRouterModelStateMapper, OpenRouterModelState> {

    public List<OpenRouterModelState> listByPlatform(String platform) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(OpenRouterModelState::getPlatform, platform)
                .orderByAsc(OpenRouterModelState::getModelName)
                .list();
    }
}
