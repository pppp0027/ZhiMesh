package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrievalRouteKey;
import com.pppp.zhimesh.common.rag.intent.RoutedRetriever;
import com.pppp.zhimesh.common.rag.bm25.Bm25ContentRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Merges results from multiple retrievers before the answer model receives them.
 * It removes duplicate and near-duplicate context while preserving the original
 * retrievers for retrieval-reference persistence.
 */
@Slf4j
public class DeduplicatingContentRetriever implements ContentRetriever {

    private static final int MIN_REDUNDANCY_LENGTH = 24;
    private static final double REDUNDANCY_THRESHOLD = 0.72D;

    private final List<RoutedRetriever> routedRetrievers;
    private final BgeReranker reranker;
    private final int rerankTopN;
    private final boolean strict;
    private final AsyncTaskExecutor executor;
    private final ZhiMeshProperties.Retrieval retrievalProperties;
    private final int maxInputTokens;
    private final String systemMessage;
    private final TokenCountEstimator tokenEstimator;
    private final boolean relevanceGateEnabled;
    private final boolean rerankSingleCandidate;
    private volatile List<RetrievalRouteResult> routeResults = List.of();
    private volatile List<Content> selectedContents = List.of();
    private volatile List<Content> candidateContents = List.of();
    private volatile RerankResult lastRerankResult;

    public DeduplicatingContentRetriever(List<ContentRetriever> sourceRetrievers, BgeReranker reranker, int rerankTopN) {
        this(sourceRetrievers, reranker, rerankTopN, false, null,
                new ZhiMeshProperties.Retrieval(), 0, null);
    }

    public DeduplicatingContentRetriever(List<ContentRetriever> sourceRetrievers,
                                         BgeReranker reranker,
                                         int rerankTopN,
                                         boolean strict,
                                         AsyncTaskExecutor executor,
                                         ZhiMeshProperties.Retrieval retrievalProperties) {
        this(sourceRetrievers, reranker, rerankTopN, strict, executor,
                retrievalProperties, 0, null);
    }

    public DeduplicatingContentRetriever(List<ContentRetriever> sourceRetrievers,
                                         BgeReranker reranker,
                                         int rerankTopN,
                                         boolean strict,
                                         AsyncTaskExecutor executor,
                                         ZhiMeshProperties.Retrieval retrievalProperties,
                                         int maxInputTokens,
                                         String systemMessage) {
        this(legacyRoutes(sourceRetrievers), reranker, rerankTopN, strict, executor,
                retrievalProperties, maxInputTokens, systemMessage, true);
    }

    /** Constructor used by the generalized route registry. */
    public DeduplicatingContentRetriever(List<RoutedRetriever> routedRetrievers,
                                         BgeReranker reranker,
                                         int rerankTopN,
                                         boolean strict,
                                         AsyncTaskExecutor executor,
                                         ZhiMeshProperties.Retrieval retrievalProperties,
                                         int maxInputTokens,
                                         String systemMessage,
                                         boolean explicitRouteDescriptors) {
        this(routedRetrievers, reranker, rerankTopN, strict, executor, retrievalProperties,
                maxInputTokens, systemMessage, explicitRouteDescriptors, null);
    }

    /** Constructor with an explicit request-scoped token estimator. */
    public DeduplicatingContentRetriever(List<RoutedRetriever> routedRetrievers,
                                         BgeReranker reranker,
                                         int rerankTopN,
                                         boolean strict,
                                         AsyncTaskExecutor executor,
                                         ZhiMeshProperties.Retrieval retrievalProperties,
                                         int maxInputTokens,
                                         String systemMessage,
                                         boolean explicitRouteDescriptors,
                                         TokenCountEstimator tokenEstimator) {
        this(routedRetrievers, reranker, rerankTopN, strict, executor, retrievalProperties,
                maxInputTokens, systemMessage, explicitRouteDescriptors, tokenEstimator, false);
    }

    /** Constructor with request-level relevance rejection enabled for open chat. */
    public DeduplicatingContentRetriever(List<RoutedRetriever> routedRetrievers,
                                         BgeReranker reranker,
                                         int rerankTopN,
                                         boolean strict,
                                         AsyncTaskExecutor executor,
                                         ZhiMeshProperties.Retrieval retrievalProperties,
                                         int maxInputTokens,
                                         String systemMessage,
                                         boolean explicitRouteDescriptors,
                                         TokenCountEstimator tokenEstimator,
                                         boolean relevanceGateEnabled) {
        this(routedRetrievers, reranker, rerankTopN, strict, executor, retrievalProperties,
                maxInputTokens, systemMessage, explicitRouteDescriptors, tokenEstimator,
                relevanceGateEnabled, false);
    }

