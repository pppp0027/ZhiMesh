package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.SysConfigDto;
import com.pppp.zhimesh.common.dto.SysConfigEditDto;
import com.pppp.zhimesh.common.dto.SysConfigSearchReq;
import com.pppp.zhimesh.common.entity.SysConfig;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.file.AliyunOssFileHelper;
import com.pppp.zhimesh.common.mapper.SysConfigMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.vo.RequestRateLimit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SysConfigService extends ServiceImpl<SysConfigMapper, SysConfig> {

    @Resource
    private AliyunOssFileHelper aliyunOssFileHelper;

    public void loadAndCache() {
        List<SysConfig> configsFromDB = this.lambdaQuery().list();
        if (LocalCache.CONFIGS.isEmpty()) {
            configsFromDB.forEach(item -> LocalCache.CONFIGS.put(item.getName(), item.getValue()));
        } else {
            //remove deleted config
            List<String> deletedKeys = new ArrayList<>();
            LocalCache.CONFIGS.forEach((k, v) -> {
                boolean deleted = configsFromDB.stream().noneMatch(sysConfig -> sysConfig.getName().equals(k));
                if (deleted) {
                    deletedKeys.add(k);
                }
            });
            if (!deletedKeys.isEmpty()) {
                deletedKeys.forEach(LocalCache.CONFIGS::remove);
            }

            //add or update config
            for (SysConfig item : configsFromDB) {
                String key = item.getName();
                LocalCache.CONFIGS.put(key, item.getValue());
            }
        }
        LocalCache.TEXT_RATE_LIMIT_CONFIG = JsonUtil.fromJson(LocalCache.CONFIGS.get(ZhiMeshConstant.SysConfigKey.REQUEST_TEXT_RATE_LIMIT), RequestRateLimit.class);
        LocalCache.IMAGE_RATE_LIMIT_CONFIG = JsonUtil.fromJson(LocalCache.CONFIGS.get(ZhiMeshConstant.SysConfigKey.REQUEST_IMAGE_RATE_LIMIT), RequestRateLimit.class);
        LocalCache.TEXT_RATE_LIMIT_CONFIG.setType(RequestRateLimit.TYPE_TEXT);
        LocalCache.IMAGE_RATE_LIMIT_CONFIG.setType(RequestRateLimit.TYPE_IMAGE);

        aliyunOssFileHelper.reload();
    }

    public int edit(SysConfigEditDto sysConfigDto) {
        LambdaQueryWrapper<SysConfig> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SysConfig::getName, sysConfigDto.getName());
        SysConfig existOne = baseMapper.selectOne(lambdaQueryWrapper);
        if (null == existOne) {
            throw new BaseException(ErrorEnum.A_DATA_NOT_FOUND);
        }
        SysConfig updateOne = new SysConfig();
        updateOne.setId(existOne.getId());
        updateOne.setValue(sysConfigDto.getValue());
        int ret = baseMapper.updateById(updateOne);

        loadAndCache();

        return ret;
    }

    public boolean softDelete(Long id) {
        int ret = baseMapper.deleteById(id);

        loadAndCache();
        return ret > 0;
    }

    public int getCharacterMaxNum() {
        String maxNum = LocalCache.CONFIGS.get(ZhiMeshConstant.SysConfigKey.CHARACTER_MAX_NUM);
        return Integer.parseInt(maxNum);
    }

    public static String getByKey(String key) {
        return LocalCache.CONFIGS.get(key);
    }

    public static Integer getIntByKey(String key, int defaultValue) {
        String val = LocalCache.CONFIGS.get(key);
        if (null != val) {
            return Integer.parseInt(val);
        }
        return defaultValue;
    }


    public Page<SysConfigDto> search(SysConfigSearchReq searchReq, Integer currentPage, Integer pageSize) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(searchReq.getKeyword())) {
            wrapper.like(SysConfig::getName, searchReq.getKeyword());
        }
        if (CollectionUtils.isNotEmpty(searchReq.getNames())) {
            wrapper.in(SysConfig::getName, searchReq.getNames());
        }
        Page<SysConfig> page = baseMapper.selectPage(new Page<>(currentPage, pageSize), wrapper);
        Page<SysConfigDto> result = new Page<>();
        return MPPageUtil.convertToPage(page, result, SysConfigDto.class);
    }

}
