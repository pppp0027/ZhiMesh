package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeBaseChunkMapper extends BaseMapper<KnowledgeBaseChunk> {
    List<KnowledgeBaseChunk> selectRouteProfileSamples(@Param("kbUuid") String kbUuid,
                                                       @Param("limit") int limit);
}
