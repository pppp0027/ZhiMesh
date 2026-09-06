package com.pppp.zhimesh.common.rag.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.util.RedisTemplateUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.util.Set;

import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_ROUTE_PROFILE_BUILD_LOCK;
import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_ROUTE_PROFILE_REBUILD_SIGNAL;

/** Coalesces source/index changes and serializes KB-level profile builds across app instances. */
@Slf4j
@Component
public class KnowledgeRouteProfileCoordinator {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeRouteProfileBuildService buildService;
    private final StringRedisTemplate redis;
    private final RedisTemplateUtil lock;
    private final ZhiMeshProperties properties;

    public KnowledgeRouteProfileCoordinator(KnowledgeBaseMapper knowledgeBaseMapper,
                                            KnowledgeRouteProfileBuildService buildService,
                                            StringRedisTemplate redis,
                                            RedisTemplateUtil lock,
                                            ZhiMeshProperties properties) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.buildService = buildService;
        this.redis = redis;
        this.lock = lock;
        this.properties = properties;
    }

    /** Marks and returns the generation owned by one explicit derived-index batch. */
    @Transactional
    public long indexingRequested(String kbUuid) {
        if (kbUuid == null || kbUuid.isBlank()) return -1L;
        KnowledgeBase locked = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getUuid, kbUuid)
                        .last("FOR UPDATE"));
        if (locked == null || knowledgeBaseMapper.markRouteProfileStale(kbUuid) != 1) return -1L;
        return value(locked.getRouteProfileGeneration()) + 1L;
    }

    /** Queues the expensive build only after the owning asynchronous batch finishes. */
    public void indexingCompleted(String kbUuid, long generation) {
        signal(kbUuid, generation);
    }

    private void signal(String kbUuid, long generation) {
        if (kbUuid == null || kbUuid.isBlank() || generation <= 0) return;
        try {
            redis.opsForSet().add(KB_ROUTE_PROFILE_REBUILD_SIGNAL, encode(kbUuid, generation));
        } catch (RuntimeException exception) {
            log.warn("Unable to queue knowledge route-profile rebuild, kbUuid:{}, generation:{}",
                    kbUuid, generation, exception);
        }
    }

    public void knowledgeBaseDeleted(String kbUuid, long activeGeneration) {
        try {
            redis.opsForSet().remove(KB_ROUTE_PROFILE_REBUILD_SIGNAL, kbUuid);
        } catch (RuntimeException exception) {
            log.debug("Unable to remove deleted KB from the route-profile queue, kbUuid:{}", kbUuid, exception);
        }
        buildService.deleteByKnowledgeBase(kbUuid, activeGeneration);
    }

    @Scheduled(initialDelay = 15000,
            fixedDelayString = "${zhimesh.knowledge-scope-gate.rebuild-debounce-ms:5000}")
    public void processSignals() {
        if (!properties.getKnowledgeScopeGate().isEnabled()) return;
        Set<String> queued;
        try {
            queued = redis.opsForSet().members(KB_ROUTE_PROFILE_REBUILD_SIGNAL);
        } catch (RuntimeException exception) {
            log.warn("Unable to read knowledge route-profile rebuild queue", exception);
            return;
        }
        if (CollectionUtils.isEmpty(queued)) return;
        queued.stream().limit(20).forEach(this::processOne);
    }

    /** Recovers failed/stuck builds without making chat requests wait for repair. */
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void recoverIncompleteBuilds() {
        if (!properties.getKnowledgeScopeGate().isEnabled()) return;
        knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getRouteProfileStatus, "STALE", "BUILDING", "FAILED")
                        .eq(KnowledgeBase::getIsEnabled, true)
                        .orderByAsc(KnowledgeBase::getRouteProfileStatusChangeTime)
                        .last("LIMIT 100"))
                .forEach(kb -> signal(kb.getUuid(), value(kb.getRouteProfileGeneration())));
    }

    /** Refreshes Redis from the immutable ACTIVE release without detecting source changes. */
    @Scheduled(initialDelay = 300000, fixedDelay = 3600000)
    public void refreshActiveCaches() {
        if (!properties.getKnowledgeScopeGate().isEnabled()) return;
        knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getRouteProfileStatus, "READY")
                        .eq(KnowledgeBase::getIsEnabled, true)
                        .last("LIMIT 500"))
                .forEach(kb -> buildService.warmActive(kb.getUuid()));
    }

    private void processOne(String encodedSignal) {
        BuildSignal buildSignal = decode(encodedSignal);
        if (buildSignal == null) {
            redis.opsForSet().remove(KB_ROUTE_PROFILE_REBUILD_SIGNAL, encodedSignal);
            return;
        }
        String kbUuid = buildSignal.kbUuid();
        String lockKey = MessageFormat.format(KB_ROUTE_PROFILE_BUILD_LOCK, kbUuid);
        String clientId = UuidUtil.createShort();
        long leaseSeconds = Math.max(30L, properties.getKnowledgeScopeGate().getBuildLockSeconds());
        if (!lock.lock(lockKey, clientId, leaseSeconds)) return;
        try {
            redis.opsForSet().remove(KB_ROUTE_PROFILE_REBUILD_SIGNAL, encodedSignal);
            KnowledgeBase current = knowledgeBaseMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeBase>()
                            .eq(KnowledgeBase::getUuid, kbUuid));
            if (current == null
                    || value(current.getRouteProfileGeneration()) != buildSignal.generation()) {
                log.info("Discarding superseded route-profile signal, kbUuid:{}, generation:{}",
                        kbUuid, buildSignal.generation());
                return;
            }
            KnowledgeRouteProfileBuildService.Outcome outcome = buildService.buildOrWarm(kbUuid);
            if (outcome == KnowledgeRouteProfileBuildService.Outcome.WAITING
                    || outcome == KnowledgeRouteProfileBuildService.Outcome.RETRY) {
                signal(kbUuid, buildSignal.generation());
            }
        } catch (RuntimeException exception) {
            log.error("Knowledge route-profile signal processing failed, kbUuid:{}", kbUuid, exception);
            signal(kbUuid, buildSignal.generation());
        } finally {
            lock.unlock(lockKey, clientId);
        }
    }

    private static String encode(String kbUuid, long generation) {
        return kbUuid + "|" + generation;
    }

    private static BuildSignal decode(String value) {
        if (value == null) return null;
        int separator = value.lastIndexOf('|');
        if (separator <= 0 || separator == value.length() - 1) return null;
        try {
            long generation = Long.parseLong(value.substring(separator + 1));
            return generation > 0 ? new BuildSignal(value.substring(0, separator), generation) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private record BuildSignal(String kbUuid, long generation) {
    }
}
