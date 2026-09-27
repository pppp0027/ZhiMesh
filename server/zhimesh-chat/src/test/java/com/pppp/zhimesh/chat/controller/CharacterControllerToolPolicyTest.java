package com.pppp.zhimesh.chat.controller;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.CharacterAddReq;
import com.pppp.zhimesh.common.dto.CharacterEditReq;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.service.CharacterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 用户端不可写 tool_policy：角色工具策略只经预设实例化（服务端复制）或管理端下发，
 * 用户端 add/edit 接口在 Controller 入口剥离该字段，防止绕过前端直写 API 篡改默认工具策略。
 */
class CharacterControllerToolPolicyTest {
    private final CharacterController controller = new CharacterController();
    private final CharacterService characterService = mock(CharacterService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "characterService", characterService);
        User user = new User();
        user.setId(1L);
        ThreadContext.setCurrentUser(user);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
    }

    @Test
    void addStripsToolPolicyBeforeDelegatingToService() {
        CharacterAddReq req = new CharacterAddReq();
        req.setTitle("test");
        req.setAiSystemMessage("test");
        req.setToolPolicy("{\"builtinDenylist\":[\"search_knowledge\"]}");

        controller.add(req);

        ArgumentCaptor<CharacterAddReq> captor = ArgumentCaptor.forClass(CharacterAddReq.class);
        verify(characterService).add(captor.capture());
        assertThat(captor.getValue().getToolPolicy()).isNull();
    }

    @Test
    void editStripsToolPolicyBeforeDelegatingToService() {
        CharacterEditReq req = new CharacterEditReq();
        req.setToolPolicy("{\"approvalRequiredMcpTools\":[\"demo_tool\"]}");

        controller.edit("uuid-1234", req);

        ArgumentCaptor<CharacterEditReq> captor = ArgumentCaptor.forClass(CharacterEditReq.class);
        verify(characterService).editOwnedByCurrentUser(eq("uuid-1234"), captor.capture());
        assertThat(captor.getValue().getToolPolicy()).isNull();
    }
}
