package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fail-closed readiness gate evaluated against every authorized knowledge base. */
@Service
public class Bm25ReadinessService {

    private final Bm25Repository repository;
    private final ZhiMeshProperties properties;
    /**
     * 就绪结果按 KB 本地缓存（见 zhimesh.retrieval.bm25.readiness-cache-ttl-ms）。
     * 该门控在每个聊天请求的关键路径上执行，不缓存时每次一趟远程 SQL；
     * 索引构建低频，短 TTL 两个陈旧方向都安全（未就绪→少走 BM25 一路；
     * 已就绪但索引失效→检索器报错被通道失败兜底吞掉）。
     */
    private final Map<String, ReadinessEntry> readinessCache = new ConcurrentHashMap<>();

    private record ReadinessEntry(boolean ready, String analyzerVersion, long expiresAt) {
        boolean stale(String analyzerVersion, long now) {
            return expiresAt < now || !Objects.equals(analyzerVersion, this.analyzerVersion);
        }
    }

    public Bm25ReadinessService(Bm25Repository repository, ZhiMeshProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Set<String> readyKnowledgeBases(Collection<String> kbUuids) {
        Set<String> scope = sanitize(kbUuids);
        if (!properties.getRetrieval().getBm25().isEnabled() || scope.isEmpty()) {
            return Set.of();
        }
        String analyzerVersion = properties.getRetrieval().getBm25().getAnalyzerVersion();
        long ttlMs = properties.getRetrieval().getBm25().getReadinessCacheTtlMs();
        if (ttlMs <= 0) {
            return repository.readyKnowledgeBases(scope, analyzerVersion);
        }
        long now = System.currentTimeMillis();
        Set<String> ready = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>(scope.size());
        for (String kbUuid : scope) {
            ReadinessEntry entry = readinessCache.get(kbUuid);
            if (entry != null && !entry.stale(analyzerVersion, now)) {
                if (entry.ready()) {
                    ready.add(kbUuid);
                }
            } else {
                unresolved.add(kbUuid);
            }
        }
        if (!unresolved.isEmpty()) {
            Set<String> fetched = repository.readyKnowledgeBases(unresolved, analyzerVersion);
            long expiresAt = now + ttlMs;
            for (String kbUuid : unresolved) {
                readinessCache.put(kbUuid,
                        new ReadinessEntry(fetched.contains(kbUuid), analyzerVersion, expiresAt));
                if (fetched.contains(kbUuid)) {
                    ready.add(kbUuid);
                }
            }
        }
        return Set.copyOf(ready);
    }

    public void requireReady(Collection<String> kbUuids) {
        Set<String> scope = sanitize(kbUuids);
        if (scope.isEmpty()) {
            throw new Bm25UnavailableException("BM25 retrieval requires an authorized knowledge-base scope");
        }
        if (!properties.getRetrieval().getBm25().isEnabled()) {
            throw new Bm25UnavailableException("BM25 retrieval is disabled");
        }
        Set<String> ready = readyKnowledgeBases(scope);
        if (!ready.containsAll(scope)) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(scope);
            missing.removeAll(ready);
            throw new Bm25UnavailableException(
                    "BM25 index is not ready for knowledge bases: " + String.join(",", missing));
        }
    }

    private static Set<String> sanitize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(result::add);
        return Set.copyOf(result);
    }
}