    /** Constructor that can force cross-encoder scoring for a single candidate. */
    public DeduplicatingContentRetriever(List<RoutedRetriever> routedRetrievers,
                                         BgeReranker reranker,
                                         int rerankTopN,
                                         boolean strict,
                                         AsyncTaskExecutor executor,
                                         ZhiMeshProperties.Retrieval retrievalProperties,
                                         int maxInputTokens,
                                         String systemMessage,
                                         boolean explicitRouteDescriptors,
                                         TokenCountEstimator tokenEstimator,
                                         boolean relevanceGateEnabled,
                                         boolean rerankSingleCandidate) {
        this.routedRetrievers = List.copyOf(routedRetrievers);
        this.reranker = reranker;
        this.rerankTopN = rerankTopN;
        this.strict = strict;
        this.executor = executor;
        this.retrievalProperties = retrievalProperties == null
                ? new ZhiMeshProperties.Retrieval()
                : retrievalProperties;
        this.maxInputTokens = maxInputTokens;
        this.systemMessage = systemMessage;
        this.tokenEstimator = tokenEstimator;
        this.relevanceGateEnabled = relevanceGateEnabled;
        this.rerankSingleCandidate = rerankSingleCandidate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        selectedContents = List.of();
        candidateContents = List.of();
        lastRerankResult = null;
        List<RetrievalRouteResult> results = executor == null
                ? retrieveSequentially(query)
                : retrieveInParallel(query);
        routeResults = List.copyOf(results);

        List<RetrievedCandidate> candidates = mergeRouteCandidates(results);
        if (candidates.isEmpty()) {
            synchronizeSelectedProvenance(List.of());
            boolean hasRouteFailure = results.stream().anyMatch(RetrievalRouteResult::failed);
            if (hasRouteFailure) {
                RetrievalRouteResult failedRoute = results.stream()
                        .filter(RetrievalRouteResult::failed)
                        .findFirst()
                        .orElse(null);
                throw new IllegalStateException("No usable retrieval context because one or more routes failed: "
                        + summarize(failedRoute));
            }
            if (strict) {
                throw new BaseException(ErrorEnum.B_BREAK_SEARCH.getCode(), ErrorEnum.B_BREAK_SEARCH.getInfo());
            }
            return List.of();
        }
        candidates.forEach(RetrievedCandidate::calculateRrfScore);
        List<RetrievedCandidate> fallbackOrder = candidates.stream()
                .sorted(java.util.Comparator.comparingDouble(
                        candidate -> -safeScore(candidate.rrfScore())))
                .toList();

        List<RetrievedCandidate> prefiltered = relevanceGateEnabled
                ? RetrievalRelevanceGate.preFilter(query.text(), fallbackOrder, retrievalProperties)
                : fallbackOrder;
        List<RetrievedCandidate> ranked = rerank(query.text(), prefiltered);
        if (shouldProtectVectorEvidence(results)) {
            ranked = prioritizeVectorEvidence(
                    ranked,
                    retrievalProperties.getHybridProtectedVectorCount(),
                    retrievalProperties.getHybridVectorProtectionMinMargin(),
                    rerankTopN > 0 ? rerankTopN : ranked.size(),
                    retrievalProperties.getContextPerDocumentLimit());
        }
        List<RetrievedCandidate> diversified = diversifyCandidates(ranked);
        candidateContents = diversified.stream().map(RetrievedCandidate::toContent).toList();
        List<RetrievedCandidate> relevant = relevanceGateEnabled
                ? RetrievalRelevanceGate.filter(query.text(), diversified,
                lastRerankResult != null && lastRerankResult.successful(), reranker != null,
                retrievalProperties)
                : diversified;
        List<RetrievedCandidate> selectionOrder =
                lastRerankResult != null && lastRerankResult.successful()
                        ? applyRerankScoreCutoff(
                                relevant,
                                retrievalProperties.getRerankMinCandidates(),
                                retrievalProperties.getRerankRelativeScoreThreshold())
                        : relevant;
        if (isEpisodicMemorySource()) {
            selectionOrder = EpisodicRecencyRanker.rank(
                    query.text(), selectionOrder,
                    retrievalProperties.getEpisodicRecencyWeight(),
                    retrievalProperties.getEpisodicRecencyHalfLifeDays(),
                    retrievalProperties.getEpisodicImportanceWeight(),
                    java.time.LocalDateTime.now());
        }
        int configuredLimit = rerankTopN > 0 ? rerankTopN : diversified.size();
        TokenCountEstimator estimator = tokenEstimator != null
                ? tokenEstimator
                : TokenEstimatorFactory.create(TokenEstimatorThreadLocal.getTokenEstimator());
        int contextBudget = calculateContextBudget(query.text(), estimator);
        List<RetrievedCandidate> selected = packCandidates(
                selectionOrder, configuredLimit, contextBudget, estimator);
        if (selected.isEmpty() && strict) {
            synchronizeSelectedProvenance(List.of());
            throw new BaseException(ErrorEnum.B_BREAK_SEARCH.getCode(), ErrorEnum.B_BREAK_SEARCH.getInfo());
        }
        log.info("Merged retrieval candidates: exactUnique={}, prefiltered={}, selected={}, contextBudget={}, routes={}",
                candidates.size(), prefiltered.size(), selected.size(), contextBudget, summarize(results));
        selectedContents = selected.stream().map(RetrievedCandidate::toContent).toList();
        synchronizeSelectedProvenance(selectedContents);
        return selectedContents;
    }

