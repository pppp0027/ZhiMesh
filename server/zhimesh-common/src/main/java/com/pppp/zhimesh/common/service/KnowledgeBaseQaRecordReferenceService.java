package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.dto.RefEmbeddingDto;
import com.pppp.zhimesh.common.entity.KnowledgeBaseQaRefEmbedding;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseQaRecordReferenceMapper;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import com.pppp.zhimesh.common.util.EmbeddingUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class KnowledgeBaseQaRecordReferenceService extends ServiceImpl<KnowledgeBaseQaRecordReferenceMapper, KnowledgeBaseQaRefEmbedding> {

    @Resource
    private IKnowledgeEmbeddingService iKnowledgeEmbeddingService;

    public List<RefEmbeddingDto> listRefEmbeddings(String aqRecordUuid) {
        List<KnowledgeBaseQaRefEmbedding> recordReferences = this.getBaseMapper().listByQaUuid(aqRecordUuid);
        if (CollectionUtils.isEmpty(recordReferences)) {
            return Collections.emptyList();
        }
        List<String> embeddingIds = recordReferences.stream().map(KnowledgeBaseQaRefEmbedding::getEmbeddingId).toList();
        if (CollectionUtils.isEmpty(embeddingIds)) {
            return Collections.emptyList();
        }
        List<KbItemEmbeddingDto> embeddings = iKnowledgeEmbeddingService.listByEmbeddingIds(embeddingIds);
        return EmbeddingUtil.itemToRefEmbeddingDto(embeddings);
    }

    public List<Long> listQaRecordIdsWithRefs(Collection<Long> qaRecordIds) {
        if (CollectionUtils.isEmpty(qaRecordIds)) {
            return List.of();
        }
        return getBaseMapper().listQaRecordIdsWithRefs(List.copyOf(qaRecordIds));
    }
}
