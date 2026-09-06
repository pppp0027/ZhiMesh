package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.dto.RefEmbeddingDto;
import com.pppp.zhimesh.common.entity.CharacterMessageRefEmbedding;
import com.pppp.zhimesh.common.mapper.CharacterMessageRefEmbeddingMapper;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import com.pppp.zhimesh.common.util.EmbeddingUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class CharacterMessageRefEmbeddingService extends ServiceImpl<CharacterMessageRefEmbeddingMapper, CharacterMessageRefEmbedding> {

    @Resource
    private IKnowledgeEmbeddingService knowledgeEmbeddingService;

    public List<RefEmbeddingDto> listRefEmbeddings(String msgUuid) {
        List<CharacterMessageRefEmbedding> recordReferences = this.getBaseMapper().listByMsgUuid(msgUuid);
        if (CollectionUtils.isEmpty(recordReferences)) {
            return Collections.emptyList();
        }
        List<String> embeddingIds = recordReferences.stream().map(CharacterMessageRefEmbedding::getEmbeddingId).toList();
        if (CollectionUtils.isEmpty(embeddingIds)) {
            return Collections.emptyList();
        }
        List<KbItemEmbeddingDto> embeddings = knowledgeEmbeddingService.listByEmbeddingIds(embeddingIds);
        return EmbeddingUtil.itemToRefEmbeddingDto(embeddings);
    }

}
