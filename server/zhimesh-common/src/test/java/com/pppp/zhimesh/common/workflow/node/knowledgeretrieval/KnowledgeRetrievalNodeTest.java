package com.pppp.zhimesh.common.workflow.node.knowledgeretrieval;

import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.rag.bm25.Bm25ReadinessService;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalNodeTest {

    @Test
    void allowsPublicOrOwnedKnowledgeBasesButNeverSystemKnowledgeBases() {
        User owner = new User();
        owner.setId(7L);

        KnowledgeBase privateOwned = knowledgeBase(false, false, 7L);
        KnowledgeBase privateForeign = knowledgeBase(false, false, 8L);
        KnowledgeBase publicForeign = knowledgeBase(true, false, 8L);
        KnowledgeBase system = knowledgeBase(true, true, 7L);

        assertTrue(KnowledgeRetrievalNode.canRead(privateOwned, owner));
        assertFalse(KnowledgeRetrievalNode.canRead(privateForeign, owner));
        assertTrue(KnowledgeRetrievalNode.canRead(publicForeign, owner));
        assertFalse(KnowledgeRetrievalNode.canRead(system, owner));
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

    private static KnowledgeBase knowledgeBase(boolean isPublic, boolean isSystem, Long ownerId) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setIsPublic(isPublic);
        knowledgeBase.setIsSystem(isSystem);
        knowledgeBase.setOwnerId(ownerId);
        return knowledgeBase;
    }
}
