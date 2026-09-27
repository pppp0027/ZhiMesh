package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.CharacterAddReq;
import com.pppp.zhimesh.common.dto.CharacterDto;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterPreset;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.mapper.CharacterPresetMapper;
import com.pppp.zhimesh.common.mapper.CharacterPresetRelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预设角色实例化契约验证：
 * ① toolPolicy 透传（T4 审查小修 8）：addByPresetCharacter 构造 CharacterAddReq 时必须
 * 携带预设的 tool_policy（如财务审批门禁），null 预设策略保持 null（默认策略）。
 * ② 2026-09-23 产品决策：预设 MCP 不再自动启用——实例化请求不携带 mcpIds（预设配套 MCP
 * 经预设关系运行时解析，用户 MCP 目录 adi_user_mcp 只含用户自选），且不触发任何
 * userMcpService 的启用写入；实例化返回的 dto 只读展示预设 MCP 合并结果。
 * <p>
 * 经 self.add(...) 捕获入参断言，不触发 add 的完整建链。
 * <p>
 * Preset instantiation contract:
 * (1) toolPolicy passthrough (T4 review fix 8): addByPresetCharacter must carry the
 * preset's tool_policy (e.g. the finance approval gate); a null preset policy stays
 * null (default policy).
 * (2) 2026-09-23 product decision: preset MCPs are no longer auto-enabled — the
 * instantiation request carries no mcpIds (preset-bound MCPs resolve from the preset
 * relation at runtime; the user MCP catalog adi_user_mcp only holds user selections),
 * no userMcpService enable-writes are triggered, and the returned dto shows the
 * merged preset MCPs read-only.
 * <p>
 * Asserted by capturing the argument of self.add(...) without triggering add's full
 * creation chain.
 */
class CharacterServicePresetInstantiationTest {

    private CharacterService characterService;
    private CharacterService self;
    private CharacterPresetService characterPresetService;
    private CharacterPresetRelService characterPresetRelService;
    private CharacterMapper characterMapper;
    private CharacterPresetMapper presetMapper;
    private CharacterPresetRelMapper relMapper;
    private UserMcpService userMcpService;
    private KnowledgeBaseService knowledgeBaseService;

    @BeforeAll
    static void initTableInfo() {
        // lambdaQuery 链的列解析需要实体元数据（脱离容器时手动预置）
        // Column resolution for lambda chains needs entity metadata (preset
        // manually outside the container)
        MybatisTableInfoTestSupport.init(Character.class, CharacterPreset.class,
                com.pppp.zhimesh.common.entity.CharacterPresetRel.class);
    }

    @BeforeEach
    void setUp() {
        characterService = new CharacterService();
        ReflectionTestUtils.setField(characterService, "entityClass", Character.class);
        characterMapper = mock(CharacterMapper.class);
        ReflectionTestUtils.setField(characterService, "baseMapper", characterMapper);

        characterPresetService = new CharacterPresetService();
        ReflectionTestUtils.setField(characterPresetService, "entityClass", CharacterPreset.class);
        presetMapper = mock(CharacterPresetMapper.class);
        ReflectionTestUtils.setField(characterPresetService, "baseMapper", presetMapper);

        characterPresetRelService = new CharacterPresetRelService();
        ReflectionTestUtils.setField(characterPresetRelService, "entityClass",
                com.pppp.zhimesh.common.entity.CharacterPresetRel.class);
        relMapper = mock(CharacterPresetRelMapper.class);
        ReflectionTestUtils.setField(characterPresetRelService, "baseMapper", relMapper);

        userMcpService = mock(UserMcpService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);

        self = mock(CharacterService.class);
        ReflectionTestUtils.setField(characterService, "self", self);
        ReflectionTestUtils.setField(characterService, "characterPresetService", characterPresetService);
        ReflectionTestUtils.setField(characterService, "characterPresetRelService", characterPresetRelService);
        ReflectionTestUtils.setField(characterService, "userMcpService", userMcpService);
        ReflectionTestUtils.setField(characterService, "knowledgeBaseService", knowledgeBaseService);

        User user = new User();
        user.setId(7L);
        user.setUuid("user-uuid-7");
        ThreadContext.setCurrentUser(user);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.setCurrentUser(null);
    }

