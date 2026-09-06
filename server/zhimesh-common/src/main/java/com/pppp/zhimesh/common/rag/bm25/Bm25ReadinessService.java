package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fail-closed readiness gate evaluated against every authorized knowledge base. */
@Service
public class Bm25ReadinessService {

    private final Bm25Repository repository;
    private final ZhiMeshProperties properties;

    public Bm25ReadinessService(Bm25Repository repository, ZhiMeshProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Set<String> readyKnowledgeBases(Collection<String> kbUuids) {
        Set<String> scope = sanitize(kbUuids);
        if (!properties.getRetrieval().getBm25().isEnabled() || scope.isEmpty()) {
            return Set.of();
        }
        return repository.readyKnowledgeBases(
                scope, properties.getRetrieval().getBm25().getAnalyzerVersion());
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
