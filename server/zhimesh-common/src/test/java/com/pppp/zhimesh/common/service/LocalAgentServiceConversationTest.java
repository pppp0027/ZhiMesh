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
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class LocalAgentServiceConversationTest {
    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final UserDayCostService userDayCostService = mock(UserDayCostService.class);
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
        when(applicationContext.getBean(UserDayCostService.class)).thenReturn(userDayCostService);
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

    @Test
    void agentNodeLlmCostIsChargedToInitiatingUser() {
        User user = user();
        // 免费模型 + total 齐全：按 total 计
        // Free model with total present: charged by the total
        service.appendLlmCostToUserSafely(user, llmService(true),
                new TokenUsage(300, 100, 400));
        verify(userDayCostService).appendCostToUser(user, 400, true);

        // 付费模型 + total 缺省：回退 input+output
        // Paid model without total: falls back to input + output
        service.appendLlmCostToUserSafely(user, llmService(false),
                new TokenUsage(250, 125, null));
        verify(userDayCostService).appendCostToUser(user, 375, false);

        // 空用量不入账
        // Null usage charges nothing
        service.appendLlmCostToUserSafely(user, llmService(true), null);
        verify(userDayCostService, times(2)).appendCostToUser(any(), anyInt(), anyBoolean());
    }

    @Test
    void ledgerFailureNeverBreaksTheAgentNode() {
        doThrow(new RuntimeException("ledger down"))
                .when(userDayCostService).appendCostToUser(any(), anyInt(), anyBoolean());

        service.appendLlmCostToUserSafely(user(), llmService(true), new TokenUsage(10, 5, 15));
        // 吞掉账本异常：节点回答不受影响
        // Ledger exception is swallowed: the node answer is unaffected
        verify(userDayCostService).appendCostToUser(any(), anyInt(), anyBoolean());
    }

    private com.pppp.zhimesh.common.languagemodel.AbstractLLMService llmService(boolean isFree) {
        com.pppp.zhimesh.common.languagemodel.AbstractLLMService llmService =
                mock(com.pppp.zhimesh.common.languagemodel.AbstractLLMService.class);
        com.pppp.zhimesh.common.entity.AiModel aiModel = new com.pppp.zhimesh.common.entity.AiModel();
        aiModel.setIsFree(isFree);
        when(llmService.getAiModel()).thenReturn(aiModel);
        return llmService;
    }

    private User user() { User user = new User(); user.setId(7L); return user; }
    private Character character() { Character c = new Character(); c.setId(11L); c.setUuid("aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa"); return c; }
    private Conversation conversation(long characterId) { Conversation c = new Conversation(); c.setUuid("bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb"); c.setCharacterId(characterId); return c; }
}
