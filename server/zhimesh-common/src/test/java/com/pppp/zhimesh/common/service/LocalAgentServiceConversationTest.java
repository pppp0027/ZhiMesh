package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryKeyResolver;
import com.pppp.zhimesh.common.vo.AgentRequest;
import com.pppp.zhimesh.common.util.SpringUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalAgentServiceConversationTest {
    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final LocalAgentService service = new LocalAgentService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "characterMapper", characterMapper);
        ReflectionTestUtils.setField(service, "conversationService", conversationService);
        ReflectionTestUtils.setField(service, "shortTermMemoryKeyResolver", new ShortTermMemoryKeyResolver());
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(applicationContext.getBean(MessageSource.class)).thenReturn(messageSource);
        when(messageSource.getMessage(anyString(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        new SpringUtil().setApplicationContext(applicationContext);
    }

    @Test
    void characterLookupUsesOwnedCharacterQueryAndRejectsMissingCharacter() {
        User user = user();
        Character character = character();
        when(characterMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(character);

        Character result = service.findOwnedCharacter(
                AgentRequest.builder().characterUuid(character.getUuid()).build(), user);

        assertThat(result).isSameAs(character);
        verify(characterMapper).selectOne(any(LambdaQueryWrapper.class));

        when(characterMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.findOwnedCharacter(
                AgentRequest.builder().characterUuid(character.getUuid()).build(), user))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void workflowRequestWithoutConversationIsStateless() {
        assertThat(service.resolveMemoryId(AgentRequest.builder().build(), user(), character())).isNull();
    }

    @Test
    void explicitConversationUsesValidatedNamespacedMemory() {
        User user = user();
        Character character = character();
        Conversation conversation = conversation(character.getId());
        when(conversationService.getOwnedOrThrow(user.getId(), conversation.getUuid())).thenReturn(conversation);

        String result = service.resolveMemoryId(
                AgentRequest.builder().conversationUuid(conversation.getUuid()).build(), user, character);

        assertThat(result).isEqualTo("conversation:" + conversation.getUuid());
    }

    @Test
    void conversationAndCharacterMustMatch() {
        User user = user();
        Character character = character();
        Conversation conversation = conversation(999L);
        when(conversationService.getOwnedOrThrow(user.getId(), conversation.getUuid())).thenReturn(conversation);

        assertThatThrownBy(() -> service.resolveMemoryId(
                AgentRequest.builder().conversationUuid(conversation.getUuid()).build(), user, character))
                .isInstanceOf(BaseException.class);
    }

    private User user() { User user = new User(); user.setId(7L); return user; }
    private Character character() { Character c = new Character(); c.setId(11L); c.setUuid("aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa"); return c; }
    private Conversation conversation(long characterId) { Conversation c = new Conversation(); c.setUuid("bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb"); c.setCharacterId(characterId); return c; }
}
