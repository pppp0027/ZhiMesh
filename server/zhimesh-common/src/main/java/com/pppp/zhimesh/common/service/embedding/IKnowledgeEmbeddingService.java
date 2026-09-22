package com.pppp.zhimesh.common.service.embedding;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;

import java.util.List;

public interface IKnowledgeEmbeddingService {
    List<KbItemEmbeddingDto> listByEmbeddingIds(List<String> embeddingIds);

    Page<KbItemEmbeddingDto> listByItemUuid(String kbItemUuid, int currentPage, int pageSize);

    boolean deleteByItemUuid(String kbItemUuid);

    /**
     * 删除{kbUuid}这个知识库的全部向量
     * Delete every embedding of the whole knowledge base {kbUuid}.
     *
     * @param kbUuid 知识库uuid
     * @return
     */
    boolean deleteByKbUuid(String kbUuid);

    Integer countByKbUuid(String kbUuid);
}
