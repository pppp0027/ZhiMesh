package com.pppp.zhimesh.common.util;

import org.apache.commons.lang3.StringUtils;

public class ZhiMeshApacheAgeJSONFilterMapper extends ZhiMeshApacheAgeFilterMapper {
    final String metadataColumn;

    public ZhiMeshApacheAgeJSONFilterMapper(String metadataColumn) {
        this.metadataColumn = metadataColumn;
    }

    String formatKey(String key, Class<?> valueType) {
        String metadataName = metadataColumn;
        if (StringUtils.isNotBlank(alias)) {
            metadataName = alias + "." + metadataName;
        }
        return metadataName + "." + key;
    }

    String formatKeyAsString(String key) {
        String metadataName = metadataColumn;
        if (StringUtils.isNotBlank(alias)) {
            metadataName = alias + "." + metadataName;
        }
        return metadataName + "." + key;
    }


    String formatJsonKeyAsString(String key) {
        String metadataName = metadataColumn;
        if (StringUtils.isNotBlank(alias)) {
            metadataName = alias + "." + metadataName;
        }
        return metadataName + "." + key;
    }
}