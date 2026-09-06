package com.pppp.zhimesh.common.rag.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.KnowledgeBaseRouteProfile;
import com.pppp.zhimesh.common.entity.KnowledgeBaseRouteProfileSet;
import com.pppp.zhimesh.common.mapper.AiModelMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseItemMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseRouteProfileMapper;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseRouteProfileSetMapper;
import com.pppp.zhimesh.common.util.UuidUtil;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds and atomically publishes bounded route-profile releases. */
@Slf4j
@Service
public class KnowledgeRouteProfileBuildService {

    private static final String BUILDING = "BUILDING";
    private static final String READY = "READY";
    private static final String ACTIVE = "ACTIVE";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String FAILED = "FAILED";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseItemMapper itemMapper;
    private final KnowledgeBaseRouteProfileSetMapper setMapper;
    private final KnowledgeBaseRouteProfileMapper profileMapper;
    private final AiModelMapper aiModelMapper;
    private final EmbeddingModel embeddingModel;
    private final ZhiMeshProperties properties;
    private final KnowledgeRouteProfileCandidateCollector candidateCollector;
    private final KnowledgeRouteProfileManifestBuilder manifestBuilder;
    private final KnowledgeRouteProfileSelector selector;
    private final KnowledgeRouteProfileCache cache;
    private final KnowledgeRouteProfileCodec codec;
    private final TransactionTemplate transactions;

    public KnowledgeRouteProfileBuildService(KnowledgeBaseMapper knowledgeBaseMapper,
                                             KnowledgeBaseItemMapper itemMapper,
                                             KnowledgeBaseRouteProfileSetMapper setMapper,
                                             KnowledgeBaseRouteProfileMapper profileMapper,
                                             AiModelMapper aiModelMapper,
                                             EmbeddingModel embeddingModel,
                                             ZhiMeshProperties properties,
                                             KnowledgeRouteProfileCandidateCollector candidateCollector,
                                             KnowledgeRouteProfileManifestBuilder manifestBuilder,
                                             KnowledgeRouteProfileSelector selector,
                                             KnowledgeRouteProfileCache cache,
                                             KnowledgeRouteProfileCodec codec,
                                             PlatformTransactionManager transactionManager) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.itemMapper = itemMapper;
        this.setMapper = setMapper;
        this.profileMapper = profileMapper;
        this.aiModelMapper = aiModelMapper;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.candidateCollector = candidateCollector;
        this.manifestBuilder = manifestBuilder;
        this.selector = selector;
        this.cache = cache;
        this.codec = codec;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Outcome buildOrWarm(String kbUuid) {
        KnowledgeBase kb = selectKnowledgeBase(kbUuid);
        if (kb == null || !Boolean.TRUE.equals(kb.getIsEnabled())) return Outcome.SKIPPED;
        long generation = value(kb.getRouteProfileGeneration());
        if (generation <= 0) {
            knowledgeBaseMapper.markRouteProfileStale(kbUuid);
            return Outcome.RETRY;
        }

        List<KnowledgeBaseItem> items = itemMapper.selectList(new LambdaQueryWrapper<KnowledgeBaseItem>()
                .eq(KnowledgeBaseItem::getKbUuid, kbUuid)
                .orderByAsc(KnowledgeBaseItem::getId));
        if (items.isEmpty()) return Outcome.SKIPPED;
        String modelIdentity = properties.getEmbeddingModel();
        long modelId = resolveModelId(modelIdentity);
        String manifestHash = manifestBuilder.build(kb, items);
        int requestedProfileLimit = profileLimit(kb, items.size());
        KnowledgeBaseRouteProfileSet existing = selectSet(kbUuid, generation);
        if (isCurrentRelease(kb, existing, manifestHash, modelIdentity, requestedProfileLimit)) {
            return publishCacheThenActivate(kb, existing, loadProfiles(existing.getUuid()), modelId, modelIdentity)
                    ? Outcome.COMPLETE : Outcome.RETRY;
        }
        if ("READY".equals(kb.getRouteProfileStatus())
                && value(kb.getRouteProfileActiveGeneration()) == generation) {
            knowledgeBaseMapper.markRouteProfileStale(kbUuid);
            return Outcome.RETRY;
        }

        knowledgeBaseMapper.markRouteProfileBuilding(kbUuid, generation);
        try {
            List<KnowledgeRouteProfileCandidate> candidates = candidateCollector.collect(kb, items);
            if (candidates.isEmpty()) throw new IllegalStateException("No informative route-profile candidate was produced");
            List<TextSegment> segments = candidates.stream().map(candidate -> TextSegment.from(candidate.text())).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            List<KnowledgeRouteProfileSelector.Selected> selected = selector.select(
                    candidates, embeddings, requestedProfileLimit);
            int dimension = selected.get(0).vector().length;
            KnowledgeBaseRouteProfileSet profileSet = prepareBuildingSet(
                    existing, kb, generation, manifestHash, modelId, modelIdentity, dimension, requestedProfileLimit);
            List<KnowledgeBaseRouteProfile> profiles = persistReadyRelease(profileSet, kb, selected, dimension);
            if (!publishCacheThenActivate(kb, profileSet, profiles, modelId, modelIdentity)) return Outcome.RETRY;
            log.info("Knowledge route profile activated, kbUuid:{}, generation:{}, profiles:{}",
                    kbUuid, generation, profiles.size());
            return Outcome.COMPLETE;
        } catch (Exception exception) {
            log.error("Knowledge route-profile build failed, kbUuid:{}, generation:{}", kbUuid, generation, exception);
            markFailed(kbUuid, generation, exception);
            return Outcome.FAILED;
        }
    }

