package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkSetMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/** Read-only access for the canonical Chunk schema. It does not participate in indexing or retrieval. */
@Service
public class CanonicalChunkQueryService {

    @Resource
    private KnowledgeBaseChunkSetMapper chunkSetMapper;
    @Resource
    private KnowledgeBaseChunkMapper chunkMapper;

    public List<KnowledgeBaseChunkSet> listChunkSets(String kbItemUuid) {
        return chunkSetMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBaseChunkSet>()
                .eq(KnowledgeBaseChunkSet::getKbItemUuid, kbItemUuid)
                
                .orderByDesc(KnowledgeBaseChunkSet::getId));
    }

    public List<KnowledgeBaseChunk> listChunks(String kbItemUuid, String chunkSetUuid) {
        return chunkMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBaseChunk>()
                .eq(KnowledgeBaseChunk::getKbItemUuid, kbItemUuid)
                .eq(KnowledgeBaseChunk::getChunkSetUuid, chunkSetUuid)
                
                .orderByAsc(KnowledgeBaseChunk::getChunkIndex));
    }
}
