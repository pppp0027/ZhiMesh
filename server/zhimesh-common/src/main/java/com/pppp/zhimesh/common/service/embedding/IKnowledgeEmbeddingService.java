package com.pppp.zhimesh.common.service.embedding;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;

import java.util.List;

public interface IKnowledgeEmbeddingService {
    List<KbItemEmbeddingDto> listByEmbeddingIds(List<String> embeddingIds);

    Page<KbItemEmbeddingDto> listByItemUuid(String kbItemUuid, int currentPage, int pageSize);

    boolean deleteByItemUuid(String kbItemUuid);

    Integer countByKbUuid(String kbUuid);
}