    /**
     * Republishes the currently ACTIVE immutable release. A release created
     * with a larger profile budget is retired lazily so deployments migrate
     * from older fixed-size releases without doing work on the chat path.
     */
    public boolean warmActive(String kbUuid) {
        KnowledgeBase kb = selectKnowledgeBase(kbUuid);
        if (kb == null || !Boolean.TRUE.equals(kb.getIsEnabled())) return false;
        long activeGeneration = value(kb.getRouteProfileActiveGeneration());
        if (activeGeneration <= 0 || StringUtils.isBlank(kb.getRouteProfileSetUuid())) return false;
        KnowledgeBaseRouteProfileSet activeSet = selectSet(kbUuid, activeGeneration);
        if (activeSet == null || !ACTIVE.equals(activeSet.getStatus())
                || !Objects.equals(kb.getRouteProfileSetUuid(), activeSet.getUuid())) {
            return false;
        }
        List<KnowledgeBaseRouteProfile> profiles = loadProfiles(activeSet.getUuid());
        int expectedLimit = profileLimit(kb, value(kb.getItemCount()));
        if (!Objects.equals(activeSet.getProfileLimit(), expectedLimit)
                || (activeSet.getProfileCount() != null && activeSet.getProfileCount() > expectedLimit)
                || profiles.size() > expectedLimit) {
            knowledgeBaseMapper.markRouteProfileStale(kbUuid);
            log.info("Retiring out-of-budget knowledge route profile release, kbUuid:{}, generation:{}, storedProfiles:{}, limit:{}",
                    kbUuid, activeGeneration, profiles.size(), expectedLimit);
            return false;
        }
        return !profiles.isEmpty() && cache.put(bundle(activeSet, profiles));
    }

    public void deleteByKnowledgeBase(String kbUuid, long activeGeneration) {
        transactions.executeWithoutResult(status -> {
            profileMapper.delete(new LambdaQueryWrapper<KnowledgeBaseRouteProfile>()
                    .eq(KnowledgeBaseRouteProfile::getKbUuid, kbUuid));
            setMapper.delete(new LambdaQueryWrapper<KnowledgeBaseRouteProfileSet>()
                    .eq(KnowledgeBaseRouteProfileSet::getKbUuid, kbUuid));
        });
        cache.delete(kbUuid, activeGeneration);
    }

    private boolean isCurrentRelease(KnowledgeBase kb, KnowledgeBaseRouteProfileSet set,
                                     String manifestHash, String modelIdentity, int expectedProfileLimit) {
        return set != null
                && (READY.equals(set.getStatus()) || ACTIVE.equals(set.getStatus()))
                && StringUtils.equals(set.getSourceManifestHash(), manifestHash)
                && StringUtils.equals(set.getEmbeddingModelIdentity(), modelIdentity)
                && StringUtils.equals(set.getGeneratorVersion(), properties.getKnowledgeScopeGate().getGeneratorVersion())
                && value(kb.getRouteProfileGeneration()) == set.getGeneration()
                && set.getProfileLimit() != null
                && set.getProfileLimit() == expectedProfileLimit
                && set.getProfileCount() != null
                && set.getProfileCount() <= expectedProfileLimit;
    }

    private int profileLimit(KnowledgeBase kb, long observedItemCount) {
        long embeddingCount = kb == null || kb.getEmbeddingCount() == null
                ? 0L : Math.max(0L, kb.getEmbeddingCount());
        long contentUnits = KnowledgeRouteProfileSizing.contentUnits(observedItemCount, embeddingCount);
        return KnowledgeRouteProfileSizing.boundedLimit(contentUnits,
                properties.getKnowledgeScopeGate().getProfileLimit());
    }

