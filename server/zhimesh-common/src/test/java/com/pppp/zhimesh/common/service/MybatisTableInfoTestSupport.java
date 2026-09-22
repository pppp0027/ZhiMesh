package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单测专用的 MyBatis-Plus TableInfo 初始化：lambda 列解析与 ServiceImpl 的
 * lambdaQuery()/lambdaUpdate() 在脱离容器时需要实体元数据。每个实体使用独立的
 * MapperBuilderAssistant（namespace 只能设置一次）。
 */
final class MybatisTableInfoTestSupport {

    private MybatisTableInfoTestSupport() {
    }

    static void init(Class<?>... entityTypes) {
        for (Class<?> entityType : entityTypes) {
            MapperBuilderAssistant assistant =
                    new MapperBuilderAssistant(new MybatisConfiguration(), "test");
            assistant.setCurrentNamespace(entityType.getName());
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
