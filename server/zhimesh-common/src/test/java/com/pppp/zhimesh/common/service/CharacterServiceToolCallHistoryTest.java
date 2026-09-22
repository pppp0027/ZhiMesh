package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterMessageToolCall;
import com.pppp.zhimesh.common.mapper.CharacterMessageToolCallMapper;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 历史回放的工具调用轨迹回填：一次 IN 查询按 message_id 分组、组内按 seq 升序、
 * 可空列映射默认值、空入参不触发查询。
 * <p>
 * History-replay backfill of tool-call traces: one IN query grouped by
 * message_id, each group sorted by seq ascending, nullable columns mapped to
 * defaults, and an empty input triggering no query at all.
 */
class CharacterServiceToolCallHistoryTest {

    private CharacterService characterService;
    private CharacterMessageToolCallMapper toolCallMapper;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 的列解析需要实体元数据（脱离容器时手动预置）
        // LambdaQueryWrapper column resolution needs entity metadata (preset manually outside the container)
        MybatisTableInfoTestSupport.init(Character.class, CharacterMessageToolCall.class);
    }

    @BeforeEach
    void setUp() {
        characterService = new CharacterService();
        ReflectionTestUtils.setField(characterService, "entityClass", Character.class);
        toolCallMapper = mock(CharacterMessageToolCallMapper.class);
        ReflectionTestUtils.setField(characterService, "characterMessageToolCallMapper", toolCallMapper);
    }

    @Test
    void historyToolCallsAreGroupedByMessageAndSortedBySeq() {
        // 故意乱序返回：组内排序必须由 Java 端保证，不依赖数据库返回顺序
        // Deliberately shuffled rows: in-group ordering is guaranteed in Java,
        // not by database return order
        when(toolCallMapper.selectList(any())).thenReturn(List.of(
                row(10L, 1, "search_knowledge", "{\"query\":\"b\"}", "r1", 22L, true),
                row(11L, 0, "search_knowledge", "{\"query\":\"c\"}", "r2", 33L, false),
                row(10L, 2, "search_knowledge", "{\"query\":\"c\"}", "r2", 33L, true),
                row(10L, 0, "search_knowledge", "{\"query\":\"a\"}", "r0", 11L, true)));

        Map<Long, List<ToolCallTrace>> grouped = invokeListToolCalls(List.of(10L, 11L));

        assertThat(grouped).containsOnlyKeys(10L, 11L);
        assertThat(grouped.get(10L))
                .extracting(ToolCallTrace::getSeq, ToolCallTrace::getArgs, ToolCallTrace::getDurationMs)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "{\"query\":\"a\"}", 11L),
                        org.assertj.core.groups.Tuple.tuple(1, "{\"query\":\"b\"}", 22L),
                        org.assertj.core.groups.Tuple.tuple(2, "{\"query\":\"c\"}", 33L));
        assertThat(grouped.get(11L))
                .extracting(ToolCallTrace::getSeq, ToolCallTrace::isSuccess)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, false));
    }

    @Test
    void historyToolCallsMapNullColumnsToDefaults() {
        CharacterMessageToolCall row = new CharacterMessageToolCall();
        row.setMessageId(10L);
        row.setToolName("search_knowledge");
        row.setSeq(null);
        row.setDurationMs(null);
        row.setSuccess(null);
        when(toolCallMapper.selectList(any())).thenReturn(List.of(row));

        Map<Long, List<ToolCallTrace>> grouped = invokeListToolCalls(List.of(10L));

        ToolCallTrace trace = grouped.get(10L).get(0);
        assertThat(trace.getToolName()).isEqualTo("search_knowledge");
        assertThat(trace.getSeq()).isZero();
        assertThat(trace.getDurationMs()).isZero();
        assertThat(trace.isSuccess()).isFalse();
        assertThat(trace.getArgs()).isNull();
        assertThat(trace.getResultSummary()).isNull();
    }

    @Test
    void emptyMessageIdsSkipTheQueryEntirely() {
        Map<Long, List<ToolCallTrace>> grouped = invokeListToolCalls(Collections.emptyList());

        assertThat(grouped).isEmpty();
        verifyNoInteractions(toolCallMapper);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, List<ToolCallTrace>> invokeListToolCalls(List<Long> messageIds) {
        return (Map<Long, List<ToolCallTrace>>) ReflectionTestUtils.invokeMethod(
                characterService, "listToolCallsByMessageIds", messageIds);
    }

    private static CharacterMessageToolCall row(Long messageId, int seq, String toolName,
                                                String args, String resultSummary, Long durationMs, boolean success) {
        CharacterMessageToolCall record = new CharacterMessageToolCall();
        record.setMessageId(messageId);
        record.setSeq(seq);
        record.setToolName(toolName);
        record.setArgs(args);
        record.setResultSummary(resultSummary);
        record.setDurationMs(durationMs);
        record.setSuccess(success);
        return record;
    }
}
