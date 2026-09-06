package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryKeyResolver;
import com.pppp.zhimesh.common.vo.ChatContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatContextResolverTest {

    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final ChatContextResolver resolver = new ChatContextResolver();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(resolver, "characterMapper", characterMapper);
        ReflectionTestUtils.setField(resolver, "conversationService", conversationService);
        ReflectionTestUtils.setField(resolver, "memoryKeyResolver", new ShortTermMemoryKeyResolver());
    }

    @Test
    void characterOnlyRequestCreatesDefaultConversationAndUsesConversationMemory() {
        User user = user();
        Character character = character();
        Conversation conversation = new Conversation();
        conversation.setId(20L);
        conversation.setUuid("bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb");
        conversation.setCharacterId(character.getId());
        conversation.setIsDefault(true);
        when(characterMapper.selectOne(any())).thenReturn(character);
        when(conversationService.getOrCreateDefault(user.getId(), character)).thenReturn(conversation);
        AskReq request = new AskReq();
        request.setCharacterUuid(character.getUuid());

        ChatContext context = resolver.resolve(user, request);

        assertThat(context.conversation()).isSameAs(conversation);
        assertThat(context.requestUuid()).isEqualTo(conversation.getUuid());
        assertThat(context.shortTermMemoryId()).isEqualTo("conversation:" + conversation.getUuid());
        verify(conversationService).getOrCreateDefault(user.getId(), character);
    }

    @Test
    void explicitDefaultConversationUsesNamespacedMemory() {
        User user = user();
        Character character = character();
        Conversation conversation = new Conversation();
        conversation.setId(20L);
        conversation.setUuid("bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb");
        conversation.setCharacterId(character.getId());
        conversation.setIsDefault(true);
        when(conversationService.getOwnedOrThrow(user.getId(), conversation.getUuid())).thenReturn(conversation);
        when(characterMapper.selectOne(any())).thenReturn(character);
        AskReq request = new AskReq();
        request.setConversationUuid(conversation.getUuid());

        ChatContext context = resolver.resolve(user, request);

        assertThat(context.character()).isSameAs(character);
        assertThat(context.shortTermMemoryId()).isEqualTo("conversation:" + conversation.getUuid());
    }

    @Test
    void explicitUserConversationUsesTheSameConversationMemory() {
        User user = user();
        Character character = character();
        Conversation conversation = new Conversation();
        conversation.setId(21L);
        conversation.setUuid("cccccccccccc4ccc8ccccccccccccccc");
        conversation.setCharacterId(character.getId());
        conversation.setIsDefault(false);
        when(conversationService.getOwnedOrThrow(user.getId(), conversation.getUuid())).thenReturn(conversation);
        when(characterMapper.selectOne(any())).thenReturn(character);
        AskReq request = new AskReq();
        request.setConversationUuid(conversation.getUuid());

        ChatContext context = resolver.resolve(user, request);

        assertThat(context.conversation()).isSameAs(conversation);
        assertThat(context.requestUuid()).isEqualTo(conversation.getUuid());
        assertThat(context.shortTermMemoryId()).isEqualTo("conversation:" + conversation.getUuid());
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        return user;
    }

    private Character character() {
        Character character = new Character();
        character.setId(10L);
        character.setUuid("aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa");
        character.setUserId(1L);
        return character;
    }
}
