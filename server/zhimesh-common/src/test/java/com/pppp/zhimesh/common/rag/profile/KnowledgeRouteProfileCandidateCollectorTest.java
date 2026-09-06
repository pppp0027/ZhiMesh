package com.pppp.zhimesh.common.rag.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRouteProfileCandidateCollectorTest {

    @Test
    void candidatePoolAndCandidateTextRemainStrictlyBounded() {
        KnowledgeBaseChunkMapper chunkMapper = mock(KnowledgeBaseChunkMapper.class);
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getKnowledgeScopeGate().setCandidatePoolLimit(4);
        properties.getKnowledgeScopeGate().setProfileTextMaxChars(64);
        KnowledgeRouteProfileCandidateCollector collector =
                new KnowledgeRouteProfileCandidateCollector(chunkMapper, new ObjectMapper(), properties);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUuid("kb-a");
        kb.setTitle("制度知识库");
        kb.setRemark("差旅、采购与报销制度");
        List<KnowledgeBaseItem> items = List.of(item("item-a"), item("item-b"), item("item-c"));
        when(chunkMapper.selectRouteProfileSamples("kb-a", 2))
                .thenReturn(List.of(
                        chunk("chunk-a", "这是一段足够长且不同的代表性主题内容".repeat(10)),
                        chunk("chunk-b", "另一个关于采购审批和供应商管理的主题".repeat(10))));

        List<KnowledgeRouteProfileCandidate> candidates = collector.collect(kb, items);

        assertThat(candidates).hasSize(4);
        assertThat(candidates.get(0).type()).isEqualTo("OVERVIEW");
        assertThat(candidates).extracting(candidate -> candidate.text().length())
                .allMatch(length -> length <= 64);
        assertThat(candidates).extracting(KnowledgeRouteProfileCandidate::key)
                .doesNotHaveDuplicates();
    }

    private static KnowledgeBaseItem item(String uuid) {
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setUuid(uuid);
        item.setTitle("文档-" + uuid);
        item.setBrief("摘要-" + uuid);
        return item;
    }

    private static KnowledgeBaseChunk chunk(String uuid, String content) {
        KnowledgeBaseChunk chunk = new KnowledgeBaseChunk();
        chunk.setUuid(uuid);
        chunk.setContent(content);
        return chunk;
    }
}
