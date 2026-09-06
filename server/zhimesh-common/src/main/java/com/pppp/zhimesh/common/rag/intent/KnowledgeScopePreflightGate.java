package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.rag.QueryInformationAnalyzer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.rag.content.Content;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** High-recall gate in front of document-KB intent recognition and route selection. */
@Slf4j
@Component
public class KnowledgeScopePreflightGate {
    private static final Pattern EXPLICIT_KB_REQUEST = Pattern.compile(
            "(?:根据|按照|基于|查询|查找|搜索|查看|参考)\\s*(?:当前|这个|该|所选|关联|这份|本|"
                    + "用户(?:勾选|选择)的|当前会话的)?的?\\s*"
                    + "(?:知识库|资料库|文档|资料)|"
                    + "(?:知识库|资料库|文档|资料)\\s*(?:中|里|内|的)?\\s*"
                    + "(?:怎么说|如何说|有没有|是否提到|记载|说明|有哪些|有什么内容|包含哪些|包括哪些)|"
                    + "(?:你有什么知识库|当前有哪些知识库|有哪些知识库|知识库有哪些|知识库有什么内容|"
                    + "有哪些资料库|资料库有哪些内容|有哪些文档|文档中有哪些内容)|"
                    + "according to (the )?(knowledge base|documentation)|in (the )?(docs|knowledge base)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED_KB_REQUEST = Pattern.compile(
            "(?:不要|不用|无需|不必|别|不需要)\\s*(?:再)?\\s*"
                    + "(?:根据|按照|基于|查询|查找|搜索|查看|参考)\\s*"
                    + "(?:当前|这个|该|所选|关联|这份|本|用户(?:勾选|选择)的|当前会话的)?的?\\s*"
                    + "(?:知识库|资料库|文档|资料)|"
                    + "(?:不要|不用|无需|不必|别|不需要)\\s*(?:使用|参考|查询)\\s*"
                    + "(?:当前|这个|该|所选|关联|这份|本|用户(?:勾选|选择)的|当前会话的)?的?\\s*"
                    + "(?:知识库|资料库|文档|资料)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final KnowledgeScopeProfileProbe profileProbe;
    private final RuleIntentRecognizer ruleRecognizer;
    private final ZhiMeshProperties properties;

    public KnowledgeScopePreflightGate(KnowledgeScopeProfileProbe profileProbe,
                                       RuleIntentRecognizer ruleRecognizer,
                                       ZhiMeshProperties properties) {
        this.profileProbe = profileProbe;
        this.ruleRecognizer = ruleRecognizer;
        this.properties = properties;
    }

    public KnowledgeScopeDecision evaluate(String rawQuery, Embedding queryEmbedding,
                                           List<KbInfoResp> knowledgeBases) {
        return evaluate(rawQuery, rawQuery,
                queryEmbedding == null ? List.of() : List.of(queryEmbedding), knowledgeBases, true);
    }

    public KnowledgeScopeDecision evaluate(String rawQuery, String retrievalQuery,
                                            List<Embedding> queryEmbeddings,
                                            List<KbInfoResp> knowledgeBases) {
        return evaluate(rawQuery, retrievalQuery, queryEmbeddings, knowledgeBases, true);
    }

    /**
     * Dedicated knowledge-base Q&A is already scoped to one authorized KB.
     * Profile negatives are therefore advisory: a changed chunking strategy
     * can change the bounded profile sample without changing answerable
     * content, so the real retrieval pipeline must make the final no-evidence
     * decision.
     */
    public KnowledgeScopeDecision evaluateDedicatedKnowledgeBase(String rawQuery,
                                                                  Embedding queryEmbedding,
                                                                  List<KbInfoResp> knowledgeBases) {
        return failOpenDedicatedScope(evaluate(rawQuery, rawQuery,
                queryEmbedding == null ? List.of() : List.of(queryEmbedding), knowledgeBases, false));
    }

    public KnowledgeScopeDecision evaluateDedicatedKnowledgeBase(String rawQuery,
                                                                  String retrievalQuery,
                                                                  List<Embedding> queryEmbeddings,
                                                                  List<KbInfoResp> knowledgeBases) {
        return failOpenDedicatedScope(evaluate(rawQuery, retrievalQuery, queryEmbeddings, knowledgeBases, false));
    }

    /**
     * A dedicated QA page has already selected the current knowledge base.
     * Keep it in the retrieval scope even when a complete profile produces a
     * strong negative score; profile coverage is intentionally bounded and is
     * not a substitute for the source indexes in this single-KB workflow.
     */
    private KnowledgeScopeDecision failOpenDedicatedScope(KnowledgeScopeDecision decision) {
        if (decision == null || decision.status() != KnowledgeScopeDecision.Status.UNRELATED) {
            return decision;
        }
        return new KnowledgeScopeDecision(
                KnowledgeScopeDecision.Status.UNCERTAIN,
                decision.maxVectorScore(),
                "dedicated knowledge-base QA keeps the selected scope; "
                        + "route-profile negative evidence is advisory only",
                decision.prefetchedVectorContents(),
                decision.durationMs(),
                decision.retrievalKnowledgeBaseUuids(),
                false);
    }

    private KnowledgeScopeDecision evaluate(String rawQuery, String retrievalQuery,
                                             List<Embedding> queryEmbeddings,
                                             List<KbInfoResp> knowledgeBases,
                                             boolean bypassForStrictKnowledgeBase) {
        long startedAt = System.nanoTime();
        ZhiMeshProperties.KnowledgeScopeGate config = properties.getKnowledgeScopeGate();
        if (!config.isEnabled() || knowledgeBases == null || knowledgeBases.isEmpty()) {
            return decision(KnowledgeScopeDecision.Status.NOT_APPLICABLE, -1D,
                    "gate disabled or no attached knowledge base", List.of(), startedAt);
        }
        String query = normalize(rawQuery);
        String effectiveQuery = StringUtils.defaultIfBlank(normalize(retrievalQuery), query);
        if (StringUtils.isBlank(query) || queryEmbeddings == null || queryEmbeddings.isEmpty()
                || queryEmbeddings.stream().anyMatch(java.util.Objects::isNull)) {
            return decision(KnowledgeScopeDecision.Status.UNCERTAIN, -1D,
                    "query or query embedding is unavailable", List.of(), startedAt);
        }
        if (bypassForStrictKnowledgeBase && knowledgeBases.size() == 1
                && knowledgeBases.stream().anyMatch(kb -> Boolean.TRUE.equals(kb.getIsStrict()))) {
            return decision(KnowledgeScopeDecision.Status.RELATED, 1D,
                    "strict knowledge base bypass", List.of(), startedAt);
        }
        if (isExplicitKnowledgeBaseRequest(query)
                || isExplicitKnowledgeBaseRequest(effectiveQuery)) {
            return decision(KnowledgeScopeDecision.Status.RELATED, 1D,
                    "explicit knowledge-base request", List.of(), startedAt);
        }
        RuleIntentRecognizer.SignalAnalysis signals = ruleRecognizer.analyze(query);
        RuleIntentRecognizer.SignalAnalysis effectiveSignals = ruleRecognizer.analyze(effectiveQuery);
        if (signals.signals().contains(IntentSignal.EXACT_IDENTIFIER)
                || effectiveSignals.signals().contains(IntentSignal.EXACT_IDENTIFIER)) {
            return decision(KnowledgeScopeDecision.Status.RELATED, 1D,
                    "exact identifier protection", List.of(), startedAt);
        }
        boolean rewritten = !StringUtils.equals(query, effectiveQuery);
        if (!rewritten && (signals.signals().contains(IntentSignal.AMBIGUOUS_CONTEXT)
                || !QueryInformationAnalyzer.hasInformativeTerms(query))) {
            return decision(KnowledgeScopeDecision.Status.UNCERTAIN, -1D,
                    "context-dependent or low-information follow-up", List.of(), startedAt);
        }
        Set<String> queryTerms = QueryInformationAnalyzer.informativeTerms(query);
        Set<String> effectiveQueryTerms = QueryInformationAnalyzer.informativeTerms(effectiveQuery);
        Set<String> lexicalMatches = knowledgeBases.stream()
                .filter(kb -> hasLexicalScopeMatch(queryTerms, kb)
                        || hasLexicalScopeMatch(effectiveQueryTerms, kb))
                .map(KbInfoResp::getUuid)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (knowledgeBases.size() == 1 && !lexicalMatches.isEmpty()) {
            return decision(KnowledgeScopeDecision.Status.RELATED, 1D,
                    "knowledge-base title or description lexical match", List.of(), startedAt);
        }
        if (knowledgeBases.stream().anyMatch(kb -> kb.getItemCount() == null)) {
            return decision(KnowledgeScopeDecision.Status.UNCERTAIN, -1D,
                    "knowledge-base content statistics are unavailable", List.of(), startedAt);
        }
        // A zero item count is not proof that the scope is unrelated. It can
        // be a stale statistic while ingestion/indexing is still committing.
        // Keep the request on the fail-open path so intent routing can decide
        // and the retrievers can observe any already-serving content.
        if (knowledgeBases.stream().allMatch(kb -> value(kb.getItemCount()) == 0)) {
            return decision(KnowledgeScopeDecision.Status.UNCERTAIN, -1D,
                    "attached knowledge-base content statistics are empty or stale",
                    List.of(), startedAt);
        }
        if (knowledgeBases.size() == 1
                && knowledgeBases.stream().filter(kb -> value(kb.getItemCount()) > 0)
                .anyMatch(kb -> !hasFreshServingProfile(kb))) {
            return decision(KnowledgeScopeDecision.Status.RELATED, 1D,
                    "an attached knowledge-base profile is updating or unavailable; default related",
                    List.of(), startedAt);
        }
        try {
            KnowledgeScopeProfileProbe.Result probe = profileProbe.probe(queryEmbeddings, knowledgeBases);
            if (probe == null || probe.maxScore() < -1D
                    || (!probe.complete() && probe.matches().isEmpty())) {
                return decision(KnowledgeScopeDecision.Status.UNCERTAIN, -1D,
                        probe == null ? "route-profile probe returned no decision" : probe.reason(),
                        List.of(), startedAt);
            }
            if (!probe.matches().isEmpty()) {
                return classifyPerKnowledgeBase(knowledgeBases, queryTerms, effectiveQueryTerms,
                        lexicalMatches, probe, config, bypassForStrictKnowledgeBase, startedAt);
            }
            double unrelatedFloor = clamp(config.getUnrelatedMaxScore());
            double relatedFloor = Math.max(unrelatedFloor, clamp(config.getRelatedMinScore()));
            double maxScore = probe.maxScore();
            KnowledgeScopeDecision.Status status = maxScore >= relatedFloor
                    ? KnowledgeScopeDecision.Status.RELATED
                    : probe.containsStaleProfile()
                    ? KnowledgeScopeDecision.Status.UNCERTAIN
                    : isClearlyUnrelated(maxScore, unrelatedFloor)
                    ? KnowledgeScopeDecision.Status.UNRELATED
                    : KnowledgeScopeDecision.Status.UNCERTAIN;
            String reason = switch (status) {
                case UNRELATED -> "all fresh route profiles provide strong evidence of a completely unrelated query";
                case RELATED -> "a route profile passed the related threshold";
                default -> probe.containsStaleProfile()
                        ? "stale route profiles provide positive evidence only"
                        : "route-profile score is inside the fail-open gray band";
            };
            return decision(status, maxScore, reason, List.of(), startedAt);
        } catch (RuntimeException exception) {
            log.warn("Knowledge scope preflight failed open: {}", exception.getMessage());
            return decision(KnowledgeScopeDecision.Status.UNCERTAIN, -1D,
                    "probe failure: " + exception.getClass().getSimpleName(), List.of(), startedAt);
        }
    }

    private KnowledgeScopeDecision classifyPerKnowledgeBase(
            List<KbInfoResp> knowledgeBases,
            Set<String> queryTerms,
            Set<String> effectiveQueryTerms,
            Set<String> lexicalMatches,
            KnowledgeScopeProfileProbe.Result probe,
            ZhiMeshProperties.KnowledgeScopeGate config,
            boolean bypassForStrictKnowledgeBase,
            long startedAt) {
        double unrelatedFloor = clamp(config.getUnrelatedMaxScore());
        double relatedFloor = Math.max(unrelatedFloor, clamp(config.getRelatedMinScore()));
        Set<String> retained = new LinkedHashSet<>();
        int related = 0;
        int uncertain = 0;
        int excluded = 0;
        double maxScore = probe.maxScore();

        for (KbInfoResp knowledgeBase : knowledgeBases) {
            String uuid = StringUtils.trimToEmpty(knowledgeBase.getUuid());
            if (StringUtils.isBlank(uuid)) {
                uncertain++;
                continue;
            }
            boolean lexical = lexicalMatches.contains(uuid)
                    || hasLexicalScopeMatch(queryTerms, knowledgeBase)
                    || hasLexicalScopeMatch(effectiveQueryTerms, knowledgeBase);
            boolean strictProtection = bypassForStrictKnowledgeBase
                    && Boolean.TRUE.equals(knowledgeBase.getIsStrict());
            KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch match = probe.matches().get(uuid);

            if (lexical || strictProtection) {
                retained.add(uuid);
                related++;
                continue;
            }
            if (match == null || !match.complete() || match.containsStaleProfile()) {
                retained.add(uuid);
                uncertain++;
                continue;
            }
            if (isClearlyUnrelated(match, unrelatedFloor,
                    config.getMinProfilesForNegativeDecision())) {
                excluded++;
                continue;
            }
            retained.add(uuid);
            if (match.maxScore() >= relatedFloor) related++;
            else uncertain++;
        }

        KnowledgeScopeDecision.Status status;
        if (retained.isEmpty() && uncertain == 0 && related == 0 && excluded > 0) {
            status = KnowledgeScopeDecision.Status.UNRELATED;
        } else if (related > 0) {
            status = KnowledgeScopeDecision.Status.RELATED;
        } else {
            status = KnowledgeScopeDecision.Status.UNCERTAIN;
        }
        String reason = String.format(Locale.ROOT,
                "per-knowledge-base scope: retained=%d, excluded=%d, uncertain=%d",
                retained.size(), excluded, uncertain);
        return decision(status, maxScore, reason, List.of(), startedAt, retained, true);
    }

    private static boolean isClearlyUnrelated(
            KnowledgeScopeProfileProbe.Result.KnowledgeBaseMatch match,
            double configuredFloor,
            int configuredMinimumProfiles) {
        double conservativeFloor = Math.min(configuredFloor, 0.35D);
        return match.complete()
                && !match.containsStaleProfile()
                && match.comparedProfiles() >= Math.max(1, configuredMinimumProfiles)
                && match.maxScore() >= 0D
                && match.maxScore() < conservativeFloor
                && match.topKMeanScore() >= 0D
                && match.topKMeanScore() < conservativeFloor;
    }

    private static boolean hasLexicalScopeMatch(Set<String> terms, KbInfoResp knowledgeBase) {
        if (terms == null || terms.isEmpty()) return false;
        String profile = normalize(StringUtils.defaultString(knowledgeBase.getTitle()) + " "
                + StringUtils.defaultString(knowledgeBase.getRemark()));
        return terms.stream().anyMatch(term -> term.length() >= 2 && profile.contains(term));
    }

    private KnowledgeScopeDecision decision(KnowledgeScopeDecision.Status status, double score,
                                             String reason, List<Content> prefetched,
                                             long startedAt) {
        return decision(status, score, reason, prefetched, startedAt, Set.of(), false);
    }

    private KnowledgeScopeDecision decision(KnowledgeScopeDecision.Status status, double score,
                                             String reason, List<Content> prefetched,
                                             long startedAt, Set<String> retainedKnowledgeBaseUuids,
                                             boolean retrievalScopeResolved) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        KnowledgeScopeDecision result = new KnowledgeScopeDecision(
                status, score, reason, prefetched, durationMs,
                retainedKnowledgeBaseUuids, retrievalScopeResolved);
        log.info("Knowledge scope preflight: status={}, score={}, skip={}, durationMs={}, reason={}",
                result.status(), result.maxVectorScore(),
                result.skipKnowledgeBaseRouting(), result.durationMs(), result.reason());
        return result;
    }

