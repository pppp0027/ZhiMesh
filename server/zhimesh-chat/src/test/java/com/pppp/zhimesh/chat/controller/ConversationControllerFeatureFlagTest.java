package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.conversation.ConversationDto;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerFeatureFlagTest {
    private final ConversationController controller = new ConversationController();
    private final ConversationService conversationService = mock(ConversationService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "conversationService", conversationService);
        User user = new User();
        user.setId(1L);
        ThreadContext.setCurrentUser(user);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
    }

    @Test
    void listDelegatesToConversationService() {
        Page<ConversationDto> expected = new Page<>();
        when(conversationService.listByUser(1L, 1, 20)).thenReturn(expected);

        assertThat(controller.list(1, 20)).isSameAs(expected);
        verify(conversationService).listByUser(1L, 1, 20);
    }
}
