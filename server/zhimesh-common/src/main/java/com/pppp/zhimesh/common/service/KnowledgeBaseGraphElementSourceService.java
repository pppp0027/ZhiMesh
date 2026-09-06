package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.entity.KnowledgeBaseGraphElementSource;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseGraphElementSourceMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MAX_METADATA_VALUE_LENGTH;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.KB_ITEM_UUID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.KB_UUID;

@Service
public class KnowledgeBaseGraphElementSourceService extends ServiceImpl<KnowledgeBaseGraphElementSourceMapper, KnowledgeBaseGraphElementSource> {
    public static final String VERTEX = "vertex";
    public static final String EDGE = "edge";
    public void replaceDocumentSources(String kbItemUuid) {
        lambdaUpdate()
                .eq(KnowledgeBaseGraphElementSource::getKbItemUuid, kbItemUuid)
                .remove();
    }

    /** Drops every provenance row of one knowledge base; used when the KB itself is deleted. */
    public void removeKnowledgeBaseSources(String kbUuid) {
        lambdaUpdate()
                .eq(KnowledgeBaseGraphElementSource::getKbUuid, kbUuid)
                .remove();
    }
    public void record(String kbUuid, String kbItemUuid, String segmentUuid, String type, String elementId) {
        record(kbUuid, kbItemUuid, segmentUuid, type, elementId, "", null,
                "", "", 0L, "");
    }

    public void record(String kbUuid, String kbItemUuid, String segmentUuid, String type,
                       String elementId, String description, Double weight,
                       String chunkSetUuid, String chunkUuid, Long graphModelId,
                       String graphIndexVersionUuid) {
        record(kbUuid, kbItemUuid, segmentUuid, type, elementId, description, weight,
                "", List.of(), Map.of(), null, "", null, "",
                chunkSetUuid, chunkUuid, graphModelId, graphIndexVersionUuid);
    }

