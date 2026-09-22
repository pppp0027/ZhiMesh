package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseQa;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseQaRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 清空问答历史的范围契约：删除条件必须同时携带 userId 与 kbId。
 * 只按 user_id 删除会波及当前用户其他知识库的记录（数据损失）。
 */
class KnowledgeBaseQaServiceClearTest {

    private KnowledgeBaseQaService service;
    private KnowledgeBaseQaRecordMapper qaRecordMapper;

    private User member;
    private KnowledgeBase teamKb;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        // 每个实体独立的 MapperBuilderAssistant：namespace 只能设置一次
        MybatisTableInfoTestSupport.init(KnowledgeBaseQa.class);
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseQaService();
        qaRecordMapper = mock(KnowledgeBaseQaRecordMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", qaRecordMapper);
        ReflectionTestUtils.setField(service, "entityClass", KnowledgeBaseQa.class);

        member = new User();
        member.setId(1L);
        ThreadContext.setCurrentUser(member);

        teamKb = new KnowledgeBase();
        teamKb.setId(100L);
        teamKb.setUuid("kb-uuid");
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
    }

    @Test
    void clearByCurrentUserScopesDeleteToUserAndKnowledgeBase() {
        service.clearByCurrentUser(teamKb);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KnowledgeBaseQa>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(qaRecordMapper).delete(captor.capture());
        LambdaQueryWrapper<KnowledgeBaseQa> wrapper =
                (LambdaQueryWrapper<KnowledgeBaseQa>) captor.getValue();

        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("user_id"));
        assertTrue(sqlSegment.contains("kb_id"));

        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(100L));
    }
}
