package com.pppp.zhimesh.common.rag.profile;

import dev.langchain4j.data.embedding.Embedding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic farthest-point medoid selection over real, auditable candidates. */
@Component
public class KnowledgeRouteProfileSelector {

    public List<Selected> select(List<KnowledgeRouteProfileCandidate> candidates,
                                 List<Embedding> embeddings, int limit) {
        if (candidates == null || embeddings == null || candidates.size() != embeddings.size()) {
            throw new IllegalArgumentException("Profile candidates and embeddings must be aligned");
        }
        if (candidates.isEmpty()) return List.of();
        int selectedLimit = Math.min(Math.max(1, limit), candidates.size());
        List<float[]> normalized = embeddings.stream()
                .map(embedding -> RouteProfileVectorMath.normalize(embedding.vector())).toList();
        List<Integer> selected = new ArrayList<>();
        Set<Integer> selectedSet = new HashSet<>();
        selected.add(0);
        selectedSet.add(0);
        while (selected.size() < selectedLimit) {
            int bestIndex = -1;
            double bestDistance = -1D;
            for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                if (selectedSet.contains(candidateIndex)) continue;
                double nearestDistance = Double.POSITIVE_INFINITY;
                for (int selectedIndex : selected) {
                    nearestDistance = Math.min(nearestDistance,
                            1D - RouteProfileVectorMath.dot(
                                    normalized.get(candidateIndex), normalized.get(selectedIndex)));
                }
                if (nearestDistance > bestDistance) {
                    bestDistance = nearestDistance;
                    bestIndex = candidateIndex;
                }
            }
            if (bestIndex < 0) break;
            selected.add(bestIndex);
            selectedSet.add(bestIndex);
        }
        List<Selected> result = new ArrayList<>();
        for (int index : selected) result.add(new Selected(candidates.get(index), normalized.get(index)));
        return List.copyOf(result);
    }

    public record Selected(KnowledgeRouteProfileCandidate candidate, float[] vector) {
    }
}
