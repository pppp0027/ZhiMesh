package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.memory.vo.BatchActionMemories;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.CHARACTER_ID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.LAST_SOURCE_MESSAGE_ID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.MEMORY_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticMemoryServiceTest {

    @Mock
    private EmbeddingStore<TextSegment> store;
    @Mock
    private EmbeddingModel embeddingModel;

    private SemanticMemoryService service;

    @BeforeEach
    void setUp() {
        service = new SemanticMemoryService();
        ReflectionTestUtils.setField(service, "semanticEmbeddingStore", store);
        ReflectionTestUtils.setField(service, "embeddingModel", embeddingModel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateReembedsAndAtomicallyUpsertsTheSameId() {
        Embedding oldEmbedding = Embedding.from(new float[]{1F, 0F});
        Embedding newEmbedding = Embedding.from(new float[]{0F, 1F});
        when(embeddingModel.embed("喜欢川菜和重庆火锅")).thenReturn(Response.from(newEmbedding));
        SemanticMemoryService.MergeCandidates candidates = candidates(
                "喜欢吃辣", "memory-id", oldEmbedding, 100L);

        service.applyActions(actions("喜欢吃辣", "0", "喜欢川菜和重庆火锅", "UPDATE"),
                10L, 7L, 101L, candidates);

        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Embedding>> embeddings = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TextSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(ids.capture(), embeddings.capture(), segments.capture());
        verify(store, never()).remove("memory-id");
        assertThat(ids.getValue()).containsExactly("memory-id");
        assertThat(embeddings.getValue()).containsExactly(newEmbedding);
        assertThat(segments.getValue().get(0).text()).isEqualTo("喜欢川菜和重庆火锅");
        assertThat(segments.getValue().get(0).metadata().toMap())
                .containsEntry(CHARACTER_ID, 10L)
                .containsEntry(MEMORY_TYPE, MemoryType.SEMANTIC.getDesc())
                .containsEntry(LAST_SOURCE_MESSAGE_ID, 101L);
    }

    @Test
    void olderAsyncTaskCannotOverwriteANewerSemanticMemory() {
        SemanticMemoryService.MergeCandidates candidates = candidates(
                "喜欢吃辣", "memory-id", Embedding.from(new float[]{1F, 0F}), 200L);

        service.applyActions(actions("喜欢吃辣", "0", "只喜欢微辣", "UPDATE"),
                10L, 7L, 199L, candidates);

        verify(embeddingModel, never()).embed("只喜欢微辣");
        verify(store, never()).addAll(anyList(), anyList(), anyList());
        verify(store, never()).remove("memory-id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addPersistsOwnershipAndSourceGenerationWithTheFreshVector() {
        Embedding embedding = Embedding.from(new float[]{0.5F, 0.5F});
        when(embeddingModel.embed("偏好简洁回答")).thenReturn(Response.from(embedding));

        service.applyActions(actions("偏好简洁回答", null, "偏好简洁回答", "ADD"),
                10L, 7L, 101L, new SemanticMemoryService.MergeCandidates(
                        Map.of(), Map.of(), Map.of()));

        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Embedding>> embeddings = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TextSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(ids.capture(), embeddings.capture(), segments.capture());
        assertThat(ids.getValue().get(0)).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(embeddings.getValue()).containsExactly(embedding);
        assertThat(segments.getValue().get(0).metadata().toMap())
                .containsEntry(CHARACTER_ID, 10L)
                .containsEntry(LAST_SOURCE_MESSAGE_ID, 101L);
    }

    private static SemanticMemoryService.MergeCandidates candidates(
            String fact, String embeddingId, Embedding embedding, long sourceMessageId) {
        TextSegment segment = TextSegment.from("喜欢吃辣", new Metadata(Map.of(
                CHARACTER_ID, 10L,
                MEMORY_TYPE, MemoryType.SEMANTIC.getDesc(),
                LAST_SOURCE_MESSAGE_ID, sourceMessageId)));
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9D, embeddingId, embedding, segment);
        Map<String, List<Map<String, String>>> prompt = new LinkedHashMap<>();
        prompt.put(fact, List.of(Map.of("id", "0", "text", segment.text())));
        return new SemanticMemoryService.MergeCandidates(
                prompt,
                Map.of(fact, Map.of("0", embeddingId)),
                Map.of(fact, Map.of(embeddingId, match)));
    }

    private static BatchActionMemories actions(String fact, String id, String text, String event) {
        BatchActionMemories.BatchActionMemory action = new BatchActionMemories.BatchActionMemory();
        action.setFact(fact);
        action.setId(id);
        action.setText(text);
        action.setEvent(event);
        BatchActionMemories result = new BatchActionMemories();
        result.setActions(List.of(action));
        return result;
    }
}
