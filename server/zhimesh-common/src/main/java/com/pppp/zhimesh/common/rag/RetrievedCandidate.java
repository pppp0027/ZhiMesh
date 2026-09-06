package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A route-neutral retrieval candidate with evidence provenance and scores. */
final class RetrievedCandidate {

    static final String ROUTE = "retrieval_route";
    static final String ROUTES = "retrieval_routes";
    static final String CONTENT_TYPE = "content_type";
    static final String ORIGINAL_SEGMENT = "original_segment";
    static final String GRAPH_RELATION = "graph_relation";
    static final String GRAPH_SEGMENT_UUID = "graph_segment_uuid";
    static final String SEGMENT_UUID = "segment_uuid";
    static final String GRAPH_ELEMENT_ID = "graph_element_id";
    static final String GRAPH_ELEMENT_IDS = "graph_element_ids";
    static final String SOURCE_DOCUMENT_IDS = "source_document_ids";
    static final String SOURCE_SEGMENT_IDS = "source_segment_ids";
    static final String VECTOR_SCORE = "vector_score";
    static final String BM25_SCORE = "bm25_score";
    static final String VECTOR_RANK = "vector_rank";
    static final String GRAPH_RANK = "graph_rank";
    static final String BM25_RANK = "bm25_rank";
    static final String ROUTE_RANK = "route_rank";
    static final String ROUTE_RANKS = "route_ranks";
    static final String SOURCE_TYPE = "source_type";
    static final String ROUTE_TYPE = "route_type";
    static final String SOURCE_INSTANCE_ID = "source_instance_id";
    static final String RRF_SCORE = "rrf_score";
    static final String RERANK_SCORE = "rerank_score";

    private final String text;
    private final Map<String, Object> metadata;
    private final Set<String> routes = new LinkedHashSet<>();
    private final Set<String> graphElementIds = new LinkedHashSet<>();
    private final Set<String> sourceDocumentIds = new LinkedHashSet<>();
    private final Set<String> sourceSegmentIds = new LinkedHashSet<>();
    private final Map<String, Integer> routeRanks = new LinkedHashMap<>();
    private Double vectorScore;
    private Double bm25Score;
    private Double rrfScore;
    private Double rerankScore;

    private RetrievedCandidate(Content content) {
        this.text = content.textSegment().text();
        this.metadata = new LinkedHashMap<>(content.textSegment().metadata().toMap());
        addCsvValues(routes, metadata.get(ROUTES));
        addCsvValues(routes, metadata.get(ROUTE));
        addCsvValues(graphElementIds, metadata.get(GRAPH_ELEMENT_IDS));
        addCsvValues(graphElementIds, metadata.get(GRAPH_ELEMENT_ID));
        addCsvValues(sourceDocumentIds, metadata.get(SOURCE_DOCUMENT_IDS));
        addCsvValues(sourceDocumentIds, metadata.get(ZhiMeshConstant.MetadataKey.KB_ITEM_UUID));
        addCsvValues(sourceSegmentIds, metadata.get(SOURCE_SEGMENT_IDS));
        addCsvValues(sourceSegmentIds, metadata.get(ZhiMeshConstant.MetadataKey.CHUNK_UUID));
        addCsvValues(sourceSegmentIds, metadata.get(SEGMENT_UUID));
        addCsvValues(sourceSegmentIds, metadata.get(GRAPH_SEGMENT_UUID));
        this.vectorScore = doubleValue(metadata.get(VECTOR_SCORE));
        this.bm25Score = doubleValue(metadata.get(BM25_SCORE));
        Integer legacyVectorRank = integerValue(metadata.get(VECTOR_RANK));
        Integer legacyGraphRank = integerValue(metadata.get(GRAPH_RANK));
        Integer legacyBm25Rank = integerValue(metadata.get(BM25_RANK));
        if (legacyVectorRank != null) routeRanks.put("vector", legacyVectorRank);
        if (legacyGraphRank != null) routeRanks.put("graph", legacyGraphRank);
        if (legacyBm25Rank != null) routeRanks.put("bm25", legacyBm25Rank);
    }

    static RetrievedCandidate from(Content content, String route, int rank) {
        RetrievedCandidate candidate = new RetrievedCandidate(content);
        candidate.routes.add(route);
        candidate.routeRanks.merge(route, rank, Math::min);
        return candidate;
    }