    public void record(String kbUuid, String kbItemUuid, String segmentUuid, String type,
                       String elementId, String description, Double weight,
                       String canonicalName, List<String> aliases,
                       Map<String, Object> properties, Double salience,
                       String relationType, Boolean relationPolarity, String relationStatus,
                       String chunkSetUuid, String chunkUuid, Long graphModelId,
                       String graphIndexVersionUuid) {
        if (StringUtils.isBlank(elementId)) return;
        String aliasesJson = JsonUtil.toJson(aliases == null ? List.of() : aliases);
        String propertiesJson = JsonUtil.toJson(properties == null ? Map.of() : properties);
        KnowledgeBaseGraphElementSource existing = lambdaQuery()
                .eq(KnowledgeBaseGraphElementSource::getKbItemUuid, kbItemUuid)
                .eq(KnowledgeBaseGraphElementSource::getGraphSegmentUuid, segmentUuid)
                .eq(KnowledgeBaseGraphElementSource::getElementType, type)
                .eq(KnowledgeBaseGraphElementSource::getElementId, elementId)
                .one();
        if (existing != null) {
            lambdaUpdate()
                    .eq(KnowledgeBaseGraphElementSource::getId, existing.getId())
                    .set(KnowledgeBaseGraphElementSource::getContributionDescription,
                            appendUniqueLine(existing.getContributionDescription(), description))
                    .set(KnowledgeBaseGraphElementSource::getContributionWeight,
                            maxNullable(existing.getContributionWeight(), weight))
                    .set(KnowledgeBaseGraphElementSource::getContributionCanonicalName,
                            StringUtils.defaultString(canonicalName))
                    .set(KnowledgeBaseGraphElementSource::getContributionAliases, aliasesJson)
                    .set(KnowledgeBaseGraphElementSource::getContributionProperties, propertiesJson)
                    .set(KnowledgeBaseGraphElementSource::getContributionSalience, salience)
                    .set(KnowledgeBaseGraphElementSource::getRelationType,
                            StringUtils.defaultString(relationType))
                    .set(KnowledgeBaseGraphElementSource::getRelationPolarity, relationPolarity)
                    .set(KnowledgeBaseGraphElementSource::getRelationStatus,
                            StringUtils.defaultString(relationStatus))
                    .set(KnowledgeBaseGraphElementSource::getChunkSetUuid,
                            StringUtils.defaultString(chunkSetUuid))
                    .set(KnowledgeBaseGraphElementSource::getChunkUuid,
                            StringUtils.defaultString(chunkUuid))
                    .set(KnowledgeBaseGraphElementSource::getGraphModelId,
                            graphModelId == null ? 0L : graphModelId)
                    .set(KnowledgeBaseGraphElementSource::getGraphIndexVersionUuid,
                            StringUtils.defaultString(graphIndexVersionUuid))
                    .update();
            return;
        }
        KnowledgeBaseGraphElementSource source = new KnowledgeBaseGraphElementSource();
        source.setKbUuid(kbUuid);
        source.setKbItemUuid(kbItemUuid);
        source.setGraphSegmentUuid(segmentUuid);
        source.setChunkSetUuid(StringUtils.defaultString(chunkSetUuid));
        source.setChunkUuid(StringUtils.defaultString(chunkUuid));
        source.setGraphModelId(graphModelId == null ? 0L : graphModelId);
        source.setGraphIndexVersionUuid(StringUtils.defaultString(graphIndexVersionUuid));
        source.setElementType(type);
        source.setElementId(elementId);
        source.setContributionDescription(StringUtils.defaultString(description));
        source.setContributionWeight(weight);
        source.setContributionCanonicalName(StringUtils.defaultString(canonicalName));
        source.setContributionAliases(aliasesJson);
        source.setContributionProperties(propertiesJson);
        source.setContributionSalience(salience);
        source.setRelationType(StringUtils.defaultString(relationType));
        source.setRelationPolarity(relationPolarity);
        source.setRelationStatus(StringUtils.defaultString(relationStatus));
        save(source);
    }
    public List<String> elementIds(String kbItemUuid, String type) { return lambdaQuery().eq(KnowledgeBaseGraphElementSource::getKbItemUuid, kbItemUuid).eq(KnowledgeBaseGraphElementSource::getElementType, type).list().stream().map(KnowledgeBaseGraphElementSource::getElementId).distinct().toList(); }
    public Set<String> elementIdsReferencedByOtherDocuments(String kbUuid, String kbItemUuid, String type, List<String> elementIds) {
        if (elementIds == null || elementIds.isEmpty()) return Set.of();
        return lambdaQuery()
                .eq(KnowledgeBaseGraphElementSource::getKbUuid, kbUuid)
                .ne(KnowledgeBaseGraphElementSource::getKbItemUuid, kbItemUuid)
                .eq(KnowledgeBaseGraphElementSource::getElementType, type)
                .in(KnowledgeBaseGraphElementSource::getElementId, elementIds)
                .list()
                .stream()
                .map(KnowledgeBaseGraphElementSource::getElementId)
                .collect(Collectors.toSet());
    }
    public boolean hasSources(String kbItemUuid) { return lambdaQuery().eq(KnowledgeBaseGraphElementSource::getKbItemUuid, kbItemUuid).exists(); }

    public ContributionAggregate aggregate(String type, String elementId) {
        return aggregate(type, elementId, null);
    }

    public ContributionAggregate aggregateExcludingDocument(String type, String elementId,
                                                             String excludedKbItemUuid) {
        return aggregate(type, elementId, excludedKbItemUuid);
    }

    private ContributionAggregate aggregate(String type, String elementId, String excludedKbItemUuid) {
        List<KnowledgeBaseGraphElementSource> sources = lambdaQuery()
                .eq(KnowledgeBaseGraphElementSource::getElementType, type)
                .eq(KnowledgeBaseGraphElementSource::getElementId, elementId)
                .ne(StringUtils.isNotBlank(excludedKbItemUuid),
                        KnowledgeBaseGraphElementSource::getKbItemUuid, excludedKbItemUuid)
                .orderByAsc(KnowledgeBaseGraphElementSource::getId)
                .list();
        return aggregateSources(sources);
    }

