package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.dto.RefEmbeddingDto;
import com.pppp.zhimesh.common.entity.CharacterMessageRefEmbedding;
import com.pppp.zhimesh.common.mapper.CharacterMessageRefEmbeddingMapper;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CharacterMessageRefEmbeddingServiceTest {

    @Test
    void loadsReferencedContentFromKnowledgeEmbeddingStore() {
        CharacterMessageRefEmbeddingService service = new CharacterMessageRefEmbeddingService();
        CharacterMessageRefEmbeddingMapper mapper = mock(CharacterMessageRefEmbeddingMapper.class);
        IKnowledgeEmbeddingService knowledgeEmbeddingService = mock(IKnowledgeEmbeddingService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "knowledgeEmbeddingService", knowledgeEmbeddingService);

        CharacterMessageRefEmbedding firstRef = reference("knowledge-vector-1");
        CharacterMessageRefEmbedding secondRef = reference("knowledge-vector-2");
        when(mapper.listByMsgUuid("answer-uuid")).thenReturn(List.of(firstRef, secondRef));
        when(knowledgeEmbeddingService.listByEmbeddingIds(
                List.of("knowledge-vector-1", "knowledge-vector-2")))
                .thenReturn(List.of(
                        embedding("knowledge-vector-1", "first referenced passage"),
                        embedding("knowledge-vector-2", "second referenced passage")));

        List<RefEmbeddingDto> result = service.listRefEmbeddings("answer-uuid");

        assertThat(result)
                .extracting(RefEmbeddingDto::getEmbeddingId)
                .containsExactly("knowledge-vector-1", "knowledge-vector-2");
        assertThat(result)
                .extracting(RefEmbeddingDto::getText)
                .containsExactly("first referenced passage", "second referenced passage");
        verify(knowledgeEmbeddingService).listByEmbeddingIds(
                List.of("knowledge-vector-1", "knowledge-vector-2"));
    }

    @Test
    void doesNotQueryKnowledgeStoreWhenMessageHasNoVectorReferences() {
        CharacterMessageRefEmbeddingService service = new CharacterMessageRefEmbeddingService();
        CharacterMessageRefEmbeddingMapper mapper = mock(CharacterMessageRefEmbeddingMapper.class);
        IKnowledgeEmbeddingService knowledgeEmbeddingService = mock(IKnowledgeEmbeddingService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "knowledgeEmbeddingService", knowledgeEmbeddingService);
        when(mapper.listByMsgUuid("answer-without-reference")).thenReturn(List.of());

        List<RefEmbeddingDto> result = service.listRefEmbeddings("answer-without-reference");

        assertThat(result).isEmpty();
        verifyNoInteractions(knowledgeEmbeddingService);
    }

    private static CharacterMessageRefEmbedding reference(String embeddingId) {
        CharacterMessageRefEmbedding reference = new CharacterMessageRefEmbedding();
        reference.setEmbeddingId(embeddingId);
        return reference;
    }

    private static KbItemEmbeddingDto embedding(String embeddingId, String text) {
        return KbItemEmbeddingDto.builder()
                .embeddingId(embeddingId)
                .text(text)
                .build();
    }
}
