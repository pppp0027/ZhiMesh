package com.pppp.zhimesh.common.service.embedding.pgvector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.entity.EpisodicMemoryEmbedding;
import com.pppp.zhimesh.common.mapper.EpisodicMemoryEmbeddingMapper;
import com.pppp.zhimesh.common.service.embedding.IEpisodicMemoryEmbeddingService;
import com.pppp.zhimesh.common.util.MPPageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * pgvector implementation of {@link IEpisodicMemoryEmbeddingService}. Reverse-lookup
 * over the physically isolated {@code adi_character_episodic_memory_embedding} table.
 * Mirrors {@link CharacterMemoryEmbeddingService} for the semantic store.
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "zhimesh.vector-database", havingValue = "pgvector")
public class EpisodicMemoryEmbeddingService extends ServiceImpl<EpisodicMemoryEmbeddingMapper, EpisodicMemoryEmbedding> implements IEpisodicMemoryEmbeddingService {

    @Override
    public List<KbItemEmbeddingDto> listByEmbeddingIds(List<String> embeddingIds) {
        if (embeddingIds == null || embeddingIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<EpisodicMemoryEmbedding> q = new LambdaQueryWrapper<>();
        q.in(EpisodicMemoryEmbedding::getEmbeddingId, embeddingIds.stream().map(UUID::fromString).toList());
        List<EpisodicMemoryEmbedding> rows = baseMapper.selectList(q);
        return toDtos(rows);
    }

    @Override
    public List<KbItemEmbeddingDto> listRecentByCharacter(Long characterId, int limit) {
        List<EpisodicMemoryEmbedding> rows = baseMapper.listRecentByCharacter(characterId, limit);
        return toDtos(rows);
    }

    private List<KbItemEmbeddingDto> toDtos(List<EpisodicMemoryEmbedding> rows) {
        return MPPageUtil.convertToList(rows, KbItemEmbeddingDto.class, (s, t) -> {
            if (s.getEmbedding() != null) {
                t.setEmbedding(s.getEmbedding().toArray());
            }
            return t;
        });
    }
}
