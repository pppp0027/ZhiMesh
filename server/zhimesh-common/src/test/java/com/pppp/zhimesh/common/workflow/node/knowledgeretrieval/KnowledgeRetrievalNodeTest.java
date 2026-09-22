package com.pppp.zhimesh.common.workflow.node.knowledgeretrieval;

import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.TeamMember;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.KbOwnerTypeEnum;
import com.pppp.zhimesh.common.mapper.TeamMemberMapper;
import com.pppp.zhimesh.common.rag.bm25.Bm25ReadinessService;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalNodeTest {

    @Test
    void allowsOwnedKnowledgeBasesButNeverForeignPrivateOrSystemKnowledgeBases() {
        User owner = new User();
        owner.setId(7L);
        KnowledgeBaseAccessService accessService = accessServiceReturningNoMembership();

        KnowledgeBase owned = knowledgeBase(false, 7L);
        KnowledgeBase foreign = knowledgeBase(false, 8L);
        KnowledgeBase system = knowledgeBase(true, 7L);

        assertTrue(KnowledgeRetrievalNode.canRead(owned, owner, accessService));
        // 他人的个人库不再有"公开"逃生门：非 owner 一律不可读
        assertFalse(KnowledgeRetrievalNode.canRead(foreign, owner, accessService));
        assertFalse(KnowledgeRetrievalNode.canRead(system, owner, accessService));
    }

    @Test
    void allowsTeamKnowledgeBasesForMembersAndCompanyKnowledgeBasesForEveryone() {
        User user = new User();
        user.setId(7L);
        KnowledgeBaseAccessService accessService = accessServiceReturningNoMembership();

        KnowledgeBase teamKb = knowledgeBase(false, 8L);
        teamKb.setOwnerType(KbOwnerTypeEnum.TEAM.getValue());
        teamKb.setTeamId(5L);
        KnowledgeBase companyKb = knowledgeBase(false, 9L);
        companyKb.setOwnerType(KbOwnerTypeEnum.COMPANY.getValue());

        assertFalse(KnowledgeRetrievalNode.canRead(teamKb, user, accessService));

        KnowledgeBaseAccessService memberAccessService = accessServiceWithMembership(5L, 7L, "READER");
        assertTrue(KnowledgeRetrievalNode.canRead(teamKb, user, memberAccessService));
        assertTrue(KnowledgeRetrievalNode.canRead(companyKb, user, accessService));
    }

    private static KnowledgeBaseAccessService accessServiceReturningNoMembership() {
        TeamMemberMapper teamMemberMapper = mock(TeamMemberMapper.class);
        when(teamMemberMapper.selectOne(any())).thenReturn(null);
        KnowledgeBaseAccessService accessService = new KnowledgeBaseAccessService();
        ReflectionTestUtils.setField(accessService, "teamMemberMapper", teamMemberMapper);
        return accessService;
    }

    private static KnowledgeBaseAccessService accessServiceWithMembership(long teamId, long userId, String role) {
        TeamMember membership = new TeamMember();
        membership.setTeamId(teamId);
        membership.setUserId(userId);
        membership.setRole(role);
        TeamMemberMapper teamMemberMapper = mock(TeamMemberMapper.class);
        when(teamMemberMapper.selectOne(any())).thenReturn(membership);
        KnowledgeBaseAccessService accessService = new KnowledgeBaseAccessService();
        ReflectionTestUtils.setField(accessService, "teamMemberMapper", teamMemberMapper);
        return accessService;
    }

    @Test
    void addsBm25OnlyWhenTheEntireAuthorizedScopeIsReady() {
        Set<String> authorizedScope = Set.of("kb-1", "kb-2");
        Bm25ReadinessService readinessService = mock(Bm25ReadinessService.class);
        when(readinessService.readyKnowledgeBases(authorizedScope)).thenReturn(authorizedScope);

        Set<RetrievalRoute> routes = KnowledgeRetrievalNode.availableRoutes(
                authorizedScope, true, true, readinessService);

        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH, RetrievalRoute.BM25), routes);
        verify(readinessService).readyKnowledgeBases(authorizedScope);
    }

    @Test
    void fallsBackToEstablishedRoutesWhenBm25IsNotReady() {
        Set<String> authorizedScope = Set.of("kb-1", "kb-2");
        Bm25ReadinessService readinessService = mock(Bm25ReadinessService.class);
        when(readinessService.readyKnowledgeBases(authorizedScope)).thenReturn(Set.of("kb-1"));

        Set<RetrievalRoute> routes = KnowledgeRetrievalNode.availableRoutes(
                authorizedScope, true, true, readinessService);

        assertEquals(Set.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH), routes);
    }

    @Test
    void doesNotCheckReadinessWhenBm25RetrieverIsNotConfigured() {
        Bm25ReadinessService readinessService = mock(Bm25ReadinessService.class);

        Set<RetrievalRoute> routes = KnowledgeRetrievalNode.availableRoutes(
                Set.of("kb-1"), false, false, readinessService);

        assertEquals(Set.of(RetrievalRoute.VECTOR), routes);
        verifyNoInteractions(readinessService);
    }

    private static KnowledgeBase knowledgeBase(boolean isSystem, Long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setIsSystem(isSystem);
        knowledgeBase.setOwnerId(ownerId);
        return knowledgeBase;
    }
}
