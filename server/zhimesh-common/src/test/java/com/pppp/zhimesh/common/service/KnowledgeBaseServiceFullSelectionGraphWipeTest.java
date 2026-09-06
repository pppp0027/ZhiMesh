package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceFullSelectionGraphWipeTest {

    private KnowledgeBaseService service;
    private KnowledgeBaseMapper mapper;
    private KnowledgeBaseItemService itemService;
    private List<String> wipedKbUuids;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService();
        mapper = mock(KnowledgeBaseMapper.class);
        itemService = mock(KnowledgeBaseItemService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "knowledgeBaseItemService", itemService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(1L);
        knowledgeBase.setUuid("kb-uuid-1");
        knowledgeBase.setOwnerId(7L);
        when(mapper.getByItemUuid(anyString())).thenReturn(knowledgeBase);

        wipedKbUuids = new ArrayList<>();
        doAnswer(invocation -> {
            wipedKbUuids.add(invocation.getArgument(0));
            return null;
        }).when(itemService).cleanupKnowledgeBaseGraph(anyString());
    }

    private void stubItemCount(long count) {
        when(itemService.count(any(Wrapper.class))).thenReturn(count);
    }

    @Test
    void fullSelectionWithGraphicalWipesGraphBeforeScheduling() {
        stubItemCount(2);

        boolean result = service.indexItems(List.of("item-a", "item-b"),
                List.of("graphical", "embedding"));

        assertTrue(result);
        assertEquals(List.of("kb-uuid-1"), wipedKbUuids);
        InOrder order = inOrder(itemService);
        // The wipe must happen before any document task is scheduled, otherwise
        // the first rebuilt document would be cleaned away again by the wipe.
        order.verify(itemService).cleanupKnowledgeBaseGraph("kb-uuid-1");
        order.verify(itemService).checkAndIndexing(any(KnowledgeBase.class), any(), any());
    }

    @Test
    void partialSelectionKeepsIncrementalPath() {
        stubItemCount(2);

        service.indexItems(List.of("item-a"), List.of("graphical"));

        assertTrue(wipedKbUuids.isEmpty());
        verify(itemService).checkAndIndexing(any(KnowledgeBase.class), any(), any());
    }

    @Test
    void fullSelectionWithoutGraphicalDoesNotWipe() {
        stubItemCount(2);

        service.indexItems(List.of("item-a", "item-b"), List.of("embedding", "fulltext"));

        assertTrue(wipedKbUuids.isEmpty());
        verify(itemService).checkAndIndexing(any(KnowledgeBase.class), any(), any());
    }

    @Test
    void duplicateSelectionStillCountsAsFullSelection() {
        stubItemCount(2);

        assertTrue(service.selectsAllKnowledgeBaseItems("kb-uuid-1",
                List.of("item-a", "item-a", "item-b")));
    }

    @Test
    void selectsAllKnowledgeBaseItemsRejectsEmptyAndStaleSelections() {
        stubItemCount(2);

        assertFalse(service.selectsAllKnowledgeBaseItems("kb-uuid-1", List.of()));
        assertFalse(service.selectsAllKnowledgeBaseItems("kb-uuid-1", List.of("item-a")));
        // More selected than items exist cannot happen through indexItems (every
        // uuid resolves to a live item), but the comparison stays false anyway.
        assertFalse(service.selectsAllKnowledgeBaseItems("kb-uuid-1",
                List.of("item-a", "item-b", "item-c")));
    }
}