    @Test
    void presetToolPolicyTravelsWithTheInstantiationRequest() {
        // 预设带审批门禁策略：实例化请求必须原样透传
        // The preset carries an approval-gate policy: the instantiation request
        // must pass it through verbatim
        CharacterPreset preset = preset("{\"builtinDenylist\":[],\"mcpApproval\":[\"transfer_funds\"]}");
        when(presetMapper.selectOne(any())).thenReturn(preset);
        CharacterDto created = new CharacterDto();
        created.setId(66L);
        when(self.add(any())).thenReturn(created);
        when(characterMapper.selectById(66L)).thenReturn(savedCharacter());

        characterService.addByPresetCharacter("preset-uuid-1");

        ArgumentCaptor<CharacterAddReq> addCaptor = ArgumentCaptor.forClass(CharacterAddReq.class);
        org.mockito.Mockito.verify(self).add(addCaptor.capture());
        assertThat(addCaptor.getValue().getToolPolicy())
                .isEqualTo("{\"builtinDenylist\":[],\"mcpApproval\":[\"transfer_funds\"]}");
        assertThat(addCaptor.getValue().getTitle()).isEqualTo("财务助手");
        // 新契约：请求不携带预设 mcpIds（经预设关系运行时解析）
        // New contract: the request carries no preset mcpIds (resolved from the
        // preset relation at runtime)
        assertThat(addCaptor.getValue().getMcpIds()).isNull();
    }

    @Test
    void nullPresetToolPolicyKeepsDefaultPolicy() {
        CharacterPreset preset = preset(null);
        when(presetMapper.selectOne(any())).thenReturn(preset);
        CharacterDto created = new CharacterDto();
        created.setId(67L);
        when(self.add(any())).thenReturn(created);
        when(characterMapper.selectById(67L)).thenReturn(savedCharacter());

        characterService.addByPresetCharacter("preset-uuid-2");

        ArgumentCaptor<CharacterAddReq> addCaptor = ArgumentCaptor.forClass(CharacterAddReq.class);
        org.mockito.Mockito.verify(self).add(addCaptor.capture());
        assertThat(addCaptor.getValue().getToolPolicy()).isNull();
    }

    @Test
    void presetMcpIdsAreNotCopiedNorAutoEnabled() {
        // 2026-09-23 产品决策：实例化请求不携带预设 mcpIds（改由预设关系运行时解析），
        // 且绝不触发 userMcpService 的启用写入（adi_user_mcp 只含用户自选）。
        // 2026-09-23 product decision: the instantiation request carries no preset
        // mcpIds (they resolve from the preset relation at runtime), and no
        // userMcpService enable-write is ever triggered (adi_user_mcp only holds
        // user selections).
        CharacterPreset preset = preset(null);
        when(presetMapper.selectOne(any())).thenReturn(preset);
        CharacterDto created = new CharacterDto();
        created.setId(68L);
        when(self.add(any())).thenReturn(created);
        when(characterMapper.selectById(68L)).thenReturn(savedCharacter());

        characterService.addByPresetCharacter("preset-uuid-3");

        ArgumentCaptor<CharacterAddReq> addCaptor = ArgumentCaptor.forClass(CharacterAddReq.class);
        org.mockito.Mockito.verify(self).add(addCaptor.capture());
        assertThat(addCaptor.getValue().getMcpIds()).isNull();
        verifyNoInteractions(userMcpService);
    }

    @Test
    void existingInstanceDtoShowsPresetMcpIdsMergedReadOnly() {
        // 已实例化的用户再次进入：dto.mcpIds = 用户自选 ∪ 预设配套（只读展示合并）
        // Re-entering an existing instance: dto.mcpIds = user selections unioned
        // with preset-bound MCPs (read-only display merge)
        CharacterPreset preset = preset(null);
        when(presetMapper.selectOne(any())).thenReturn(preset);
        when(presetMapper.selectById(11L)).thenReturn(preset);
        when(relMapper.selectOne(any())).thenReturn(rel());
        Character saved = savedCharacter();
        saved.setMcpIds("10");
        when(characterMapper.selectById(66L)).thenReturn(saved);

        CharacterDto dto = characterService.addByPresetCharacter("preset-uuid-4");

        assertThat(dto.getId()).isEqualTo(66L);
        assertThat(dto.getMcpIds()).containsExactly(10L, 501L, 502L);
    }

    // ==================== 构造与桩 / Construction and stubs ====================

    private static CharacterPreset preset(String toolPolicy) {
        CharacterPreset preset = new CharacterPreset();
        preset.setId(11L);
        preset.setUuid("preset-uuid-1");
        preset.setTitle("财务助手");
        preset.setRemark("preset remark");
        preset.setAiSystemMessage("You are a finance assistant.");
        // 预设配套 MCP：仅供预设关系运行时解析，绝不复制进 character.mcp_ids、不自动启用
        // Preset-bound MCPs: resolved via the preset relation at runtime only —
        // never copied into character.mcp_ids, never auto-enabled
        preset.setMcpIds("501,502");
        preset.setToolPolicy(toolPolicy);
        return preset;
    }

    private static com.pppp.zhimesh.common.entity.CharacterPresetRel rel() {
        return com.pppp.zhimesh.common.entity.CharacterPresetRel.builder()
                .presetCharacterId(11L)
                .userCharacterId(66L)
                .userId(7L)
                .build();
    }

    private static Character savedCharacter() {
        Character saved = new Character();
        saved.setId(66L);
        saved.setUuid("char-uuid-66");
        saved.setUserId(7L);
        saved.setTitle("财务助手");
        return saved;
    }
}
