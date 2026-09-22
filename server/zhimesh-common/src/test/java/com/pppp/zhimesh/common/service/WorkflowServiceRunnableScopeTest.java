package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.Workflow;
import com.pppp.zhimesh.common.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * run_workflow 可见口径的安全性验证：listRunnableForUser 的查询必须同时携带
 * is_enable 过滤与 (user_id=当前用户 OR is_public=true) 的 mine+public 分组——
 * 绝不能退化成全库查询（无提权）；无用户上下文时直接返回空且零查询。
 * <p>
 * Security verification of the run_workflow visibility scope: the
 * listRunnableForUser query must carry both the is_enable filter and the
 * (user_id = current user OR is_public = true) mine+public grouping — it must
 * never degrade into a full-library query (no privilege escalation); a missing
 * user context yields an empty list with zero queries.
 */
class WorkflowServiceRunnableScopeTest {

    private WorkflowService service;
    private WorkflowMapper workflowMapper;
    private User normalUser;
    private User adminUser;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        MybatisTableInfoTestSupport.init(Workflow.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkflowService();
        workflowMapper = mock(WorkflowMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", workflowMapper);
        ReflectionTestUtils.setField(service, "entityClass", Workflow.class);

        normalUser = new User();
        normalUser.setId(7L);
        normalUser.setUuid("user-uuid-7");
        normalUser.setIsAdmin(false);
        normalUser.setLocale("zh-CN");

        adminUser = new User();
        adminUser.setId(9L);
        adminUser.setUuid("admin-uuid-9");
        adminUser.setIsAdmin(true);
        adminUser.setLocale("zh-CN");

        when(workflowMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void runnableScopeAlwaysFiltersByMineOrPublicAndEnabled() {
        for (User who : List.of(normalUser, adminUser)) {
            service.listRunnableForUser(who, 100);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Workflow>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowMapper, times(2)).selectList(wrapperCaptor.capture());

        // 普通用户与管理员同一口径：mine ∪ public 且必须启用——管理员也不例外，
        // 绝不出现无 user 条件的全库查询
        // Both regular users and admins share the same scope: mine ∪ public
        // and enabled — never a full-library query without the user condition
        for (LambdaQueryWrapper<Workflow> wrapper : wrapperCaptor.getAllValues()) {
            String sqlSegment = wrapper.getSqlSegment();
            assertThat(sqlSegment)
                    .contains("is_enable")
                    .contains("user_id")
                    .contains("is_public");
        }
    }

    @Test
    void missingUserContextYieldsEmptyListWithoutQuerying() {
        assertThat(service.listRunnableForUser(null, 100)).isEmpty();
        assertThat(service.listRunnableForUser(new User(), 100)).isEmpty();

        verifyNoInteractions(workflowMapper);
    }
}