    private KnowledgeBaseRouteProfileSet prepareBuildingSet(KnowledgeBaseRouteProfileSet existing,
                                                             KnowledgeBase kb, long generation,
                                                             String manifestHash, long modelId,
                                                             String modelIdentity, int dimension,
                                                             int profileLimit) {
        KnowledgeBaseRouteProfileSet set = existing == null ? new KnowledgeBaseRouteProfileSet() : existing;
        transactions.executeWithoutResult(status -> {
            if (set.getId() != null) {
                profileMapper.delete(new LambdaQueryWrapper<KnowledgeBaseRouteProfile>()
                        .eq(KnowledgeBaseRouteProfile::getProfileSetUuid, set.getUuid()));
            } else {
                set.setUuid(UuidUtil.createShort());
                set.setKbId(kb.getId());
                set.setKbUuid(kb.getUuid());
                set.setGeneration(generation);
            }
            set.setSourceManifestHash(manifestHash);
            set.setEmbeddingModelId(modelId);
            set.setEmbeddingModelIdentity(modelIdentity);
            set.setEmbeddingDimension(dimension);
            set.setGeneratorVersion(properties.getKnowledgeScopeGate().getGeneratorVersion());
            set.setProfileLimit(profileLimit);
            set.setProfileCount(0);
            set.setStatus(BUILDING);
            set.setIsActive(false);
            set.setErrorType(null);
            set.setErrorMessage(null);
            set.setStartedAt(LocalDateTime.now());
            set.setCompletedAt(null);
            set.setActivatedAt(null);
            if (set.getId() == null) setMapper.insert(set); else setMapper.updateById(set);
        });
        return set;
    }

