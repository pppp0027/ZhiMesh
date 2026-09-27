package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.AgentPendingCheckpoint;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.enums.PendingCheckpointStatus;
import com.pppp.zhimesh.common.languagemodel.tool.ApprovalGrant;
import com.pppp.zhimesh.common.languagemodel.tool.ChatMessageSnapshotCodec;
import com.pppp.zhimesh.common.mapper.AgentPendingCheckpointMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂起检查点状态机单测：惰性 TTL 过期、新挂起覆盖旧 ACTIVE（先 UPDATE 后 INSERT）、
 * consume 条件迁移幂等、会话删除级联。全部脱离数据库，沿用 MP 单测模式
 * （MapperBuilderAssistant 每实体一个 + entityClass/baseMapper 预置）。
 * <p>
 * Unit tests for the pending-checkpoint state machine: lazy TTL expiry,
 * create-supersedes (UPDATE-then-INSERT), idempotent conditional consume, and
 * the conversation-deletion cascade. All DB-free, following the house MP test
 * pattern (one MapperBuilderAssistant per entity + entityClass/baseMapper
 * preset via ReflectionTestUtils).
 */
class PendingCheckpointServiceTest {

    private PendingCheckpointService checkpointService;
    private AgentPendingCheckpointMapper checkpointMapper;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper/LambdaUpdateWrapper 的列解析需要实体元数据（脱离容器时手动预置）
        // Column resolution for lambda wrappers needs entity metadata (preset manually outside the container)
        MybatisTableInfoTestSupport.init(AgentPendingCheckpoint.class);
    }

    @BeforeEach
    void setUp() {
        checkpointService = new PendingCheckpointService();
        ReflectionTestUtils.setField(checkpointService, "entityClass", AgentPendingCheckpoint.class);
        // 用非默认 TTL（2h）注入，证明惰性过期读的是 zhimesh.agent.pending-ttl-hours 配置
        ZhiMeshProperties properties = new ZhiMeshProperties();
        properties.getAgent().setPendingTtlHours(2);
        ReflectionTestUtils.setField(checkpointService, "adiProperties", properties);
        checkpointMapper = mock(AgentPendingCheckpointMapper.class);
        ReflectionTestUtils.setField(checkpointService, "baseMapper", checkpointMapper);
    }

    @Test
    void findActiveLazilyExpiresStaleCheckpointAndReturnsNull() {
        // created_at 超过 TTL（注入值为 2h）的 ACTIVE：读取时置 EXPIRED 且返回空，新消息走正常流程
        AgentPendingCheckpoint stale = activeRow(9L, 5L);
        stale.setCreatedAt(LocalDateTime.now().minusHours(3));
        when(checkpointMapper.selectOne(any())).thenReturn(stale);
        when(checkpointMapper.update(any(), any())).thenReturn(1);

        AgentPendingCheckpoint found = checkpointService.findActive(5L);

        assertThat(found).isNull();
        ArgumentCaptor<LambdaUpdateWrapper<AgentPendingCheckpoint>> captor = updateCaptor();
        verify(checkpointMapper).update(isNull(), captor.capture());
        // 条件迁移必须带上 id + status=ACTIVE 守卫，且目标是 EXPIRED
        assertThat(captor.getValue().getSqlSegment()).contains("id", "status");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(9L, PendingCheckpointStatus.ACTIVE.getCode(), PendingCheckpointStatus.EXPIRED.getCode());
    }

    @Test
    void findActiveWithinTtlReturnsCheckpointWithoutTouchingIt() {
        // TTL（注入值 2h）内的 ACTIVE：原样返回，不产生任何 UPDATE
        AgentPendingCheckpoint fresh = activeRow(9L, 5L);
        fresh.setCreatedAt(LocalDateTime.now().minusHours(1));
        when(checkpointMapper.selectOne(any())).thenReturn(fresh);

        assertThat(checkpointService.findActive(5L)).isSameAs(fresh);
        verify(checkpointMapper, never()).update(any(), any());
    }

    @Test
    void findActiveFallsBackToDefaultTtlWhenPropertiesUnavailable() {
        // 配置 bean 缺失（脱离容器的防御路径）：按兜底 TTL（24h）判定——25h 前的行过期
        PendingCheckpointService bareService = new PendingCheckpointService();
        ReflectionTestUtils.setField(bareService, "entityClass", AgentPendingCheckpoint.class);
        ReflectionTestUtils.setField(bareService, "baseMapper", checkpointMapper);

        AgentPendingCheckpoint stale = activeRow(9L, 5L);
        stale.setCreatedAt(LocalDateTime.now().minusHours(PendingCheckpointService.FALLBACK_PENDING_TTL_HOURS + 1));
        when(checkpointMapper.selectOne(any())).thenReturn(stale);
        when(checkpointMapper.update(any(), any())).thenReturn(1);

        assertThat(bareService.findActive(5L)).isNull();
        // bareService 注入的是同一个 mapper mock，直接在其上断言 EXPIRED 迁移发生
        verify(checkpointMapper).update(isNull(), any());
    }

    @Test
    void findActiveReturnsNullWhenNoActiveRow() {
        when(checkpointMapper.selectOne(any())).thenReturn(null);

        assertThat(checkpointService.findActive(5L)).isNull();
        assertThat(checkpointService.findActive(null)).isNull();
        verify(checkpointMapper, never()).update(any(), any());
    }

    @Test
    void createSupersedesPreviousActiveBeforeInsertingNewOne() {
        // 新挂起必须先把同会话旧 ACTIVE 置 SUPERSEDED 再 INSERT（先 UPDATE 后 INSERT 的
        // 单实例语义），落库行字段完整：payload JSON、消息链快照、预算、status=ACTIVE
        when(checkpointMapper.update(any(), any())).thenReturn(1);
        when(checkpointMapper.insert(any(AgentPendingCheckpoint.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AgentPendingCheckpoint.class).setId(77L);
            return 1;
        });

        AgentPendingCheckpoint created = checkpointService.create(5L, 8L, 9L,
                PendingCheckpointKind.ASK_USER, "ask_user", "call-abc-123",
                Map.of("question", "报销哪个部门?", "options", List.of("销售部", "研发部")),
                List.of(SystemMessage.from("sys"), UserMessage.from("帮我报销")), 3, 1, null);

        // 顺序锁死：先覆盖旧 ACTIVE，后插入新行
        InOrder inOrder = inOrder(checkpointMapper);
        ArgumentCaptor<LambdaUpdateWrapper<AgentPendingCheckpoint>> supersedeCaptor = updateCaptor();
        inOrder.verify(checkpointMapper).update(isNull(), supersedeCaptor.capture());
        ArgumentCaptor<AgentPendingCheckpoint> insertCaptor = ArgumentCaptor.forClass(AgentPendingCheckpoint.class);
        inOrder.verify(checkpointMapper).insert(insertCaptor.capture());

        assertThat(supersedeCaptor.getValue().getSqlSegment()).contains("conversation_id", "status");
        assertThat(supersedeCaptor.getValue().getParamNameValuePairs().values())
                .contains(5L, PendingCheckpointStatus.ACTIVE.getCode(), PendingCheckpointStatus.SUPERSEDED.getCode());

        AgentPendingCheckpoint inserted = insertCaptor.getValue();
        assertThat(created.getId()).isEqualTo(77L);
        assertThat(created).isSameAs(inserted);
        assertThat(inserted.getUuid()).isNotBlank();
        assertThat(inserted.getConversationId()).isEqualTo(5L);
        assertThat(inserted.getCharacterId()).isEqualTo(8L);
        assertThat(inserted.getUserId()).isEqualTo(9L);
        assertThat(inserted.getKind()).isEqualTo("ASK_USER");
        assertThat(inserted.getPendingToolName()).isEqualTo("ask_user");
        assertThat(inserted.getPendingRequestId()).isEqualTo("call-abc-123");
        assertThat(inserted.getPayload()).contains("报销哪个部门?", "销售部");
        // 快照落库的是可解码回环的 JSON，恢复轮能重建等价消息链
        assertThat(ChatMessageSnapshotCodec.decode(inserted.getMessagesSnapshot())).hasSize(2);
        assertThat(inserted.getToolCallDepth()).isEqualTo(3);
        assertThat(inserted.getSuspensionCount()).isEqualTo(1);
        assertThat(inserted.getStatus()).isEqualTo(PendingCheckpointStatus.ACTIVE.getCode());
        // created_at 由服务插入时显式赋值（惰性 TTL 基准）
        assertThat(inserted.getCreatedAt()).isNotNull();
    }

    @Test
    void createWithNullPayloadAndSnapshotStillInsertsActiveRow() {
        // payload/快照允许为空（如审批挂起无 options）：落库不炸、快照为 "[]"
        when(checkpointMapper.insert(any(AgentPendingCheckpoint.class))).thenReturn(1);

        AgentPendingCheckpoint created = checkpointService.create(5L, 8L, 9L,
                PendingCheckpointKind.APPROVAL, "request_human_approval", "call-def-456",
                null, null, 1, 0, null);

        assertThat(created.getStatus()).isEqualTo(PendingCheckpointStatus.ACTIVE.getCode());
        assertThat(created.getKind()).isEqualTo("APPROVAL");
        assertThat(created.getPayload()).isNull();
        assertThat(created.getMessagesSnapshot()).isEqualTo("[]");
        assertThat(created.getApprovalGrant()).isNull();
    }

    @Test
    void createPersistsApprovalGrantJsonForMcpApprovalSuspension() {
        // MCP_APPROVAL 挂起携带批准凭证：approval_grant 列落「工具名 + 参数哈希」JSON，
        // 恢复轮装配据此读回 ToolContext 供装饰器校验放行
        // An MCP_APPROVAL suspension carries the approval grant: the
        // approval_grant column stores the "toolName + argsHash" JSON, read
        // back by the resume assembly into the ToolContext for the decorator's gate
        when(checkpointMapper.insert(any(AgentPendingCheckpoint.class))).thenReturn(1);
        ApprovalGrant grant = ApprovalGrant.forArguments("submit_expense_report",
                "{\"title\":\"聚餐\",\"amount\":3200}");

        AgentPendingCheckpoint created = checkpointService.create(5L, 8L, 9L,
                PendingCheckpointKind.MCP_APPROVAL, "submit_expense_report", "call-ghi-789",
                Map.of("action", "调用工具 submit_expense_report"), List.of(), 2, 1, grant);

        assertThat(created.getApprovalGrant()).isNotNull();
        ApprovalGrant parsed = ApprovalGrant.fromJson(created.getApprovalGrant());
        assertThat(parsed).isNotNull();
        assertThat(parsed.getToolName()).isEqualTo("submit_expense_report");
        assertThat(parsed.getArgsHash()).isEqualTo(ApprovalGrant.hashArguments("{\"title\":\"聚餐\",\"amount\":3200}"));
        // 凭证 JSON 可无损回环（恢复装配的读回依据）
        // The grant JSON round-trips losslessly (the resume assembly reads it back)
        assertThat(parsed.matches("submit_expense_report", "{\"title\":\"聚餐\",\"amount\":3200}")).isTrue();
        assertThat(parsed.matches("submit_expense_report", "{\"title\":\"聚餐\",\"amount\":9999}")).isFalse();
    }

    @Test
    void consumeWinsExactlyOnceViaConditionalUpdate() {
        // 第一次 consume：条件 UPDATE 命中（受影响 1 行）返回 true；
        // 第二次：行已非 ACTIVE（受影响 0 行）返回 false——幂等且防并发双消费
        when(checkpointMapper.update(any(), any())).thenReturn(1, 0);

        assertThat(checkpointService.consume(66L)).isTrue();
        assertThat(checkpointService.consume(66L)).isFalse();

        ArgumentCaptor<LambdaUpdateWrapper<AgentPendingCheckpoint>> captor = updateCaptor();
        verify(checkpointMapper, times(2)).update(isNull(), captor.capture());
        // 两次都必须带 status=ACTIVE 守卫，目标 CONSUMED
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("id", "status");
        assertThat(captor.getAllValues().get(0).getParamNameValuePairs().values())
                .contains(66L, PendingCheckpointStatus.ACTIVE.getCode(), PendingCheckpointStatus.CONSUMED.getCode());
        assertThat(captor.getAllValues().get(1).getSqlSegment()).contains("status");
    }

    @Test
    void consumeReturnsFalseForNullOrAlreadyTerminalRow() {
        // id 为空 / 行不存在（受影响 0 行）：均返回 false，不抛异常
        assertThat(checkpointService.consume(null)).isFalse();
        verify(checkpointMapper, never()).update(any(), any());

        when(checkpointMapper.update(any(), any())).thenReturn(0);
        assertThat(checkpointService.consume(404L)).isFalse();
    }

    @Test
    void markDeletedByConversationFlipsActiveRowsToDeleted() {
        // 会话删除级联：仅非终态（ACTIVE）置 DELETED，守卫必须同时约束会话与状态
        when(checkpointMapper.update(any(), any())).thenReturn(1);

        assertThat(checkpointService.markDeletedByConversation(5L)).isTrue();

        ArgumentCaptor<LambdaUpdateWrapper<AgentPendingCheckpoint>> captor = updateCaptor();
        verify(checkpointMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("conversation_id", "status");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(5L, PendingCheckpointStatus.ACTIVE.getCode(), PendingCheckpointStatus.DELETED.getCode());

        assertThat(checkpointService.markDeletedByConversation(null)).isFalse();
    }

    @Test
    void markSupersededFlipsActiveRowOnlyAndStaysIdempotent() {
        // 仍为 ACTIVE：条件迁移到 SUPERSEDED（T4 恢复前置的作废入口）；已终态时迁移
        // 落空返回 false（幂等，无副作用）
        // Still ACTIVE: conditional flip to SUPERSEDED (the invalidation entry
        // of T4's resume pre-check); on an already-terminal row the transition
        // misses and false is returned (idempotent, no side effect)
        when(checkpointMapper.update(any(), any())).thenReturn(1, 0);

        assertThat(checkpointService.markSuperseded(77L)).isTrue();
        assertThat(checkpointService.markSuperseded(77L)).isFalse();

        ArgumentCaptor<LambdaUpdateWrapper<AgentPendingCheckpoint>> captor = updateCaptor();
        verify(checkpointMapper, times(2)).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("id", "status");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(77L, PendingCheckpointStatus.ACTIVE.getCode(), PendingCheckpointStatus.SUPERSEDED.getCode());

        assertThat(checkpointService.markSuperseded(null)).isFalse();
    }

    private static AgentPendingCheckpoint activeRow(long id, long conversationId) {
        AgentPendingCheckpoint checkpoint = new AgentPendingCheckpoint();
        checkpoint.setId(id);
        checkpoint.setConversationId(conversationId);
        checkpoint.setStatus(PendingCheckpointStatus.ACTIVE.getCode());
        return checkpoint;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaUpdateWrapper<AgentPendingCheckpoint>> updateCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }
}
