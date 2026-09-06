package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.entity.KnowledgeBaseGraphSegment;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseGraphSegmentMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseGraphSegmentService extends ServiceImpl<KnowledgeBaseGraphSegmentMapper, KnowledgeBaseGraphSegment> {

    public void removeDocumentSegments(String kbUuid, String kbItemUuid) {
        lambdaUpdate()
                .eq(KnowledgeBaseGraphSegment::getKbUuid, kbUuid)
                .eq(KnowledgeBaseGraphSegment::getKbItemUuid, kbItemUuid)
                .remove();
    }

    /** Drops every graph segment of one knowledge base; used when the KB itself is deleted. */
    public void removeKnowledgeBaseSegments(String kbUuid) {
        lambdaUpdate()
                .eq(KnowledgeBaseGraphSegment::getKbUuid, kbUuid)
                .remove();
    }

    public List<KnowledgeBaseGraphSegment> listByUuidsPreservingOrder(List<String> segmentUuids) {
        if (segmentUuids == null || segmentUuids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, KnowledgeBaseGraphSegment> byUuid = lambdaQuery()
                .in(KnowledgeBaseGraphSegment::getUuid, segmentUuids)
                .list()
                .stream()
                .collect(Collectors.toMap(KnowledgeBaseGraphSegment::getUuid, Function.identity(),
                        (left, right) -> left));
        return segmentUuids.stream().map(byUuid::get).filter(java.util.Objects::nonNull).toList();
    }
}
