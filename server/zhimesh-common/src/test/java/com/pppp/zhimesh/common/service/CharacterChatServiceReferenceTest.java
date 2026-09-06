package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.rag.DeduplicatingContentRetriever;
import com.pppp.zhimesh.common.rag.ZhiMeshEmbeddingStoreContentRetriever;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterChatServiceReferenceTest {

    @Test
    void persistsSemanticAndEpisodicRefsFromDeduplicatingWrappers() {
        CharacterChatService chatService = new CharacterChatService();
        CharacterMessageService messageService = mock(CharacterMessageService.class);
        ReflectionTestUtils.setField(chatService, "characterMessageService", messageService);
        User user = new User();
        user.setId(7L);

        RetrieverWrapper semantic = memoryWrapper(
                ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY,
                Map.of("semantic-id", 0.88D));
        RetrieverWrapper episodic = memoryWrapper(
                ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC,
                Map.of("episodic-id", 0.86D));

        ReflectionTestUtils.invokeMethod(chatService, "createRef",
                List.of(semantic, episodic), user, 99L);

        verify(messageService).createMemoryRefs(
                user, 99L, Map.of("semantic-id", 0.88D), MemoryType.SEMANTIC);
        verify(messageService).createMemoryRefs(
                user, 99L, Map.of("episodic-id", 0.86D), MemoryType.EPISODIC);
    }

    private static RetrieverWrapper memoryWrapper(String contentFrom, Map<String, Double> refs) {
        ZhiMeshEmbeddingStoreContentRetriever source =
                mock(ZhiMeshEmbeddingStoreContentRetriever.class);
        when(source.getRetrievedEmbeddingToScore()).thenReturn(refs);
        DeduplicatingContentRetriever merged = new DeduplicatingContentRetriever(
                List.of(source), null, 1, false, null,
                new ZhiMeshProperties.Retrieval());
        return RetrieverWrapper.builder()
                .contentFrom(contentFrom)
                .retriever(merged)
                .build();
    }
}
