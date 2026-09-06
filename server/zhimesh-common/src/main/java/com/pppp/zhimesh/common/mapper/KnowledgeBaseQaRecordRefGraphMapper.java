package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pppp.zhimesh.common.entity.KnowledgeBaseQaRefGraph;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeBaseQaRecordRefGraphMapper extends BaseMapper<KnowledgeBaseQaRefGraph> {
    List<KnowledgeBaseQaRefGraph> listByQaUuid(@Param("qaUuid") String qaUuid);

    List<Long> listQaRecordIdsWithRefs(@Param("qaRecordIds") List<Long> qaRecordIds);
}