    /** Keeps source-level provenance aligned with the evidence that actually entered the prompt. */
    private void synchronizeSelectedProvenance(List<Content> selected) {
        Set<String> embeddingIds = new HashSet<>();
        Set<String> chunkUuids = new HashSet<>();
        Set<String> graphElementIds = new HashSet<>();
        for (Content content : selected) {
            Map<String, Object> metadata = content.textSegment().metadata().toMap();
            addCsvValues(embeddingIds, metadata.get("embedding_id"));
            addCsvValues(chunkUuids, metadata.get(Bm25ContentRetriever.CHUNK_UUID));
            addCsvValues(graphElementIds, metadata.get(RetrievedCandidate.GRAPH_ELEMENT_IDS));
            addCsvValues(graphElementIds, metadata.get(RetrievedCandidate.GRAPH_ELEMENT_ID));
        }
        for (ContentRetriever sourceRetriever : getSourceRetrievers()) {
            if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever vectorRetriever) {
                vectorRetriever.retainRetrievedEmbeddings(embeddingIds);
            } else if (sourceRetriever instanceof Bm25ContentRetriever bm25Retriever) {
                bm25Retriever.retainRetrievedHits(chunkUuids);
            } else if (sourceRetriever instanceof GraphStoreContentRetriever graphRetriever) {
                graphRetriever.retainRetrievedReference(graphElementIds);
            }
        }
    }

    private static void addCsvValues(Set<String> target, Object value) {
        if (value == null) return;
        for (String item : String.valueOf(value).split(",")) {
            if (StringUtils.isNotBlank(item)) target.add(item.trim());
        }
    }

    private List<RetrievedCandidate> mergeRouteCandidates(List<RetrievalRouteResult> results) {
        List<RetrievalRouteResult> routes = results.stream()
                .filter(RetrievalRouteResult::hasUsableContent).toList();
        int maxRouteSize = routes.stream().mapToInt(route -> route.contents().size()).max().orElse(0);
        Map<String, RetrievedCandidate> exactUnique = new LinkedHashMap<>();
        for (int index = 0; index < maxRouteSize; index++) {
            for (RetrievalRouteResult route : routes) {
                if (index < route.contents().size()) {
                    RetrievedCandidate candidate = RetrievedCandidate.from(
                            route.contents().get(index), route.route(), index + 1);
                    String key = normalize(candidate.text());
                    if (StringUtils.isNotBlank(key)) {
                        exactUnique.merge(key, candidate, (existing, duplicate) -> {
                            existing.merge(duplicate);
                            return existing;
                        });
                    }
                }
            }
        }
        return new ArrayList<>(exactUnique.values());
    }

    private List<RetrievedCandidate> rerank(String query, List<RetrievedCandidate> fallbackOrder) {
        if (reranker == null || fallbackOrder.isEmpty()
                || (fallbackOrder.size() == 1 && !rerankSingleCandidate)) {
            return fallbackOrder;
        }
        int candidateLimit = Math.max(1, retrievalProperties.getRerankCandidateLimit());
        List<RetrievedCandidate> pool = fallbackOrder.stream().limit(candidateLimit).toList();
        RerankResult result = reranker.rerank(query,
                pool.stream().map(RetrievedCandidate::toContent).toList(), pool.size(),
                retrievalProperties.getRerankTimeoutMs(),
                retrievalProperties.getRerankFailureThreshold(),
                retrievalProperties.getRerankCircuitOpenMs(), rerankSingleCandidate);
        lastRerankResult = result;
        if (!result.successful()) {
            log.info("Rerank fallback to RRF, circuitOpen={}, reason={}, durationMs={}",
                    result.circuitOpen(), result.failureReason(), result.durationMs());
            return fallbackOrder;
        }
        List<RetrievedCandidate> ranked = new ArrayList<>();
        Set<RetrievedCandidate> included = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (RerankResult.RerankScore score : result.scores()) {
            RetrievedCandidate candidate = pool.get(score.index());
            candidate.setRerankScore(score.score());
            ranked.add(candidate);
            included.add(candidate);
        }
        fallbackOrder.stream().filter(candidate -> !included.contains(candidate)).forEach(ranked::add);
        log.info("Rerank completed, candidates={}, durationMs={}", pool.size(), result.durationMs());
        return ranked;
    }

    private boolean shouldProtectVectorEvidence(List<RetrievalRouteResult> results) {
        boolean rerankSuccessful = lastRerankResult != null && lastRerankResult.successful();
        if (rerankSuccessful || retrievalProperties.getHybridProtectedVectorCount() <= 0) {
            return false;
        }
        Set<String> requestedRoutes = results.stream()
                .map(RetrievalRouteResult::route)
                .collect(java.util.stream.Collectors.toSet());
        if (!requestedRoutes.equals(Set.of("vector", "graph"))) {
            return false;
        }
        boolean hasVector = results.stream()
                .anyMatch(result -> "vector".equals(result.route()) && result.hasUsableContent());
        boolean hasGraph = results.stream()
                .anyMatch(result -> "graph".equals(result.route()) && result.hasUsableContent());
        return hasVector && hasGraph;
    }

    private boolean isEpisodicMemorySource() {
        return routedRetrievers.stream().anyMatch(retriever ->
                retriever.key().sourceType() == KnowledgeSourceType.EPISODIC_MEMORY);
    }

    /**
     * Keeps the strongest vector evidence ahead of RRF-only candidates while
     * retaining the original fused order for every unprotected candidate.
     */
    static List<RetrievedCandidate> prioritizeVectorEvidence(
            List<RetrievedCandidate> ranked, int protectedCount, double minScoreMargin,
            int selectionLimit, int perDocumentLimit) {
        if (ranked.isEmpty() || protectedCount <= 0) return ranked;
        List<RetrievedCandidate> vectorCandidates = ranked.stream()
                .filter(candidate -> candidate.vectorRank() != null)
                .sorted(java.util.Comparator.comparingInt(RetrievedCandidate::vectorRank))
                .toList();
        if (vectorCandidates.size() < 2
                || vectorCandidates.get(0).vectorScore() == null
                || vectorCandidates.get(1).vectorScore() == null
                || vectorCandidates.get(0).vectorScore()
                - vectorCandidates.get(1).vectorScore() < Math.max(0D, minScoreMargin)) {
            return ranked;
        }
        RetrievedCandidate vectorTopOne = vectorCandidates.get(0);
        int currentIndex = ranked.indexOf(vectorTopOne);
        boolean outsideSelectionLimit = currentIndex >= Math.max(1, selectionLimit);
        String documentId = vectorTopOne.documentId();
        long precedingSameDocument = currentIndex <= 0 || StringUtils.isBlank(documentId)
                ? 0L
                : ranked.subList(0, currentIndex).stream()
                .filter(candidate -> documentId.equals(candidate.documentId()))
                .count();
        boolean blockedByDocumentCap =
                precedingSameDocument >= Math.max(1, perDocumentLimit);
        if (!outsideSelectionLimit && !blockedByDocumentCap) return ranked;
        List<RetrievedCandidate> protectedCandidates =
                vectorCandidates.stream().limit(protectedCount).toList();
        Set<RetrievedCandidate> protectedSet = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        protectedSet.addAll(protectedCandidates);
        List<RetrievedCandidate> result = new ArrayList<>(ranked.size());
        result.addAll(protectedCandidates);
        ranked.stream().filter(candidate -> !protectedSet.contains(candidate)).forEach(result::add);
        return result;
    }

    /**
     * Drops the weak rerank tail without assuming an absolute score scale.
     * The highest-ranked candidates are always retained up to minCandidates.
     */
    static List<RetrievedCandidate> applyRerankScoreCutoff(
            List<RetrievedCandidate> ranked, int minCandidates, double relativeThreshold) {
        if (ranked.isEmpty() || relativeThreshold <= 0D) return ranked;
        int guaranteed = Math.min(ranked.size(), Math.max(0, minCandidates));
        Double topScore = ranked.get(0).rerankScore();
        if (topScore == null) return ranked;
        double ratio = Math.min(1D, relativeThreshold);
        double cutoff = topScore * ratio;
        List<RetrievedCandidate> retained = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            RetrievedCandidate candidate = ranked.get(index);
            if (index < guaranteed
                    || candidate.rerankScore() != null && candidate.rerankScore() >= cutoff) {
                retained.add(candidate);
            }
        }
        return retained;
    }

    private List<RetrievedCandidate> diversifyCandidates(List<RetrievedCandidate> ranked) {
        List<RetrievedCandidate> preferred = new ArrayList<>();
        List<RetrievedCandidate> deferred = new ArrayList<>();
        for (RetrievedCandidate candidate : ranked) {
            if (isRedundant(candidate.text(), preferred.stream().map(RetrievedCandidate::text).toList())) {
                deferred.add(candidate);
            } else {
                preferred.add(candidate);
            }
        }
        preferred.addAll(deferred);
        return preferred;
    }

    private int calculateContextBudget(String question, TokenCountEstimator estimator) {
        int hardLimit = Math.max(1, retrievalProperties.getContextMaxTokens());
        if (maxInputTokens <= 0) return hardLimit;
        int safety = (int) Math.ceil(maxInputTokens * Math.max(0D,
                Math.min(0.5D, retrievalProperties.getContextSafetyRatio())));
        int systemTokens = StringUtils.isBlank(systemMessage)
                ? 0 : estimator.estimateTokenCountInText(systemMessage);
        int questionTokens = estimator.estimateTokenCountInText(StringUtils.defaultString(question));
        int available = maxInputTokens - systemTokens - questionTokens
                - Math.max(0, retrievalProperties.getContextReservedOutputTokens())
                - Math.max(0, retrievalProperties.getContextReservedHistoryTokens()) - safety;
        return Math.max(0, Math.min(hardLimit, available));
    }

    private List<RetrievedCandidate> packCandidates(List<RetrievedCandidate> ordered, int topN,
                                                    int tokenBudget, TokenCountEstimator estimator) {
        if (ordered.isEmpty() || topN <= 0 || tokenBudget <= 0) return List.of();
        int graphBudget = (int) Math.floor(tokenBudget * Math.max(0D,
                Math.min(1D, retrievalProperties.getGraphDescriptionTokenRatio())));
        int perDocumentLimit = Math.max(1, retrievalProperties.getContextPerDocumentLimit());
        List<RetrievedCandidate> selected = new ArrayList<>();
        List<RetrievedCandidate> deferred = new ArrayList<>();
        Map<String, Integer> documentCounts = new java.util.HashMap<>();
        int usedTokens = 0;
        int graphTokens = 0;
        for (RetrievedCandidate candidate : ordered) {
            if (selected.size() >= topN) break;
            int tokens = estimator.estimateTokenCountInText(candidate.text());
            boolean relation = RetrievedCandidate.GRAPH_RELATION.equals(candidate.contentType());
            String documentId = candidate.documentId();
            boolean documentFull = StringUtils.isNotBlank(documentId)
                    && documentCounts.getOrDefault(documentId, 0) >= perDocumentLimit;
            if (documentFull || usedTokens + tokens > tokenBudget
                    || relation && graphTokens + tokens > graphBudget) {
                deferred.add(candidate);
                continue;
            }
            selected.add(candidate);
            usedTokens += tokens;
            if (relation) graphTokens += tokens;
            if (StringUtils.isNotBlank(documentId)) documentCounts.merge(documentId, 1, Integer::sum);
        }
        // The per-document cap is soft. Refill with relevant evidence when room remains.
        for (RetrievedCandidate candidate : deferred) {
            if (selected.size() >= topN) break;
            int tokens = estimator.estimateTokenCountInText(candidate.text());
            boolean relation = RetrievedCandidate.GRAPH_RELATION.equals(candidate.contentType());
            if (usedTokens + tokens <= tokenBudget
                    && (!relation || graphTokens + tokens <= graphBudget)) {
                selected.add(candidate);
                usedTokens += tokens;
                if (relation) graphTokens += tokens;
            }
        }
        if (selected.isEmpty() && !ordered.isEmpty()) {
            String truncated = truncateAtSentenceBoundary(ordered.get(0).text(), tokenBudget, estimator);
            if (StringUtils.isNotBlank(truncated)) selected.add(ordered.get(0).withText(truncated));
        }
        log.info("Packed retrieval context, selected={}, tokens={}, graphTokens={}, budget={}",
                selected.size(), usedTokens, graphTokens, tokenBudget);
        return selected;
    }

    private String truncateAtSentenceBoundary(String text, int budget, TokenCountEstimator estimator) {
        StringBuilder accepted = new StringBuilder();
        for (String sentence : text.split("(?<=[。！？!?；;\\n])")) {
            String proposed = accepted + sentence;
            if (estimator.estimateTokenCountInText(proposed) > budget) break;
            accepted.append(sentence);
        }
        if (!accepted.isEmpty()) return accepted.toString();
        int low = 0, high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (estimator.estimateTokenCountInText(text.substring(0, middle)) <= budget) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, low);
    }

    private static double safeScore(Double score) {
        return score == null ? 0D : score;
    }

    private static boolean isRedundant(String candidate, List<String> accepted) {
        String normalizedCandidate = normalize(candidate);
        for (String existingText : accepted) {
            String existing = normalize(existingText);
            if (Math.min(normalizedCandidate.length(), existing.length()) >= MIN_REDUNDANCY_LENGTH
                    && (normalizedCandidate.contains(existing) || existing.contains(normalizedCandidate))) return true;
            if (characterNgramJaccard(normalizedCandidate, existing) >= REDUNDANCY_THRESHOLD) return true;
        }
        return false;
    }

    private List<RetrievalRouteResult> retrieveSequentially(Query query) {
        return routedRetrievers.stream().map(retriever -> executeRoute(retriever, query)).toList();
    }

    private List<RetrievalRouteResult> retrieveInParallel(Query query) {
        long startedAt = System.nanoTime();
        List<RouteTask> tasks = new ArrayList<>(routedRetrievers.size());
        for (RoutedRetriever retriever : routedRetrievers) {
            Future<RetrievalRouteResult> future = null;
            long routeTimeoutMs = timeoutMs(retriever);
            try {
                future = executor.submit(() -> {
                    // A task may sit in the bounded queue while another
                    // request is timing out. Do not start a stale retrieval
                    // after its request-scoped deadline has already elapsed.
                    if (elapsedMs(startedAt) >= routeTimeoutMs) {
                        return RetrievalRouteResult.timeout(retriever.key().routeName(), routeTimeoutMs);
                    }
                    return executeRoute(retriever, query);
                });
            } catch (RejectedExecutionException exception) {
                // Saturation is a route-local failure. Do not run retrieval on
                // the caller thread and do not discard the other routes.
                log.warn("Retrieval route rejected because the bounded executor is saturated, route:{}",
                        retriever.key().routeName());
            }
            tasks.add(new RouteTask(retriever, retriever.key().routeName(),
                    routeTimeoutMs, future));
        }
        List<RetrievalRouteResult> results = new ArrayList<>();
        for (RouteTask task : tasks) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (task.future() == null) {
                results.add(RetrievalRouteResult.error(task.route(), elapsedMs,
                        new RejectedExecutionException("Retrieval executor is saturated")));
                continue;
            }
            long remainingMs = Math.max(1L, task.timeoutMs() - elapsedMs);
            try {
                results.add(task.future().get(remainingMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException exception) {
                task.future().cancel(true);
                results.add(RetrievalRouteResult.timeout(task.route(), task.timeoutMs()));
                log.warn("Retrieval route timed out, route:{}, timeoutMs:{}", task.route(), task.timeoutMs());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                task.future().cancel(true);
                results.add(RetrievalRouteResult.error(task.route(), elapsedMs, exception));
            } catch (Exception exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                results.add(RetrievalRouteResult.error(task.route(), elapsedMs, cause));
            }
        }
        return results;
    }

    private RetrievalRouteResult executeRoute(RoutedRetriever routedRetriever, Query query) {
        String route = routedRetriever.key().routeName();
        ContentRetriever retriever = routedRetriever.delegate();
        long startedAt = System.nanoTime();
        int retryCount = Math.max(0, retrievalProperties.getRetryCount());
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            try {
                List<Content> contents = retriever.retrieve(query);
                return RetrievalRouteResult.completed(route,
                        addRouteMetadata(contents, routedRetriever.key()), elapsedMs(startedAt));
            } catch (BaseException exception) {
                if (ErrorEnum.B_BREAK_SEARCH.getCode().equals(exception.getCode())) {
                    return RetrievalRouteResult.completed(route, List.of(), elapsedMs(startedAt));
                }
                if (attempt >= retryCount) {
                    log.error("Retrieval route failed, route:{}, attempts:{}", route, attempt + 1, exception);
                    return RetrievalRouteResult.error(route, elapsedMs(startedAt), exception);
                }
                log.warn("Retrying retrieval route, route:{}, nextAttempt:{}", route, attempt + 2);
            } catch (Exception exception) {
                if (attempt >= retryCount) {
                    log.error("Retrieval route failed, route:{}, attempts:{}", route, attempt + 1, exception);
                    return RetrievalRouteResult.error(route, elapsedMs(startedAt), exception);
                }
                log.warn("Retrying retrieval route, route:{}, nextAttempt:{}", route, attempt + 2);
            }
        }
        return RetrievalRouteResult.error(route, elapsedMs(startedAt),
                new IllegalStateException("Retrieval route exhausted retries"));
    }

    private long timeoutMs(RoutedRetriever retriever) {
        return switch (retriever.key().route()) {
            case GRAPH -> Math.max(1L, retrievalProperties.getGraphTimeoutMs());
            case BM25 -> Math.max(1L, retrievalProperties.getBm25().getTimeoutMs());
            case VECTOR -> Math.max(1L, retrievalProperties.getVectorTimeoutMs());
        };
    }

    private static String routeName(ContentRetriever retriever) {
        if (retriever instanceof ZhiMeshEmbeddingStoreContentRetriever) return "vector";
        if (retriever instanceof GraphStoreContentRetriever) return "graph";
        return retriever.getClass().getSimpleName();
    }

    private static List<RoutedRetriever> legacyRoutes(List<ContentRetriever> retrievers) {
        if (retrievers == null) return List.of();
        return retrievers.stream().map(retriever -> {
            String routeName = routeName(retriever);
            RetrievalRoute route = "graph".equals(routeName) ? RetrievalRoute.GRAPH : RetrievalRoute.VECTOR;
            return new RoutedRetriever(
                    new RetrievalRouteKey(KnowledgeSourceType.DOCUMENT_KB, route, null), retriever);
        }).toList();
    }

    private static List<Content> addRouteMetadata(List<Content> contents, RetrievalRouteKey key) {
        if (contents == null || contents.isEmpty()) return List.of();
        List<Content> enriched = new ArrayList<>(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            Content content = contents.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>(content.textSegment().metadata().toMap());
            metadata.putIfAbsent(RetrievedCandidate.SOURCE_TYPE, key.sourceType().name().toLowerCase(Locale.ROOT));
            metadata.putIfAbsent(RetrievedCandidate.ROUTE_TYPE, key.routeName());
            if (key.sourceInstanceId() != null) {
                metadata.putIfAbsent(RetrievedCandidate.SOURCE_INSTANCE_ID, key.sourceInstanceId());
            }
            metadata.putIfAbsent(RetrievedCandidate.ROUTE_RANK, index + 1);
            enriched.add(Content.from(TextSegment.from(content.textSegment().text(), new Metadata(metadata))));
        }
        return List.copyOf(enriched);
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String summarize(RetrievalRouteResult result) {
        if (result == null) return "unknown";
        return result.route() + ":" + result.status();
    }

    private static String summarize(List<RetrievalRouteResult> results) {
        return results.stream().map(DeduplicatingContentRetriever::summarize).toList().toString();
    }

    private record RouteTask(RoutedRetriever retriever, String route, long timeoutMs,
                             Future<RetrievalRouteResult> future) {
    }

    public List<ContentRetriever> getSourceRetrievers() {
        return routedRetrievers.stream().map(RoutedRetriever::delegate).toList();
    }

    public List<RoutedRetriever> getRoutedRetrievers() {
        return routedRetrievers;
    }

    public List<RetrievalRouteResult> getRouteResults() {
        return routeResults;
    }

    public List<Content> getSelectedContents() {
        return selectedContents;
    }

    public List<Content> getCandidateContents() {
        return candidateContents;
    }

    public RerankResult getLastRerankResult() {
        return lastRerankResult;
    }

    /** Evaluation-only trace; never written to production logs. */
    public Map<String, Object> getGraphTrace() {
        return getSourceRetrievers().stream()
                .filter(GraphStoreContentRetriever.class::isInstance)
                .map(GraphStoreContentRetriever.class::cast)
                .findFirst()
                .map(GraphStoreContentRetriever::getTrace)
                .orElseGet(() -> Map.of("status", "not_configured"));
    }

    public static List<ContentRetriever> unwrapSourceRetrievers(ContentRetriever retriever) {
        if (retriever instanceof DeduplicatingContentRetriever deduplicatingRetriever) {
            return deduplicatingRetriever.getSourceRetrievers();
        }
        return List.of(retriever);
    }

    static List<Content> deduplicate(Collection<Content> candidates) {
        List<Content> result = new ArrayList<>();
        Set<String> normalizedAccepted = new HashSet<>();
        for (Content candidate : candidates) {
            String normalized = normalize(candidate.textSegment().text());
            if (StringUtils.isBlank(normalized) || !normalizedAccepted.add(normalized)) {
                continue;
            }
            result.add(candidate);
        }
        return result;
    }

    /**
     * Greedily keeps high-ranked but non-redundant evidence. Deferred similar
     * candidates are used to fill any remaining slots, so corroborating evidence
     * is never discarded when there is no more diverse alternative.
     */
    static List<Content> selectDiverse(List<Content> ranked, int limit) {
        if (ranked.isEmpty() || limit <= 0) {
            return List.of();
        }
        int target = Math.min(limit, ranked.size());
        List<Content> selected = new ArrayList<>(target);
        List<Content> deferred = new ArrayList<>();
        for (Content candidate : ranked) {
            if (selected.size() >= target) {
                break;
            }
            if (isRedundant(candidate, selected)) {
                deferred.add(candidate);
            } else {
                selected.add(candidate);
            }
        }
        for (Content candidate : deferred) {
            if (selected.size() >= target) {
                break;
            }
            selected.add(candidate);
        }
        return selected;
    }

    private static boolean isRedundant(Content candidate, List<Content> selected) {
        String normalizedCandidate = normalize(candidate.textSegment().text());
        for (Content existingContent : selected) {
            String existing = normalize(existingContent.textSegment().text());
            if (Math.min(normalizedCandidate.length(), existing.length()) >= MIN_REDUNDANCY_LENGTH
                    && (normalizedCandidate.contains(existing) || existing.contains(normalizedCandidate))) {
                return true;
            }
            if (characterNgramJaccard(normalizedCandidate, existing) >= REDUNDANCY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (StringUtils.isBlank(text)) {
            return StringUtils.EMPTY;
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]+", StringUtils.EMPTY);
    }

    private static double characterNgramJaccard(String left, String right) {
        if (left.length() < 3 || right.length() < 3) {
            return 0D;
        }
        Set<String> leftNgrams = characterNgrams(left);
        Set<String> rightNgrams = characterNgrams(right);
        Set<String> union = new HashSet<>(leftNgrams);
        union.addAll(rightNgrams);
        if (union.isEmpty()) {
            return 0D;
        }
        Set<String> intersection = new HashSet<>(leftNgrams);
        intersection.retainAll(rightNgrams);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> characterNgrams(String text) {
        Set<String> ngrams = new HashSet<>();
        for (int index = 0; index <= text.length() - 3; index++) {
            ngrams.add(text.substring(index, index + 3));
        }
        return ngrams;
    }
}