    static ContributionAggregate aggregateSources(List<KnowledgeBaseGraphElementSource> sources) {
        if (sources.isEmpty()) {
            return ContributionAggregate.empty();
        }
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        LinkedHashSet<String> segmentUuids = new LinkedHashSet<>();
        LinkedHashSet<String> itemUuids = new LinkedHashSet<>();
        double maxWeight = 0D;
        String canonicalName = "";
        LinkedHashSet<String> canonicalCandidates = new LinkedHashSet<>();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        Double salience = null;
        String relationType = "";
        Boolean relationPolarity = null;
        String relationStatus = "";
        for (KnowledgeBaseGraphElementSource source : sources) {
            appendLines(descriptions, source.getContributionDescription());
            if (StringUtils.isNotBlank(source.getGraphSegmentUuid())) {
                segmentUuids.add(source.getGraphSegmentUuid().trim());
            }
            if (StringUtils.isNotBlank(source.getKbItemUuid())) {
                itemUuids.add(source.getKbItemUuid().trim());
            }
            if (source.getContributionWeight() != null) {
                maxWeight = Math.max(maxWeight, clampWeight(source.getContributionWeight()));
            }
            if (StringUtils.isNotBlank(source.getContributionCanonicalName())) {
                String candidate = source.getContributionCanonicalName().trim();
                canonicalCandidates.add(candidate);
                if (isMoreSpecificCanonical(candidate, canonicalName)) {
                    canonicalName = candidate;
                }
            }
            aliases.addAll(parseAliases(source.getContributionAliases()));
            properties.putAll(parseProperties(source.getContributionProperties()));
            if (source.getContributionSalience() != null) {
                salience = salience == null ? source.getContributionSalience()
                        : Math.max(salience, source.getContributionSalience());
            }
            if (StringUtils.isBlank(relationType) && StringUtils.isNotBlank(source.getRelationType())) {
                relationType = source.getRelationType().trim();
            }
            if (relationPolarity == null && source.getRelationPolarity() != null) {
                relationPolarity = source.getRelationPolarity();
            }
            if (StringUtils.isBlank(relationStatus) && StringUtils.isNotBlank(source.getRelationStatus())) {
                relationStatus = source.getRelationStatus().trim();
            }
        }
        aliases.addAll(canonicalCandidates);
        aliases.remove(canonicalName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(KB_UUID, sources.get(0).getKbUuid());
        metadata.put(KB_ITEM_UUID, boundedCsv(itemUuids));
        return new ContributionAggregate(String.join("\n", descriptions),
                String.join(",", segmentUuids), maxWeight, Map.copyOf(metadata), sources.size(),
                canonicalName, List.copyOf(aliases), Map.copyOf(properties), salience,
                relationType, relationPolarity, relationStatus);
    }

    private static String appendUniqueLine(String existing, String value) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        appendLines(lines, existing);
        appendLines(lines, value);
        return String.join("\n", lines);
    }

