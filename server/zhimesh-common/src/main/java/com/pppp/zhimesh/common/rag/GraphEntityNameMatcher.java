package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.util.ZhiMeshStringUtil;
import com.pppp.zhimesh.common.vo.GraphVertex;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class GraphEntityNameMatcher {

    private static final double MIN_FUZZY_SCORE = 0.80D;

    private GraphEntityNameMatcher() {
    }

    static List<GraphVertex> match(Collection<String> entities, List<GraphVertex> candidates, int limit) {
        Set<String> normalizedEntities = new LinkedHashSet<>();
        for (String entity : entities) {
            String normalized = normalize(entity);
            if (StringUtils.isNotBlank(normalized)) {
                normalizedEntities.add(normalized);
            }
        }
        return candidates.stream()
                .filter(vertex -> normalizedEntities.stream()
                        .mapToDouble(entity -> vertexIdentityNames(vertex).stream()
                                .mapToDouble(name -> similarity(entity, normalize(name)))
                                .max().orElse(0D))
                        .max()
                        .orElse(0D) >= MIN_FUZZY_SCORE)
                .sorted(Comparator.comparingDouble((GraphVertex vertex) -> normalizedEntities.stream()
                        .mapToDouble(entity -> vertexIdentityNames(vertex).stream()
                                .mapToDouble(name -> similarity(entity, normalize(name)))
                                .max().orElse(0D))
                        .max()
                        .orElse(0D)).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Finds graph entities explicitly mentioned in the question without an LLM.
     * Longer names are preferred to avoid a generic short name shadowing a
     * more specific entity name.
     */
    static List<GraphVertex> matchDirectMentions(String question, List<GraphVertex> candidates, int limit) {
        String normalizedQuestion = normalize(question);
        if (StringUtils.isBlank(normalizedQuestion)) {
            return List.of();
        }
        return candidates.stream()
                .filter(vertex -> {
                    return vertexIdentityNames(vertex).stream().map(GraphEntityNameMatcher::normalize)
                            .anyMatch(name -> name.length() >= 2 && normalizedQuestion.contains(name));
                })
                .sorted(Comparator.comparingInt((GraphVertex vertex) -> vertexIdentityNames(vertex)
                        .stream().map(GraphEntityNameMatcher::normalize)
                        .filter(normalizedQuestion::contains).mapToInt(String::length)
                        .max().orElse(0)).reversed())
                .limit(limit)
                .toList();
    }

    static String normalize(String value) {
        if (StringUtils.isBlank(value)) {
            return StringUtils.EMPTY;
        }
        return ZhiMeshStringUtil.removeSpecialChar(value).replaceAll("\\s+", "").toUpperCase();
    }

    private static List<String> vertexIdentityNames(GraphVertex vertex) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(vertex.getName());
        names.add(vertex.getCanonicalName());
        if (vertex.getAliases() != null) names.addAll(vertex.getAliases());
        names.removeIf(StringUtils::isBlank);
        return List.copyOf(names);
    }

    static double similarity(String left, String right) {
        if (StringUtils.isBlank(left) || StringUtils.isBlank(right)) {
            return 0D;
        }
        if (left.equals(right)) {
            return 1D;
        }
        if (left.contains(right) || right.contains(left)) {
            return 1D;
        }
        int distance = levenshteinDistance(left, right);
        return 1D - (double) distance / Math.max(left.length(), right.length());
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitutionCost = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
                current[rightIndex] = Math.min(
                        Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1),
                        previous[rightIndex - 1] + substitutionCost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