    private List<KnowledgeBaseRouteProfile> persistReadyRelease(
            KnowledgeBaseRouteProfileSet set, KnowledgeBase kb,
            List<KnowledgeRouteProfileSelector.Selected> selected, int dimension) {
        List<KnowledgeBaseRouteProfile> profiles = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            int ordinal = 0;
            for (KnowledgeRouteProfileSelector.Selected value : selected) {
                KnowledgeBaseRouteProfile profile = new KnowledgeBaseRouteProfile();
                profile.setUuid(UuidUtil.createShort());
                profile.setProfileSetId(set.getId());
                profile.setProfileSetUuid(set.getUuid());
                profile.setKbId(kb.getId());
                profile.setKbUuid(kb.getUuid());
                profile.setProfileType(value.candidate().type());
                profile.setProfileKey(value.candidate().key());
                profile.setProfileText(value.candidate().text());
                profile.setProfileEmbedding(value.vector());
                profile.setEmbeddingDimension(dimension);
                profile.setSourceRefs(value.candidate().sourceRefs());
                profile.setOrdinal(ordinal++);
                profileMapper.insert(profile);
                profiles.add(profile);
            }
            setMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseRouteProfileSet>()
                    .eq(KnowledgeBaseRouteProfileSet::getId, set.getId())
                    .set(KnowledgeBaseRouteProfileSet::getProfileCount, profiles.size())
                    .set(KnowledgeBaseRouteProfileSet::getStatus, READY)
                    .set(KnowledgeBaseRouteProfileSet::getIsActive, false)
                    .set(KnowledgeBaseRouteProfileSet::getCompletedAt, LocalDateTime.now()));
            set.setProfileCount(profiles.size());
            set.setStatus(READY);
        });
        return List.copyOf(profiles);
    }

    private boolean publishCacheThenActivate(KnowledgeBase kb, KnowledgeBaseRouteProfileSet set,
                                             List<KnowledgeBaseRouteProfile> profiles,
                                             long modelId, String modelIdentity) {
        if (profiles == null || profiles.isEmpty()) return false;
        KnowledgeRouteProfileBundle bundle = bundle(set, profiles);
        if (!cache.put(bundle)) return false;
        Boolean activated = transactions.execute(status -> {
            KnowledgeBase locked = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getUuid, kb.getUuid()).last("FOR UPDATE"));
            if (locked == null || value(locked.getRouteProfileGeneration()) != set.getGeneration()) {
                setMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseRouteProfileSet>()
                        .eq(KnowledgeBaseRouteProfileSet::getId, set.getId())
                        .set(KnowledgeBaseRouteProfileSet::getStatus, SUPERSEDED)
                        .set(KnowledgeBaseRouteProfileSet::getIsActive, false));
                return false;
            }
            setMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseRouteProfileSet>()
                    .eq(KnowledgeBaseRouteProfileSet::getKbUuid, kb.getUuid())
                    .eq(KnowledgeBaseRouteProfileSet::getIsActive, true)
                    .ne(KnowledgeBaseRouteProfileSet::getId, set.getId())
                    .set(KnowledgeBaseRouteProfileSet::getStatus, SUPERSEDED)
                    .set(KnowledgeBaseRouteProfileSet::getIsActive, false));
            setMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseRouteProfileSet>()
                    .eq(KnowledgeBaseRouteProfileSet::getId, set.getId())
                    .set(KnowledgeBaseRouteProfileSet::getStatus, ACTIVE)
                    .set(KnowledgeBaseRouteProfileSet::getIsActive, true)
                    .set(KnowledgeBaseRouteProfileSet::getActivatedAt, LocalDateTime.now()));
            int activatedRows = knowledgeBaseMapper.activateRouteProfile(
                    kb.getUuid(), set.getGeneration(), set.getUuid(),
                    set.getSourceManifestHash(), modelId, modelIdentity);
            if (activatedRows != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(activated);
    }

    private KnowledgeRouteProfileBundle bundle(KnowledgeBaseRouteProfileSet set,
                                               List<KnowledgeBaseRouteProfile> profiles) {
        List<KnowledgeRouteProfileBundle.Profile> payloadProfiles = profiles.stream()
                .map(profile -> new KnowledgeRouteProfileBundle.Profile(
                        profile.getProfileType(), profile.getProfileKey(), profile.getProfileText(),
                        codec.encode(profile.getProfileEmbedding())))
                .toList();
        return new KnowledgeRouteProfileBundle(set.getKbUuid(), set.getGeneration(), set.getUuid(),
                set.getSourceManifestHash(), value(set.getEmbeddingModelId()), set.getEmbeddingModelIdentity(),
                set.getEmbeddingDimension(), set.getGeneratorVersion(), payloadProfiles);
    }

    private void markFailed(String kbUuid, long generation, Exception exception) {
        transactions.executeWithoutResult(status -> {
            KnowledgeBaseRouteProfileSet set = selectSet(kbUuid, generation);
            if (set != null && !ACTIVE.equals(set.getStatus())) {
                setMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseRouteProfileSet>()
                        .eq(KnowledgeBaseRouteProfileSet::getId, set.getId())
                        .set(KnowledgeBaseRouteProfileSet::getStatus, FAILED)
                        .set(KnowledgeBaseRouteProfileSet::getIsActive, false)
                        .set(KnowledgeBaseRouteProfileSet::getErrorType, exception.getClass().getSimpleName())
                        .set(KnowledgeBaseRouteProfileSet::getErrorMessage,
                                StringUtils.substring(exception.getMessage(), 0, 2000)));
            }
            knowledgeBaseMapper.markRouteProfileFailed(kbUuid, generation);
        });
    }

    private List<KnowledgeBaseRouteProfile> loadProfiles(String setUuid) {
        return profileMapper.selectList(new LambdaQueryWrapper<KnowledgeBaseRouteProfile>()
                .eq(KnowledgeBaseRouteProfile::getProfileSetUuid, setUuid)
                .orderByAsc(KnowledgeBaseRouteProfile::getOrdinal));
    }

    private KnowledgeBase selectKnowledgeBase(String kbUuid) {
        return knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUuid, kbUuid));
    }

    private KnowledgeBaseRouteProfileSet selectSet(String kbUuid, long generation) {
        return setMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseRouteProfileSet>()
                .eq(KnowledgeBaseRouteProfileSet::getKbUuid, kbUuid)
                .eq(KnowledgeBaseRouteProfileSet::getGeneration, generation));
    }

    private long resolveModelId(String identity) {
        String[] parts = StringUtils.split(identity, ':');
        if (parts == null || parts.length < 2) return 0L;
        String platform = parts[0];
        String modelName = identity.substring(platform.length() + 1);
        List<AiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getPlatform, platform)
                .eq(AiModel::getName, modelName)
                .eq(AiModel::getType, "embedding")
                .eq(AiModel::getIsEnable, true)
                .last("LIMIT 1"));
        return models.isEmpty() ? 0L : models.get(0).getId();
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static long value(Integer value) {
        return value == null ? 0L : value;
    }

    public enum Outcome { COMPLETE, WAITING, RETRY, FAILED, SKIPPED }
}