    private static void appendLines(Set<String> target, String value) {
        if (StringUtils.isBlank(value)) return;
        Arrays.stream(value.split("\\R"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .forEach(target::add);
    }

    private static Double maxNullable(Double left, Double right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.max(clampWeight(left), clampWeight(right));
    }

    private static double clampWeight(double value) {
        return Math.max(0D, Math.min(10D, value));
    }

    private static boolean isMoreSpecificCanonical(String candidate, String current) {
        return StringUtils.isBlank(current) || candidate.length() > current.length();
    }

    private static List<String> parseAliases(String json) {
        if (StringUtils.isBlank(json)) return List.of();
        List<String> values = JsonUtil.toList(json, String.class);
        return values == null ? List.of() : values.stream()
                .filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
    }

    private static Map<String, Object> parseProperties(String json) {
        if (StringUtils.isBlank(json)) return Map.of();
        try {
            Map<String, Object> parsed = new LinkedHashMap<>(JsonUtil.toMap(json));
            parsed.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
            return parsed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static String boundedCsv(LinkedHashSet<String> values) {
        String result = String.join(",", values);
        while (result.length() > MAX_METADATA_VALUE_LENGTH && values.size() > 1) {
            values.remove(values.iterator().next());
            result = String.join(",", values);
        }
        return result.length() <= MAX_METADATA_VALUE_LENGTH
                ? result : result.substring(result.length() - MAX_METADATA_VALUE_LENGTH);
    }

    public record ContributionAggregate(String description, String textSegmentId, double weight,
                                        Map<String, Object> metadata, int sourceCount,
                                        String canonicalName, List<String> aliases,
                                        Map<String, Object> properties, Double salience,
                                        String relationType, Boolean polarity, String status) {
        public ContributionAggregate(String description, String textSegmentId, double weight,
                                     Map<String, Object> metadata, int sourceCount) {
            this(description, textSegmentId, weight, metadata, sourceCount,
                    "", List.of(), Map.of(), null, "", null, "");
        }

        public static ContributionAggregate empty() {
            return new ContributionAggregate("", "", 0D, Map.of(), 0,
                    "", List.of(), Map.of(), null, "", null, "");
        }

        public boolean isEmpty() {
            return sourceCount == 0;
        }
    }

    /** Returns the graph elements that produced each selected source segment. */
    public Map<String, Set<String>> elementIdsBySegmentUuids(Set<String> kbUuids,
                                                              List<String> segmentUuids) {
        if (kbUuids == null || kbUuids.isEmpty() || segmentUuids == null || segmentUuids.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        lambdaQuery()
                .in(KnowledgeBaseGraphElementSource::getKbUuid, kbUuids)
                .in(KnowledgeBaseGraphElementSource::getGraphSegmentUuid, segmentUuids)
                .list()
                .forEach(source -> result.computeIfAbsent(
                                source.getGraphSegmentUuid(), ignored -> new LinkedHashSet<>())
                        .add(source.getElementId()));
        return result.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /**
     * Resolves graph elements to their source segment UUIDs using batched queries.
     * Edge evidence is intentionally considered before vertex evidence because it
     * normally carries the relation needed to answer the question.
     */
    public List<String> sourceSegmentUuids(List<String> edgeIds, List<String> vertexIds, int limit) {
        return sourceSegmentUuids(null, edgeIds, vertexIds, limit, limit);
    }

    /**
     * Resolves a wider but bounded provenance pool for query-time ranking.
     * A per-element cap prevents one high-degree generic vertex from consuming
     * the complete candidate budget. When available, kbUuid also prevents
     * provenance from another knowledge base entering the pool.
     */
    public List<String> sourceSegmentUuids(String kbUuid, List<String> edgeIds,
                                           List<String> vertexIds, int limit,
                                           int perElementLimit) {
        Set<String> scope = kbUuid == null || kbUuid.isBlank() ? null : Set.of(kbUuid.trim());
        return sourceSegmentUuidsInternal(scope, edgeIds, vertexIds, limit, perElementLimit);
    }

    /**
     * Resolves provenance for a multi-knowledge-base graph query without
     * collapsing the scope to the first KB encountered in the graph.
     */
    public List<String> sourceSegmentUuidsForKnowledgeBases(Set<String> kbUuids,
                                                              List<String> edgeIds,
                                                              List<String> vertexIds,
                                                              int limit,
                                                              int perElementLimit) {
        Set<String> scope = kbUuids == null ? Set.of() : kbUuids.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // Query-time graph provenance must fail closed when the graph did not
        // carry any KB identity. The legacy single-KB overload below retains
        // its historical unrestricted behavior for offline callers.
        if (scope.isEmpty()) return List.of();
        return sourceSegmentUuidsInternal(scope, edgeIds, vertexIds, limit, perElementLimit);
    }

    private List<String> sourceSegmentUuidsInternal(Set<String> scope, List<String> edgeIds,
                                                     List<String> vertexIds, int limit,
                                                     int perElementLimit) {
        LinkedHashSet<String> segmentUuids = new LinkedHashSet<>();
        int safeLimit = Math.max(1, limit);
        int safePerElementLimit = Math.max(1, perElementLimit);
        appendSourceSegments(segmentUuids, scope, EDGE, edgeIds, safeLimit, safePerElementLimit);
        appendSourceSegments(segmentUuids, scope, VERTEX, vertexIds, safeLimit, safePerElementLimit);
        return new ArrayList<>(segmentUuids);
    }

    private void appendSourceSegments(LinkedHashSet<String> result, Set<String> kbUuids, String type,
                                      List<String> elementIds, int limit, int perElementLimit) {
        if (elementIds == null || elementIds.isEmpty() || result.size() >= limit) {
            return;
        }
        List<KnowledgeBaseGraphElementSource> sources = lambdaQuery()
                .in(kbUuids != null && !kbUuids.isEmpty(),
                        KnowledgeBaseGraphElementSource::getKbUuid, kbUuids)
                .eq(KnowledgeBaseGraphElementSource::getElementType, type)
                .in(KnowledgeBaseGraphElementSource::getElementId, elementIds)
                .list();
        Map<String, List<String>> segmentsByElement = new LinkedHashMap<>();
        for (KnowledgeBaseGraphElementSource source : sources) {
            segmentsByElement.computeIfAbsent(source.getElementId(), ignored -> new ArrayList<>())
                    .add(source.getGraphSegmentUuid());
        }
        for (String elementId : elementIds) {
            List<String> elementSegments = segmentsByElement.getOrDefault(elementId, List.of());
            int acceptedForElement = 0;
            for (String segmentUuid : elementSegments) {
                if (result.add(segmentUuid)) {
                    acceptedForElement++;
                }
                if (result.size() >= limit) {
                    return;
                }
                if (acceptedForElement >= perElementLimit) {
                    break;
                }
            }
        }
    }
}
