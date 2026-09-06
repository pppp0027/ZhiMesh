package com.pppp.zhimesh.common.service;

import com.alibaba.dashscope.utils.JsonUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.entity.KnowledgeBaseQaRefGraph;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseQaRecordRefGraphMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class KnowledgeBaseQaRefGraphService extends ServiceImpl<KnowledgeBaseQaRecordRefGraphMapper, KnowledgeBaseQaRefGraph> {

    public List<Long> listQaRecordIdsWithRefs(Collection<Long> qaRecordIds) {
        if (qaRecordIds == null || qaRecordIds.isEmpty()) {
            return List.of();
        }
        return getBaseMapper().listQaRecordIdsWithRefs(List.copyOf(qaRecordIds));
    }

    public RefGraphDto getByQaUuid(String aqRecordUuid) {
        List<KnowledgeBaseQaRefGraph> list = this.getBaseMapper().listByQaUuid(aqRecordUuid);
        if (list.isEmpty()) {
            return RefGraphDto
                    .builder()
                    .vertices(Collections.emptyList())
                    .edges(Collections.emptyList())
                    .entitiesFromQuestion(Collections.emptyList())
                    .build();
        }
        KnowledgeBaseQaRefGraph refGraph = list.get(0);

        RefGraphDto result = new RefGraphDto();
        String graphStr = refGraph.getGraphFromStore();
        if (StringUtils.isNotBlank(graphStr)) {
            result = JsonUtils.fromJson(graphStr, RefGraphDto.class);
        }
        result.setEntitiesFromQuestion(Arrays.stream(refGraph.getEntitiesFromQuestion().split(",")).filter(StringUtils::isNotBlank).toList());
        if (null == result.getVertices()) {
            result.setVertices(Collections.emptyList());
        }
        if (null == result.getEdges()) {
            result.setEdges(Collections.emptyList());
        }
        return result;
    }
}
