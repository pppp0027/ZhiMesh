package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterServiceConversationHistoryTest {

    private final CharacterService characterService = new CharacterService();
    private final ConversationService conversationService = mock(ConversationService.class);
    private final ConversationBackfillService backfillService = mock(ConversationBackfillService.class);
    private final CharacterMessageService messageService = mock(CharacterMessageService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(characterService, "conversationService", conversationService);
        ReflectionTestUtils.setField(characterService, "conversationBackfillService", backfillService);
        ReflectionTestUtils.setField(characterService, "characterMessageService", messageService);
    }

    @Test
    void defaultConversationBackfillsLegacyMessagesBeforeReading() {
        Conversation conversation = new Conversation();
        conversation.setId(20L);
        conversation.setCharacterId(10L);
        conversation.setIsDefault(true);
        when(conversationService.getOwnedOrThrow(1L, "conversation-uuid")).thenReturn(conversation);
        when(messageService.listQuestionsByConversationId(20L, Long.MAX_VALUE, 20))
                .thenReturn(Collections.emptyList());

        characterService.detailByConversation(1L, "conversation-uuid", "", 20);

        verify(backfillService).backfillCharacter(1L, 10L);
        verify(messageService).listQuestionsByConversationId(20L, Long.MAX_VALUE, 20);
    }
}
