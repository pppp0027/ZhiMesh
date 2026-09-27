package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.mapper.ConversationMapper;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryCleanupTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话删除对挂起检查点的级联验证（T4 第 7 项）：softDelete 与 softDeleteByCharacter
 * 都必须把该会话的 ACTIVE 检查点置 DELETED，防孤儿 ACTIVE 让已删会话的下一轮消息
 * 误入恢复流程；删除本身失败时不级联。
 * <p>
 * Conversation-deletion cascade over pending checkpoints (T4 item 7): both
 * softDelete and softDeleteByCharacter must flip the conversation's ACTIVE
 * checkpoints to DELETED, preventing a dangling ACTIVE from pushing the next
 * message of a deleted conversation into the resume path; no cascade when the
 * deletion itself fails.
 */
class ConversationServicePendingCascadeTest {

    private ConversationService conversationService;
    private ConversationMapper conversationMapper;
    private CharacterMapper characterMapper;
    private CharacterMessageService characterMessageService;
    private PendingCheckpointService pendingCheckpointService;
    private ShortTermMemoryCleanupTask cleanupTask;

    @BeforeAll
    static void initTableInfo() {
        MybatisTableInfoTestSupport.init(Conversation.class);
    }

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService();
        ReflectionTestUtils.setField(conversationService, "entityClass", Conversation.class);
        conversationMapper = mock(ConversationMapper.class);
        ReflectionTestUtils.setField(conversationService, "baseMapper", conversationMapper);
        characterMapper = mock(CharacterMapper.class);
        ReflectionTestUtils.setField(conversationService, "characterMapper", characterMapper);
        characterMessageService = mock(CharacterMessageService.class);
        ReflectionTestUtils.setField(conversationService, "characterMessageService", characterMessageService);
        pendingCheckpointService = mock(PendingCheckpointService.class);
        ReflectionTestUtils.setField(conversationService, "pendingCheckpointService", pendingCheckpointService);
        cleanupTask = mock(ShortTermMemoryCleanupTask.class);
        ReflectionTestUtils.setField(conversationService, "shortTermMemoryCleanupTask", cleanupTask);
    }

    @Test
    void softDeleteCascadesPendingCheckpointsToDeleted() {
        Conversation conversation = conversation(301L, "conv-uuid-301");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(conversationMapper.deleteById(301L)).thenReturn(1);

        boolean deleted = conversationService.softDelete(7L, "conv-uuid-301");

        assertThat(deleted).isTrue();
        verify(pendingCheckpointService).markDeletedByConversation(301L);
        verify(characterMessageService).softDeleteByConversationIds(7L, List.of(301L));
    }

    @Test
    void softDeleteSkipsCascadeWhenRemovalFails() {
        Conversation conversation = conversation(302L, "conv-uuid-302");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(conversationMapper.deleteById(302L)).thenReturn(0);

        boolean deleted = conversationService.softDelete(7L, "conv-uuid-302");

        assertThat(deleted).isFalse();
        verify(pendingCheckpointService, never()).markDeletedByConversation(any());
    }

    @Test
    void softDeleteByCharacterCascadesEveryConversationCheckpoint() {
        Character character = new Character();
        character.setId(55L);
        character.setUuid("char-uuid-55");
        when(characterMapper.selectById(55L)).thenReturn(character);
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                conversation(301L, "conv-uuid-301"), conversation(303L, "conv-uuid-303")));
        when(conversationMapper.delete(any())).thenReturn(2);

        conversationService.softDeleteByCharacter(7L, 55L);

        verify(pendingCheckpointService).markDeletedByConversation(301L);
        verify(pendingCheckpointService).markDeletedByConversation(303L);
        verify(characterMessageService).softDeleteByConversationIds(7L, List.of(301L, 303L));
        verify(cleanupTask).deleteCharacter(anyString(), anyList());
    }

    private static Conversation conversation(Long id, String uuid) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUuid(uuid);
        conversation.setUserId(7L);
        conversation.setCharacterId(55L);
        return conversation;
    }
}