    private static int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static boolean hasFreshServingProfile(KbInfoResp knowledgeBase) {
        Long generation = knowledgeBase.getRouteProfileGeneration();
        Long activeGeneration = knowledgeBase.getRouteProfileActiveGeneration();
        return "READY".equals(knowledgeBase.getRouteProfileStatus())
                && generation != null && generation > 0
                && generation.equals(activeGeneration);
    }

    private static double clamp(double score) {
        return Math.max(0D, Math.min(1D, score));
    }

    /**
     * A profile score just below the configured floor is only weak negative
     * evidence. It must not suppress retrieval for a user-selected KB. The
     * early exit is reserved for a very low score substantially below the
     * configured floor; every gray-band result continues to intent routing.
     */
    private static boolean isClearlyUnrelated(double maxScore, double configuredFloor) {
        double conservativeFloor = Math.min(configuredFloor, 0.35D);
        return maxScore >= 0D && maxScore < conservativeFloor;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = ZERO_WIDTH.matcher(normalized).replaceAll("");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    /**
     * Explicit KB phrases are positive hints only. A negated phrase suppresses
     * the shortcut and lets the existing intent routing decide what to do; it
     * never creates a negative scope decision by itself.
     */
    private static boolean isExplicitKnowledgeBaseRequest(String query) {
        return StringUtils.isNotBlank(query)
                && !NEGATED_KB_REQUEST.matcher(query).find()
                && EXPLICIT_KB_REQUEST.matcher(query).find();
    }
}