    void merge(RetrievedCandidate other) {
        routes.addAll(other.routes);
        graphElementIds.addAll(other.graphElementIds);
        sourceDocumentIds.addAll(other.sourceDocumentIds);
        sourceSegmentIds.addAll(other.sourceSegmentIds);
        metadata.putAll(other.metadata);
        other.routeRanks.forEach((route, rank) -> routeRanks.merge(route, rank, Math::min));
        if (vectorScore == null || other.vectorScore != null && other.vectorScore > vectorScore) {
            vectorScore = other.vectorScore;
        }
        if (bm25Score == null || other.bm25Score != null && other.bm25Score > bm25Score) {
            bm25Score = other.bm25Score;
        }
    }

    void calculateRrfScore() {
        double score = 0D;
        for (Integer rank : routeRanks.values()) score += 1D / (60D + rank);
        rrfScore = score;
    }

    void setRerankScore(double score) {
        rerankScore = score;
    }

    String text() { return text; }
    String documentId() { return sourceDocumentIds.stream().findFirst().orElse(null); }
    String contentType() { return StringUtils.defaultIfBlank(stringValue(metadata.get(CONTENT_TYPE)), ORIGINAL_SEGMENT); }
    Integer vectorRank() { return routeRanks.get("vector"); }
    Double vectorScore() { return vectorScore; }
    Double bm25Score() { return bm25Score; }
    Object metadataValue(String key) { return metadata.get(key); }
    Map<String, Integer> routeRanks() { return Map.copyOf(routeRanks); }
    Double rrfScore() { return rrfScore; }
    Double rerankScore() { return rerankScore; }

    Content toContent() {
        Map<String, Object> output = new LinkedHashMap<>(metadata);
        output.remove(ROUTE);
        output.put(ROUTES, String.join(",", routes));
        if (!graphElementIds.isEmpty()) output.put(GRAPH_ELEMENT_IDS, String.join(",", graphElementIds));
        if (!sourceDocumentIds.isEmpty()) output.put(SOURCE_DOCUMENT_IDS, String.join(",", sourceDocumentIds));
        if (!sourceSegmentIds.isEmpty()) output.put(SOURCE_SEGMENT_IDS, String.join(",", sourceSegmentIds));
        Integer vectorRank = routeRanks.get("vector");
        Integer graphRank = routeRanks.get("graph");
        Integer bm25Rank = routeRanks.get("bm25");
        if (vectorRank != null) output.put(VECTOR_RANK, vectorRank);
        if (graphRank != null) output.put(GRAPH_RANK, graphRank);
        if (bm25Rank != null) output.put(BM25_RANK, bm25Rank);
        if (routeRanks.size() == 1) output.put(ROUTE_RANK, routeRanks.values().iterator().next());
        if (!routeRanks.isEmpty()) output.put(ROUTE_RANKS, routeRanks.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(",")));
        if (routes.size() == 1) output.put(ROUTE_TYPE, routes.iterator().next());
        if (vectorScore != null) output.put(VECTOR_SCORE, vectorScore);
        if (bm25Score != null) output.put(BM25_SCORE, bm25Score);
        if (rrfScore != null) output.put(RRF_SCORE, rrfScore);
        if (rerankScore != null) output.put(RERANK_SCORE, rerankScore);
        return Content.from(TextSegment.from(text, new Metadata(output)));
    }

    RetrievedCandidate withText(String replacement) {
        Content replacementContent = Content.from(TextSegment.from(replacement, new Metadata(metadata)));
        RetrievedCandidate candidate = new RetrievedCandidate(replacementContent);
        candidate.routes.addAll(routes);
        candidate.graphElementIds.addAll(graphElementIds);
        candidate.sourceDocumentIds.addAll(sourceDocumentIds);
        candidate.sourceSegmentIds.addAll(sourceSegmentIds);
        candidate.routeRanks.putAll(routeRanks);
        candidate.vectorScore = vectorScore;
        candidate.bm25Score = bm25Score;
        candidate.rrfScore = rrfScore;
        candidate.rerankScore = rerankScore;
        return candidate;
    }

    private static void addCsvValues(Set<String> target, Object value) {
        if (value == null) return;
        for (String item : String.valueOf(value).split(",")) {
            if (StringUtils.isNotBlank(item)) target.add(item.trim());
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
}
