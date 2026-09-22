package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.CharacterMessage;
import com.pppp.zhimesh.common.entity.CharacterMessageToolCall;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.mapper.CharacterMessageMapper;
import com.pppp.zhimesh.common.mapper.CharacterMessageToolCallMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterMessageServiceToolCallCascadeTest {

    private CharacterMessageService messageService;
    private CharacterMessageMapper messageMapper;
    private CharacterMessageToolCallMapper toolCallMapper;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 的列解析需要实体元数据（脱离容器时手动预置）
        // LambdaQueryWrapper column resolution needs entity metadata (preset manually outside the container)
        MybatisTableInfoTestSupport.init(CharacterMessage.class, CharacterMessageToolCall.class);
    }

    @BeforeEach
    void setUp() {
        messageService = new CharacterMessageService();
        ReflectionTestUtils.setField(messageService, "entityClass", CharacterMessage.class);
        messageMapper = mock(CharacterMessageMapper.class);
        ReflectionTestUtils.setField(messageService, "baseMapper", messageMapper);
        toolCallMapper = mock(CharacterMessageToolCallMapper.class);
        ReflectionTestUtils.setField(messageService, "characterMessageToolCallMapper", toolCallMapper);

        User user = new User();
        user.setId(79L);
        ThreadContext.setCurrentUser(user);
    }

    @AfterEach
    void clearThreadContext() {
        ThreadContext.setCurrentUser(null);
    }

    @Test
    void softDeleteByConversationIdsCascadesToolCallRows() {
        // 消息(11,12)被按会话删除时，其 adi_character_message_tool_call 行必须一并删除：
        // 表按约定无外键，孤儿轨迹会随角色删除无限累积
        // <p>
        // When messages (11,12) are removed by conversation, their
        // adi_character_message_tool_call rows must be deleted too: the table has
        // no FK by convention, and orphaned traces would accumulate on every
        // character deletion
        when(messageMapper.selectList(any())).thenReturn(List.of(message(11L), message(12L)));
        when(messageMapper.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(2);

        boolean removed = messageService.softDeleteByConversationIds(79L, List.of(501L));

        assertThat(removed).isTrue();
        ArgumentCaptor<LambdaQueryWrapper<CharacterMessageToolCall>> captor = cascadeCaptor();
        verify(toolCallMapper, times(1)).delete(captor.capture());
        // MP 的 in() 惰性求值：参数在 getSqlSegment() 渲染时才落入 paramNameValuePairs
        // MP's in() is lazy: params land in paramNameValuePairs only on getSqlSegment() rendering
        assertThat(captor.getValue().getSqlSegment()).contains("message_id");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(11L, 12L);
    }

    @Test
    void singleMessageSoftDeleteCascadesItsToolCallRows() {
        // 用户删除单条消息（消息级删除入口）同样不能留下孤儿轨迹
        // Deleting a single message (message-level entry) must not leave orphan
        // traces behind either
        when(messageMapper.selectList(any())).thenReturn(List.of(message(33L)));
        when(messageMapper.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        boolean removed = messageService.softDelete("msg-uuid-33");

        assertThat(removed).isTrue();
        ArgumentCaptor<LambdaQueryWrapper<CharacterMessageToolCall>> captor = cascadeCaptor();
        verify(toolCallMapper, times(1)).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("message_id");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(33L);
    }

    @Test
    void noCascadeWhenNothingRemoved() {
        // 会话下已无消息：remove 返回 false，不触发轨迹删除
        // No messages left under the conversation: remove returns false and no
        // trace deletion fires
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(messageMapper.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);

        boolean removed = messageService.softDeleteByConversationIds(79L, List.of(502L));

        assertThat(removed).isFalse();
        verify(toolCallMapper, never()).delete(any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaQueryWrapper<CharacterMessageToolCall>> cascadeCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
    }

    private static CharacterMessage message(long id) {
        CharacterMessage message = new CharacterMessage();
        message.setId(id);
        return message;
    }
}
