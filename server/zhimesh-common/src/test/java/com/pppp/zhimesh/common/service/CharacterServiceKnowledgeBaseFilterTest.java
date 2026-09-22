package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CharacterServiceKnowledgeBaseFilterTest {

    private CharacterService service;
    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeBaseAccessService accessService;
    private User user;

    @BeforeEach
    void setUp() {
        service = new CharacterService();
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        accessService = mock(KnowledgeBaseAccessService.class);
        ReflectionTestUtils.setField(service, "knowledgeBaseService", knowledgeBaseService);
        ReflectionTestUtils.setField(service, "knowledgeBaseAccessService", accessService);
        user = new User();
        user.setId(1L);
        user.setUuid("user-uuid");
    }

    @Test
    void disabledKnowledgeBaseCanNeverEnterRuntimeRetrievalScope() {
        KbInfoResp enabled = new KbInfoResp();
        enabled.setIsEnabled(true);
        KbInfoResp legacyEnabled = new KbInfoResp();
        legacyEnabled.setIsEnabled(null);
        KbInfoResp disabled = new KbInfoResp();
        disabled.setIsEnabled(false);

        assertThat(CharacterService.isKnowledgeBaseEnabled(enabled)).isTrue();
        assertThat(CharacterService.isKnowledgeBaseEnabled(legacyEnabled)).isTrue();
        assertThat(CharacterService.isKnowledgeBaseEnabled(disabled)).isFalse();
        assertThat(CharacterService.isKnowledgeBaseEnabled(null)).isFalse();
    }

    private KbInfoResp kb(Long id, String ownerType, boolean isSystem) {
        KbInfoResp dto = new KbInfoResp();
        dto.setId(id);
        dto.setOwnerType(ownerType);
        dto.setIsSystem(isSystem);
        dto.setIsEnabled(true);
        return dto;
    }

    @Test
    void filterKeepsTeamAndCompanyKnowledgeBasesForMember() {
        KbInfoResp teamKb = kb(1L, "TEAM", false);
        KbInfoResp companyKb = kb(2L, "COMPANY", false);
        KbInfoResp personalKb = kb(3L, "PERSONAL", false);
        // 可读性完全交由 resolver：团队成员库与企业库对成员可读
        when(accessService.canRead(any(User.class), any(KbInfoResp.class))).thenReturn(true);
        when(knowledgeBaseService.listByIds(anyList()))
                .thenReturn(List.of(teamKb, companyKb, personalKb));

        List<KbInfoResp> filtered = service.filterEnableKb(user, List.of(1L, 2L, 3L));

        assertThat(filtered).extracting(KbInfoResp::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void filterDropsNonMemberTeamKnowledgeBaseButKeepsCompanyStaff() {
        KbInfoResp teamKb = kb(1L, "TEAM", false);
        KbInfoResp companyKb = kb(2L, "COMPANY", false);
        // 可读性完全交由 resolver：非成员团队库被拒，企业 STAFF 库放行
        when(accessService.canRead(any(User.class), any(KbInfoResp.class)))
                .thenAnswer(invocation -> "COMPANY".equals(((KbInfoResp) invocation.getArgument(1)).getOwnerType()));
        when(knowledgeBaseService.listByIds(anyList())).thenReturn(List.of(teamKb, companyKb));

        List<KbInfoResp> filtered = service.filterEnableKb(user, List.of(1L, 2L));

        assertThat(filtered).extracting(KbInfoResp::getId).containsExactly(2L);
    }

    @Test
    void filterAlwaysDropsSystemKnowledgeBasesFromUserSelection() {
        KbInfoResp systemKb = kb(1L, "PERSONAL", true);
        when(accessService.canRead(any(User.class), any(KbInfoResp.class))).thenReturn(true);
        when(knowledgeBaseService.listByIds(anyList())).thenReturn(List.of(systemKb));

        // 系统库只能经系统角色白名单进入检索范围，用户选择永远无效
        assertThat(service.filterEnableKb(user, List.of(1L))).isEmpty();
    }
}
