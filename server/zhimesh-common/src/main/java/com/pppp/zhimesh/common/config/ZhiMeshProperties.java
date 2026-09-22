package com.pppp.zhimesh.common.config;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties("zhimesh")
@Data
public class ZhiMeshProperties {

    private String host;

    private String frontendUrl;

    private String backendUrl;

    private Proxy proxy;

    private String embeddingModel;

    private String vectorDatabase;

    private String graphDatabase;

    private Datasource datasource;

    private Encrypt encrypt;

    private Indexing indexing = new Indexing();

    private Retrieval retrieval = new Retrieval();

    /** Apache AGE graph-store connection pool; replaces per-statement driver connections. */
    private GraphStore graphStore = new GraphStore();

    /** Bounded application executors tuned for a 4-core/4-GB deployment. */
    private AsyncExecution async = new AsyncExecution();

    /** Daily OpenRouter free-model catalog synchronization. */
    private OpenRouterSync openrouterSync = new OpenRouterSync();

    /** Safe, incremental intent-routing rollout. */
    private IntentRouting intentRouting = new IntentRouting();

    /** High-recall gate that can skip the complete document-KB routing pipeline. */
    private KnowledgeScopeGate knowledgeScopeGate = new KnowledgeScopeGate();

    /**
     * 长期记忆相关配置 | Long-term memory settings.
     */
    private Memory memory = new Memory();

    private Conversation conversation = new Conversation();

    /** LLM agent tool-calling loop settings. */
    private Agent agent = new Agent();

    /**
     * Authentication settings. Demo registration is intentionally opt-in so a
     * production deployment cannot accidentally bypass email verification.
     */
    private Auth auth = new Auth();

    @Data
    public static class Auth {
        private boolean demoRegisterEnabled = false;
    }

    @Data
    public static class Proxy {
        private boolean enable;
        private String host;
        private int httpPort;
    }

    @Data
    public static class Datasource {
        private Neo4j neo4j;
    }

    @Data
    public static class Neo4j {
        private String host;
        private int port;
        private String username;
        private String password;
        private String database;
    }

    @Data
    public static class Encrypt {
        private String aesKey;
    }

    @Data
    public static class Indexing {
        /** Disabled in plain unit construction; application.yml enables shared canonical chunks. */
        private boolean canonicalChunkEnabled = false;
        /** Concurrent document-level graph extraction requests. */
        private int graphConcurrency = 2;
        /** Maximum graph segments extracted in parallel inside one document. */
        private int graphSegmentConcurrency = 4;
        /** Process-wide cap for actual graph-extraction LLM HTTP requests. */
        private int graphRequestConcurrency = 4;
        /** A graph JSON response can be slower than an interactive chat response. */
        private long graphRequestTimeoutSeconds = 120L;
        /** Total attempts, including the first request, for timeout/rate-limit failures. */
        private int graphRequestMaxAttempts = 3;
        /** Initial exponential retry delay after a transient graph request failure. */
        private long graphRetryInitialBackoffMs = 2000L;
        /** Upper bound for one graph request retry delay. */
        private long graphRetryMaxBackoffMs = 10000L;
        /** Total quality-self-healing attempts per segment (first extraction + repairs). */
        private int graphExtractionQualityMaxAttempts = 3;
        /**
         * A DOING graphical status older than this is considered abandoned
         * (crashed process, lost executor) and is recovered to FAIL so the
         * document can be re-indexed. Generous by design: a live but slow
         * ingestion must not be failed while it is still writing.
         */
        private long graphDoingTimeoutMinutes = 60L;
        /**
         * Same abandoned-DOING recovery semantics as graphDoingTimeoutMinutes,
         * applied to the embedding status. Tighter than the graph timeout
         * because embedding ingestion has no LLM-extraction stage that could
         * legitimately run for an hour.
         * 与 graphDoingTimeoutMinutes 同款恢复语义，作用于向量化状态；无 LLM 抽取阶段故更短。
         */
        private long embeddingDoingTimeoutMinutes = 30L;
        /**
         * Same abandoned-DOING recovery semantics as graphDoingTimeoutMinutes,
         * applied to the fulltext (BM25) status.
         * 与 graphDoingTimeoutMinutes 同款恢复语义，作用于全文（BM25）状态。
         */
        private long fulltextDoingTimeoutMinutes = 30L;
        /** Concurrent document-level embedding jobs. */
        private int embeddingConcurrency = 2;
    }

    @Data
    public static class AsyncExecution {
        private Executor chat = new Executor(4, 8, 20);
        /** Dedicated batch-indexing workers; graph writes remain independently serialized. */
        private Executor indexing = new Executor(2, 2, 50);
        private Executor background = new Executor(1, 2, 20);
        private Executor workflow = new Executor(1, 2, 10);
        private Executor images = new Executor(1, 2, 10);
        private int taskSchedulerPoolSize = 2;
        private int lockRenewSchedulerPoolSize = 2;
    }

