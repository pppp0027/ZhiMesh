package com.pppp.zhimesh.common.rag.profile;

import com.fasterxml.jackson.databind.JsonNode;

public record KnowledgeRouteProfileCandidate(
        String type,
        String key,
        String text,
        JsonNode sourceRefs
) {
}
