package com.pppp.zhimesh.common.service.embedding;

import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;

import java.util.List;

public interface ICharacterMemoryEmbeddingService {
    List<KbItemEmbeddingDto> listByEmbeddingIds(List<String> embeddingIds);
}
