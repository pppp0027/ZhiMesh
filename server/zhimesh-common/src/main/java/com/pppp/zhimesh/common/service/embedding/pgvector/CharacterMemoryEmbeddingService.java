package com.pppp.zhimesh.common.service.embedding.pgvector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.entity.CharacterMemoryEmbedding;
import com.pppp.zhimesh.common.mapper.CharacterMemoryEmbeddingMapper;
import com.pppp.zhimesh.common.service.embedding.ICharacterMemoryEmbeddingService;
import com.pppp.zhimesh.common.util.MPPageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(value = "zhimesh.vector-database", havingValue = "pgvector")
public class CharacterMemoryEmbeddingService extends ServiceImpl<CharacterMemoryEmbeddingMapper, CharacterMemoryEmbedding> implements ICharacterMemoryEmbeddingService {

    @Override
    public List<KbItemEmbeddingDto> listByEmbeddingIds(List<String> embeddingIds) {
        LambdaQueryWrapper<CharacterMemoryEmbedding> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(CharacterMemoryEmbedding::getEmbeddingId, embeddingIds.stream().map(UUID::fromString).toList());
        List<CharacterMemoryEmbedding> embeddingList = baseMapper.selectList(lambdaQueryWrapper);
        return MPPageUtil.convertToList(embeddingList, KbItemEmbeddingDto.class, (s, t) -> {
            t.setEmbedding(s.getEmbedding().toArray());
            return t;
        });
    }

}
