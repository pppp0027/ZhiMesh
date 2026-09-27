package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterPreset;
import com.pppp.zhimesh.common.entity.CharacterPresetRel;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.mapper.CharacterPresetMapper;
import com.pppp.zhimesh.common.mapper.CharacterPresetRelMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * resolveEffectiveMcpIds 运行时合并解析验证（2026-09-23 产品决策：预设 MCP 不再自动启用）：
 * ① character.mcp_ids（用户自选存储列）∪ 预设关系解析出的预设 mcp_ids，去重保序；
 * ② 无预设关系时退化为仅 character.mcp_ids；
 * ③ 预设关系查询异常时静默降级为仅 character.mcp_ids，不阻断聊天。
 * <p>
 * Runtime merge resolution of resolveEffectiveMcpIds (2026-09-23 product decision:
 * preset MCPs are no longer auto-enabled):
 * (1) character.mcp_ids (user-selected stored column) unioned with the preset's
 * mcp_ids resolved via the preset relation, deduplicated and order-stable;
 * (2) without a preset relation it degrades to character.mcp_ids only;
 * (3) a failing relation lookup silently degrades to character.mcp_ids only and
 * never blocks chatting.
 */
class CharacterServiceEffectiveMcpIdsTest {

    private CharacterService characterService;
    private CharacterPresetService characterPresetService;
    private CharacterPresetRelService characterPresetRelService;
    private CharacterPresetMapper presetMapper;
    private CharacterPresetRelMapper relMapper;

    @BeforeAll
    static void initTableInfo() {
        // lambdaQuery 链的列解析需要实体元数据（脱离容器时手动预置）
        // Column resolution for lambda chains needs entity metadata (preset
        // manually outside the container)
        MybatisTableInfoTestSupport.init(Character.class, CharacterPreset.class, CharacterPresetRel.class);
    }

    @BeforeEach
    void setUp() {
        characterService = new CharacterService();
        ReflectionTestUtils.setField(characterService, "entityClass", Character.class);
        ReflectionTestUtils.setField(characterService, "baseMapper", mock(CharacterMapper.class));

        characterPresetService = new CharacterPresetService();
        ReflectionTestUtils.setField(characterPresetService, "entityClass", CharacterPreset.class);
        presetMapper = mock(CharacterPresetMapper.class);
        ReflectionTestUtils.setField(characterPresetService, "baseMapper", presetMapper);

        characterPresetRelService = new CharacterPresetRelService();
        ReflectionTestUtils.setField(characterPresetRelService, "entityClass", CharacterPresetRel.class);
        relMapper = mock(CharacterPresetRelMapper.class);
        ReflectionTestUtils.setField(characterPresetRelService, "baseMapper", relMapper);

        ReflectionTestUtils.setField(characterService, "characterPresetService", characterPresetService);
        ReflectionTestUtils.setField(characterService, "characterPresetRelService", characterPresetRelService);
    }

    @Test
    void mergesUserSelectedAndPresetMcpIdsWithDedup() {
        // 用户自选 [10, 20] ∪ 预设配套 [20, 30] → [10, 20, 30]（去重、用户自选在前）
        // User selections [10, 20] unioned with preset-bound [20, 30] →
        // [10, 20, 30] (deduplicated, user selections first)
        when(relMapper.selectOne(any())).thenReturn(rel());
        when(presetMapper.selectById(11L)).thenReturn(preset("20,30"));

        List<Long> effectiveIds = characterService.resolveEffectiveMcpIds(character("10,20"));

        assertThat(effectiveIds).containsExactly(10L, 20L, 30L);
    }

    @Test
    void fallsBackToUserSelectedIdsWithoutPresetRelation() {
        // 无预设关系：退化为仅 character.mcp_ids
        // No preset relation: degrade to character.mcp_ids only
        when(relMapper.selectOne(any())).thenReturn(null);

        List<Long> effectiveIds = characterService.resolveEffectiveMcpIds(character("10,20"));

        assertThat(effectiveIds).containsExactly(10L, 20L);
    }

    @Test
    void fallsBackToUserSelectedIdsWhenPresetMissing() {
        // 关系存在但预设已被删除：同样退化为仅 character.mcp_ids
        // Relation exists but the preset is gone: degrade to character.mcp_ids only
        when(relMapper.selectOne(any())).thenReturn(rel());
        when(presetMapper.selectById(11L)).thenReturn(null);

        List<Long> effectiveIds = characterService.resolveEffectiveMcpIds(character("10,20"));

        assertThat(effectiveIds).containsExactly(10L, 20L);
    }

    @Test
    void relationLookupFailureDegradesInsteadOfBlockingChat() {
        // 预设关系查询异常：静默降级为仅 character.mcp_ids，不阻断聊天
        // Failing relation lookup: silently degrade to character.mcp_ids only,
        // never block chatting
        when(relMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        List<Long> effectiveIds = characterService.resolveEffectiveMcpIds(character("10,20"));

        assertThat(effectiveIds).containsExactly(10L, 20L);
    }

    @Test
    void blankSelectionsWithoutRelationResolveToEmptyList() {
        // 无自选且无预设关系：解析为空集合（不创建任何 MCP 客户端）
        // No selections and no relation: resolves to an empty list (no MCP clients)
        when(relMapper.selectOne(any())).thenReturn(null);

        List<Long> effectiveIds = characterService.resolveEffectiveMcpIds(character(null));

        assertThat(effectiveIds).isEmpty();
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private static Character character(String mcpIds) {
        Character character = new Character();
        character.setId(66L);
        character.setUuid("char-uuid-66");
        character.setUserId(7L);
        character.setMcpIds(mcpIds);
        return character;
    }

    private static CharacterPreset preset(String mcpIds) {
        CharacterPreset preset = new CharacterPreset();
        preset.setId(11L);
        preset.setUuid("preset-uuid-11");
        preset.setMcpIds(mcpIds);
        return preset;
    }

    private static CharacterPresetRel rel() {
        return CharacterPresetRel.builder()
                .presetCharacterId(11L)
                .userCharacterId(66L)
                .userId(7L)
                .build();
    }
}
