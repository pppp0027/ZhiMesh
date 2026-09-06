package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileBundle;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCache;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCodec;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileSizing;
import com.pppp.zhimesh.common.rag.profile.RouteProfileVectorMath;
import dev.langchain4j.data.embedding.Embedding;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class DefaultKnowledgeScopeProfileProbe implements KnowledgeScopeProfileProbe {

    private static final int SUPPORT_TOP_K = 3;

    private final KnowledgeRouteProfileCache cache;
    private final KnowledgeRouteProfileCodec codec;
    private final ZhiMeshProperties properties;

    DefaultKnowledgeScopeProfileProbe(KnowledgeRouteProfileCache cache,
                                      KnowledgeRouteProfileCodec codec,
                                      ZhiMeshProperties properties) {
        this.cache = cache;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    public Result probe(List<Embedding> queryEmbeddings, List<KbInfoResp> knowledgeBases) {
        if (queryEmbeddings == null || queryEmbeddings.isEmpty()
                || queryEmbeddings.stream().anyMatch(java.util.Objects::isNull)
                || knowledgeBases == null || knowledgeBases.isEmpty()) {
            return Result.incomplete("query embedding or knowledge-base scope is unavailable");
        }
        List<KbInfoResp> nonEmpty = knowledgeBases.stream()
                .filter(kb -> kb.getItemCount() == null || kb.getItemCount() > 0)
                .toList();
        if (nonEmpty.isEmpty()) return Result.incomplete("knowledge-base scope has no indexed content");

        Map<String, Long> generations = new LinkedHashMap<>();
        Map<String, Result.KnowledgeBaseMatch> matches = new LinkedHashMap<>();
        for (KbInfoResp kb : nonEmpty) {
            if (StringUtils.isBlank(kb.getUuid())) {
                continue;
            }
            if (value(kb.getRouteProfileActiveGeneration()) <= 0) {
                matches.put(kb.getUuid(), Result.KnowledgeBaseMatch.incomplete(
                        "knowledge base has no serving profile generation"));
                continue;
            }
            generations.put(kb.getUuid(), kb.getRouteProfileActiveGeneration());
        }
        Map<String, KnowledgeRouteProfileBundle> bundles = cache.getAll(generations);
        String runtimeModel = properties.getEmbeddingModel();
        List<float[]> queries = queryEmbeddings.stream()
                .map(Embedding::vector)
                .map(RouteProfileVectorMath::normalize)
                .toList();
        int queryDimension = queries.get(0).length;
        if (queries.stream().anyMatch(query -> query.length != queryDimension)) {
            return Result.incomplete("query embeddings use inconsistent dimensions");
        }
        boolean stale = false;
        int compared = 0;
        double maxScore = -1D;
        for (KbInfoResp kb : nonEmpty) {
            if (StringUtils.isBlank(kb.getUuid()) || !generations.containsKey(kb.getUuid())) {
                continue;
            }
            KnowledgeRouteProfileBundle bundle = bundles.get(kb.getUuid());
            if (!valid(kb, bundle, runtimeModel,
                    properties.getKnowledgeScopeGate().getGeneratorVersion(), queryDimension)) {
                matches.put(kb.getUuid(), Result.KnowledgeBaseMatch.incomplete(
                        "knowledge-base profile bundle is missing or incompatible"));
                continue;
            }
            boolean fresh = "READY".equals(kb.getRouteProfileStatus())
                    && value(kb.getRouteProfileGeneration()) == value(kb.getRouteProfileActiveGeneration());
            if (bundle.profiles().isEmpty()) {
                matches.put(kb.getUuid(), Result.KnowledgeBaseMatch.incomplete(
                        "knowledge-base profile bundle is empty"));
                continue;
            }
            int profileLimit = KnowledgeRouteProfileSizing.boundedLimit(
                    KnowledgeRouteProfileSizing.contentUnits(value(kb.getItemCount()), value(kb.getEmbeddingCount())),
                    properties.getKnowledgeScopeGate().getProfileLimit());
            List<Double> profileScores = new ArrayList<>(Math.min(bundle.profiles().size(), profileLimit));
            boolean invalidVector = false;
            for (KnowledgeRouteProfileBundle.Profile profile : bundle.profiles().stream().limit(profileLimit).toList()) {
                try {
                    double profileMax = -1D;
                    float[] vector = codec.decode(profile.embeddingBase64(), queryDimension);
                    for (float[] query : queries) {
                        profileMax = Math.max(profileMax, RouteProfileVectorMath.dot(query, vector));
                    }
                    profileScores.add(profileMax);
                    compared++;
                } catch (RuntimeException exception) {
                    invalidVector = true;
                    break;
                }
            }
            if (invalidVector || profileScores.isEmpty()) {
                matches.put(kb.getUuid(), Result.KnowledgeBaseMatch.incomplete(
                        invalidVector
                                ? "knowledge-base profile bundle contains an invalid vector"
                                : "knowledge-base profile bundle has no comparable profiles"));
                continue;
            }
            profileScores.sort(Comparator.reverseOrder());
            double kbMaxScore = profileScores.get(0);
            int supportCount = Math.min(SUPPORT_TOP_K, profileScores.size());
            double topKMeanScore = profileScores.subList(0, supportCount).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(-1D);
            boolean budgetMismatch = bundle.profiles().size() != profileLimit;
            stale |= !fresh || budgetMismatch;
            maxScore = Math.max(maxScore, kbMaxScore);
            matches.put(kb.getUuid(), new Result.KnowledgeBaseMatch(
                    true, !fresh || budgetMismatch, kbMaxScore, topKMeanScore, profileScores.size(),
                    !fresh ? "serving stale profile as positive evidence only"
                            : budgetMismatch ? "profile bundle does not match the adaptive budget; positive evidence only"
                            : "fresh profile bundle"));
        }
        boolean hasIncomplete = matches.values().stream().anyMatch(match -> !match.complete());
        // Preserve the aggregate Result.complete contract for existing callers;
        // the gate can still use the independent per-KB matches when this is
        // false and fail open only for the affected scopes.
        boolean complete = !matches.isEmpty() && !hasIncomplete;
        String reason = hasIncomplete
                ? "one or more knowledge-base profile bundles are incomplete; incomplete scopes fail open"
                : stale ? "serving stale profiles as positive evidence only" : "all profile bundles are fresh";
        return new Result(complete, stale, maxScore, compared, reason, matches);
    }

    private static boolean valid(KbInfoResp kb, KnowledgeRouteProfileBundle bundle,
                                 String runtimeModel, String runtimeGeneratorVersion,
                                 int queryDimension) {
        return bundle != null
                && kb.getUuid().equals(bundle.kbUuid())
                && value(kb.getRouteProfileActiveGeneration()) == bundle.generation()
                && StringUtils.equals(kb.getRouteProfileSetUuid(), bundle.profileSetUuid())
                && StringUtils.equals(kb.getRouteProfileSourceHash(), bundle.sourceManifestHash())
                && StringUtils.equals(kb.getRouteProfileModelIdentity(), bundle.embeddingModelIdentity())
                && StringUtils.equals(runtimeModel, bundle.embeddingModelIdentity())
                && StringUtils.equals(runtimeGeneratorVersion, bundle.generatorVersion())
                && bundle.embeddingDimension() == queryDimension;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static long value(Integer value) {
        return value == null ? 0L : value;
    }
}
