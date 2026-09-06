package com.pppp.zhimesh.common.rag.profile;

import java.util.List;

/** Versioned Redis payload for one immutable knowledge-base route-profile release. */
public record KnowledgeRouteProfileBundle(
        String kbUuid,
        long generation,
        String profileSetUuid,
        String sourceManifestHash,
        long embeddingModelId,
        String embeddingModelIdentity,
        int embeddingDimension,
        String generatorVersion,
        List<Profile> profiles
) {
    public KnowledgeRouteProfileBundle {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    public record Profile(String type, String key, String text, String embeddingBase64) {
    }
}