    @Data
    public static class Executor {
        private int corePoolSize = 1;
        private int maxPoolSize = 1;
        private int queueCapacity = 0;

        public Executor() {
        }

        public Executor(int corePoolSize, int maxPoolSize, int queueCapacity) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
        }
    }

    @Data
    public static class OpenRouterSync {
        private boolean enabled = true;
        private String platformName = "OpenRouter";
        private String cron = "0 0 4 * * *";
        private String zone = "Asia/Shanghai";
        /** Lightweight probes after the daily catalog synchronization. */
        private boolean healthCheckEnabled = true;
        /** One low-volume checkpoint per day; the catalog sync remains at 04:00. */
        private String healthCheckCron = "0 0 16 * * *";
        /** Trigger full discovery when fewer than this many free text models remain usable. */
        private int healthCheckMinActiveModels = 5;
        /** Safety cap for one lightweight health-check batch. */
        private int healthCheckMaxProbesPerRun = 5;
        /** QUEUED/RUNNING audit rows older than this cannot still own the 30-minute lock. */
        private int activeRunTimeoutMinutes = 35;
        /** Delay a recovery catalog request after OpenRouter responds with 429. */
        private int rateLimitBackoffMinutes = 60;
        /** Do not immediately retry a rate-limited provider by default. */
        private boolean rateLimitRetryEnabled = false;
        private int minContextTokens = 8192;
        private int maxCatalogP50LatencyMs = 4000;
        private double minCatalogThroughputTps = 12D;
        private double minCatalogUptime1d = 98D;
        private int maxProbeTtftMs = 6000;
        private int maxProbeTotalMs = 15000;
        private int connectTimeoutMs = 5000;
        private int requestTimeoutMs = 20000;
        private int probeIntervalMs = 10000;
        private int maxProbesPerRun = 20;
        private int minActiveModels = 10;
        private int catchUpAfterHours = 26;
        private List<String> protectedModels = new ArrayList<>();
    }

    @Data
    public static class GraphStore {
        /**
         * Emergency rollback switch: false restores the legacy per-statement
         * DriverManager connection path (no pooling, one fresh connection per
         * Cypher statement).
         */
        private boolean poolEnabled = true;
        /**
         * Pool ceiling. Graph writes are globally serialized (one connection)
         * and retrieval graph-route concurrency is bounded by the retrieval
         * executor, so a small pool covers the peak with headroom.
         */
        private int poolMaxSize = 4;
        private int poolMinIdle = 1;
        private long poolConnectionTimeoutMs = 8000;
        private long poolMaxLifetimeMs = 1800000;
    }

    @Data
    public static class Retrieval {
        /** Maximum number of vector/graph retrieval tasks running concurrently. */
        private int concurrency = 4;
        /**
         * Bounded backlog for retrieval tasks. A small bounded queue makes
         * overload fail fast instead of letting timed-out work accumulate.
         */
        private int queueCapacity = 8;
        /** Maximum wait for pgvector retrieval. */
        private long vectorTimeoutMs = 5000;
        /** Maximum wait for graph retrieval, including entity extraction by LLM. */
        private long graphTimeoutMs = 30000;
        /** PostgreSQL-backed BM25 branch. Disabled in plain unit construction; application.yml enables it. */
        private Bm25 bm25 = new Bm25();
        /** Retries for transient route failures; empty results are never retried. */
        private int retryCount = 1;
        /** Maximum time spent waiting for a rerank service. */
        private long rerankTimeoutMs = 3000;
        /** Maximum candidates sent to the cross-encoder reranker. */
        private int rerankCandidateLimit = 15;
        /** Consecutive rerank failures before opening the circuit. */
        private int rerankFailureThreshold = 3;
        /** Rerank circuit-open duration. */
        private long rerankCircuitOpenMs = 60000;
        /**
         * Keep reranked candidates whose score reaches this fraction of the
         * top score. A relative threshold is stable across score calibrations.
         */
        private double rerankRelativeScoreThreshold = 0.30D;
        /** Minimum candidates retained after applying the rerank score cutoff. */
        private int rerankMinCandidates = 0;
        /** Absolute cross-encoder relevance floor; open chat must be able to reject every candidate. */
        private double rerankAbsoluteScoreThreshold = 0.30D;
        /** Fail-open is unsafe for open chat because an unavailable reranker can inject unrelated context. */
        private boolean relevanceGateFailOpen = false;
        /** Raw vector evidence above this score may survive when the reranker is unavailable. */
        private double fallbackHighConfidenceVectorScore = 0.82D;
        /** CPU-only candidate gate before the remote reranker call. */
        private boolean preRerankGateEnabled = true;
        /** Weak vector-only candidates below this score need informative lexical overlap. */
        private double preRerankVectorScoreFloor = 0.70D;
        /**
         * Graph candidates at or above this route rank survive the no-reranker
         * fallback gate: their route already applied lexical and diversity
         * selection, while rephrased evidence has no literal query overlap.
         */
        private int fallbackGraphRankFloor = 2;
        /** Cap on graph candidates exempted by the fallback rank floor. */
        private int fallbackGraphMaxExemptions = 2;
        /** Label evidence (document chunk vs. inferred graph relation) when injecting context. */
        private boolean evidenceLabelingEnabled = true;
        /** Recency share used only after episodic candidates pass semantic relevance checks. */
        private double episodicRecencyWeight = 0.20D;
        /** Age at which the episodic recency contribution decays to one half. */
        private double episodicRecencyHalfLifeDays = 30D;
        /** Small importance tie-breaker used only for explicit temporal episodic queries. */
        private double episodicImportanceWeight = 0.05D;
        /** Hard upper bound for retrieved context tokens. */
        private int contextMaxTokens = 4000;
        /** Tokens reserved for the generated answer. */
        private int contextReservedOutputTokens = 1800;
        /** Conservative reserve for the two-message chat window. */
        private int contextReservedHistoryTokens = 1000;
        /** Fraction of model input kept as a safety margin. */
        private double contextSafetyRatio = 0.08D;
        /** Soft maximum selected chunks from one document before refill. */
        private int contextPerDocumentLimit = 2;
        /** Maximum fraction of context tokens used by graph relation summaries. */
        private double graphDescriptionTokenRatio = 0.20D;
        /** Maximum graph relation descriptions returned before fusion. */
        private int graphDescriptionLimit = 3;
        /**
         * Number of top vector candidates protected from RRF eviction when
         * hybrid retrieval has no successful reranker.
         */
        private int hybridProtectedVectorCount = 1;
        /** Minimum vector score lead over rank 2 required for protection. */
        private double hybridVectorProtectionMinMargin = 0.05D;

        @Data
        public static class Bm25 {
            /** Master switch for indexing, readiness and online retrieval. */
            private boolean enabled = false;
            /** Build the lexical index whenever a knowledge document is indexed. */
            private boolean autoIndex = false;
            /** Independent route timeout; BM25 must not inherit the vector timeout. */
            private long timeoutMs = 5000;
            /** Per-route candidate cap before RRF/reranking. */
            private int topK = 5;
            /** Okapi BM25 term-frequency saturation parameter. */
            private double k1 = 1.2D;
            /** Okapi BM25 document-length normalization parameter. */
            private double b = 0.75D;
            /** Defensive query expansion bound for CJK n-grams and identifiers. */
            private int maxQueryTerms = 128;
            /** Terms above this scoped document-frequency ratio do not contribute to BM25. */
            private double maxDocumentFrequencyRatio = 0.85D;
            /** Avoid document-frequency suppression in very small corpora. */
            private int documentFrequencyFilterMinDocuments = 20;
            /** Stored with each index build so analyzer changes force a rebuild. */
            private String analyzerVersion = "cjk-bigram-trigram-ident-v1";
        }
    }

    @Data
    public static class IntentRouting {
        /** Master switch for production intent routing. */
        private boolean enabled = true;
        /** Keep the trained three-head classifier available for a later release, but off by default. */
        private boolean classifierEnabled = false;
        /** Versioned multi-label routing classifier artifact. */
        private String classifierResource = "classpath:rag/retrieval-router-v1.json";
        /** Optional deployment-pinned SHA-256. Blank still performs structural/model checks. */
        private String classifierSha256 = "";
        /** Route-plan prototype catalog used while classifierEnabled is false. */
        private String routePrototypeResource = "classpath:rag/retrieval-route-prototypes.zh-CN.json";
        /** Legacy intent prototype settings retained for compatibility with offline tooling. */
        private String prototypeResource = "classpath:rag/intent-prototypes.zh-CN.json";
        private double minTopScore = 0.78D;
        private double minScoreMargin = 0.05D;
    }

    @Data
    public static class KnowledgeScopeGate {
        /** Master switch; failures always degrade to UNCERTAIN and continue retrieval. */
        private boolean enabled = true;
        /** Bounded immutable profiles generated for one knowledge base. */
        /** Maximum adaptive profile count; the server hard cap is applied after this setting. */
        private int profileLimit = ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_SCOPE_PROFILE_VECTORS;
        /** Maximum deterministic candidate texts embedded by one background build. */
        private int candidatePoolLimit = 256;
        /** Defensive character limit before a candidate is embedded. */
        private int profileTextMaxChars = 1000;
        /** Redis MGET hard wait; timeout always fails open. */
        private long redisTimeoutMs = 50L;
        /** Versioned Redis bundle lifetime. Active bundles are refreshed before expiry. */
        private long redisTtlHours = 24L;
        /** Coalesces concurrent item indexing completions into one KB-level rebuild. */
        private long rebuildDebounceMs = 5000L;
        /** Lease for the cross-instance profile build lock. */
        private long buildLockSeconds = 300L;
        /** Stored in each immutable release so algorithm changes force rebuilding. */
        private String generatorVersion = "kb-route-profile-v1";
        /** Redis failures before online profile reads temporarily short-circuit. */
        private int cacheFailureThreshold = 3;
        /** Circuit-open duration after repeated Redis failures. */
        private long cacheCircuitOpenMs = 30000L;
        /** Below this maximum profile score, a fresh complete scope is clearly unrelated. */
        private double unrelatedMaxScore = 0.60D;
        /** Above this score the request is related; stale profiles may only use this positive outcome. */
        private double relatedMinScore = 0.82D;
        /** Minimum number of valid profile vectors required before a KB may be negatively excluded. */
        private int minProfilesForNegativeDecision = 3;
    }

    /**
     * 长期记忆配置项。
     * <p>
     * Long-term memory configuration.
     */
    @Data
    public static class Memory {
        /**
         * 向量检索旧记忆时的最低相似度阈值。默认 {@link ZhiMeshConstant#LONG_TERM_MEMORY_MIN_SCORE_DEFAULT}。
         * <p>
         * Minimum similarity score for retrieving existing memories during extraction.
         */
        private double minScore = ZhiMeshConstant.LONG_TERM_MEMORY_MIN_SCORE_DEFAULT;
        /** Minimum similarity used when recalling semantic or episodic memory for a new answer. */
        private double retrieveMinScore = 0.65D;
        /** Higher threshold for bounded AUTO probes on implicit memory requests. */
        private double autoProbeMinScore = 0.72D;
        /** Candidates fetched from each memory store before AUTO reranking. */
        private int autoProbeCandidateResults = 5;
        /** Final accepted memories per store for AUTO after relevance checks. */
        private int autoProbeMaxResults = 2;
        /** Candidates fetched from the selected memory store(s) for explicit recall. */
        private int explicitCandidateResults = 5;
        /** Final accepted memories per store for explicit recall. */
        private int explicitMaxResults = 3;
        /** Near-duplicate episodic events at or above this score are not written again. */
        private double episodicDedupMinScore = 0.92D;
        /** Maximum time an async semantic-memory task waits for the character lock. */
        private long updateLockWaitMs = 120000L;
        /** Renewable lease used by the cross-instance semantic-memory update lock. */
        private long updateLockLeaseMs = 60000L;
    }

    @Data
    public static class Conversation {
        /** Legacy rollout flag; stateful chat now always resolves a Conversation. */
        private boolean enabled = true;
        /** Legacy rollout flag; characterUuid-only chat now always resolves the default Conversation. */
        private boolean autoCreateDefault = true;
        /** Legacy rollout flag; new chat messages now always persist Conversation ownership. */
        private boolean messageDualWrite = true;
        /** Maximum tokens retained from previous messages in one conversation. */
        private int shortMemoryMaxHistoryTokens = 8192;
        /** Tokens reserved for the model response instead of short-term memory. */
        private int shortMemoryReservedOutputTokens = 2048;
        /** Fraction of the model input kept for estimation error and non-message overhead. */
        private double shortMemorySafetyRatio = 0.08D;
        /** Sliding TTL refreshed on successful Redis writes; zero keeps explicit-delete semantics. */
        private Duration shortMemoryTtl = Duration.ZERO;
        /** Defensive maximum for one serialized memory value. */
        private int shortMemoryMaxValueBytes = 1_048_576;
        /** Fail a concurrent turn after this wait instead of silently losing messages. */
        private Duration shortMemoryTurnLockWait = Duration.ofSeconds(2);
        /** Lease used by the renewable distributed turn lock. */
        private Duration shortMemoryTurnLockLease = Duration.ofSeconds(30);
    }

    /**
     * LLM 工具调用循环配置。
     * <p>
     * LLM tool-calling loop settings. Guardrails (timeout/truncation) only apply
     * to builtin tools; MCP tools keep their legacy behavior.
     */
    @Data
    public static class Agent {
        /** Maximum recursive tool-call rounds for one chat request. */
        private int maxToolIterations = 8;
        /** Maximum execution time for one builtin (non-MCP) tool call. */
        private long toolTimeoutMs = 60000;
        /** Maximum characters of a builtin tool result injected back into the model. */
        private int toolResultMaxChars = 4000;
    }
}
