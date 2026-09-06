package com.pppp.zhimesh.common.rag.bm25;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Online ContentRetriever backed by PostgreSQL Okapi BM25 scoring. */
public class Bm25ContentRetriever implements ContentRetriever {

    public static final String BM25_SCORE = "bm25_score";
    public static final String BM25_RANK = "bm25_rank";
    public static final String RAW_SCORE = "raw_score";
    public static final String CHUNK_UUID = "chunk_uuid";
    public static final String CHUNK_SET_UUID = "chunk_set_uuid";
    public static final String INDEX_BUILD_UUID = "index_build_uuid";
    public static final String SOURCE_DOCUMENT_IDS = "source_document_ids";
    public static final String SOURCE_SEGMENT_IDS = "source_segment_ids";

    private final Bm25Repository repository;
    private final Bm25Tokenizer tokenizer;
    private final Bm25ReadinessService readinessService;
    private final Set<String> knowledgeBaseUuids;
    private final int maxResults;
    private final int maxQueryTerms;
    private final double k1;
    private final double b;
    private final double maxDocumentFrequencyRatio;
    private final int documentFrequencyFilterMinDocuments;
    private List<String> retrievedTerms = List.of();
    private List<Bm25RetrievedHit> retrievedHits = List.of();

    public Bm25ContentRetriever(Bm25Repository repository,
                                Bm25Tokenizer tokenizer,
                                Bm25ReadinessService readinessService,
                                Set<String> knowledgeBaseUuids,
                                int maxResults,
                                int maxQueryTerms,
                                double k1,
                                double b) {
        this(repository, tokenizer, readinessService, knowledgeBaseUuids, maxResults,
                maxQueryTerms, k1, b, 0.85D, 20);
    }

    public Bm25ContentRetriever(Bm25Repository repository,
                                Bm25Tokenizer tokenizer,
                                Bm25ReadinessService readinessService,
                                Set<String> knowledgeBaseUuids,
                                int maxResults,
                                int maxQueryTerms,
                                double k1,
                                double b,
                                double maxDocumentFrequencyRatio,
                                int documentFrequencyFilterMinDocuments) {
        this.repository = repository;
        this.tokenizer = tokenizer;
        this.readinessService = readinessService;
        this.knowledgeBaseUuids = sanitizeScope(knowledgeBaseUuids);
        this.maxResults = Math.max(1, maxResults);
        this.maxQueryTerms = Math.max(1, maxQueryTerms);
        if (!Double.isFinite(k1) || k1 <= 0D) {
            throw new IllegalArgumentException("BM25 k1 must be finite and greater than zero");
        }
        if (!Double.isFinite(b) || b < 0D || b > 1D) {
            throw new IllegalArgumentException("BM25 b must be between zero and one");
        }
        if (!Double.isFinite(maxDocumentFrequencyRatio)
                || maxDocumentFrequencyRatio <= 0D || maxDocumentFrequencyRatio > 1D) {
            throw new IllegalArgumentException(
                    "BM25 maxDocumentFrequencyRatio must be between zero (exclusive) and one");
        }
        this.k1 = k1;
        this.b = b;
        this.maxDocumentFrequencyRatio = maxDocumentFrequencyRatio;
        this.documentFrequencyFilterMinDocuments = Math.max(1, documentFrequencyFilterMinDocuments);
    }

    @Override
    public List<Content> retrieve(Query query) {
        retrievedTerms = List.of();
        retrievedHits = List.of();
        String text = query == null ? null : query.text();
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        if (knowledgeBaseUuids.isEmpty()) {
            throw new Bm25UnavailableException(
                    "BM25 retrieval requires an authorized knowledge-base scope");
        }
        List<String> terms = tokenizer.analyzeQuery(text).distinctTerms(maxQueryTerms);
        if (terms.isEmpty()) {
            return List.of();
        }
        readinessService.requireReady(knowledgeBaseUuids);
        retrievedTerms = List.copyOf(terms);
        List<Bm25SearchHit> hits = repository.search(
                knowledgeBaseUuids, terms, tokenizer.analyzerVersion(), k1, b, maxResults,
                maxDocumentFrequencyRatio, documentFrequencyFilterMinDocuments);
        List<Bm25RetrievedHit> provenance = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            Bm25SearchHit hit = hits.get(index);
            provenance.add(new Bm25RetrievedHit(
                    hit.chunkUuid(), hit.kbUuid(), hit.kbItemUuid(), hit.content(), hit.score(), index + 1));
        }
        retrievedHits = List.copyOf(provenance);
        return java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> toContent(hits.get(index), index + 1))
                .toList();
    }

    public Set<String> knowledgeBaseUuids() {
        return knowledgeBaseUuids;
    }

    public List<String> getRetrievedTerms() {
        return retrievedTerms;
    }

    public List<Bm25RetrievedHit> getRetrievedHits() {
        return retrievedHits;
    }

    /** Removes raw BM25 hits that were rejected during final evidence selection. */
    public void retainRetrievedHits(Set<String> acceptedChunkUuids) {
        if (acceptedChunkUuids == null || acceptedChunkUuids.isEmpty()) {
            retrievedTerms = List.of();
            retrievedHits = List.of();
            return;
        }
        retrievedHits = retrievedHits.stream()
                .filter(hit -> acceptedChunkUuids.contains(hit.chunkUuid()))
                .toList();
        if (retrievedHits.isEmpty()) retrievedTerms = List.of();
    }

    public record Bm25RetrievedHit(String chunkUuid, String kbUuid, String kbItemUuid,
                                   String content, double score, int rank) {
    }

    private static Content toContent(Bm25SearchHit hit, int rank) {
        Metadata metadata = new Metadata();
        metadata.put("content_type", "original_segment");
        metadata.put("kb_uuid", hit.kbUuid());
        metadata.put("kb_item_uuid", hit.kbItemUuid());
        metadata.put(CHUNK_UUID, hit.chunkUuid());
        metadata.put(CHUNK_SET_UUID, hit.chunkSetUuid());
        metadata.put(INDEX_BUILD_UUID, hit.indexBuildUuid());
        metadata.put(SOURCE_DOCUMENT_IDS, hit.kbItemUuid());
        metadata.put(SOURCE_SEGMENT_IDS, hit.chunkUuid());
        metadata.put(BM25_SCORE, hit.score());
        metadata.put(RAW_SCORE, hit.score());
        metadata.put(BM25_RANK, rank);
        return Content.from(TextSegment.from(hit.content(), metadata));
    }

    private static Set<String> sanitizeScope(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> scope = new LinkedHashSet<>();
        values.stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(scope::add);
        return Set.copyOf(scope);
    }
}
