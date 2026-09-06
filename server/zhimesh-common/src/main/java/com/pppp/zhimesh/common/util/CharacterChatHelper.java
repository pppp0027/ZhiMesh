package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.rag.ZhiMeshEmbeddingStoreContentRetriever;
import com.pppp.zhimesh.common.rag.BgeReranker;
import com.pppp.zhimesh.common.rag.CompositeRag;
import com.pppp.zhimesh.common.rag.EmbeddingRagContext;
import com.pppp.zhimesh.common.rag.bm25.Bm25ReadinessService;
import com.pppp.zhimesh.common.rag.intent.ContextualQueryRewriter;
import com.pppp.zhimesh.common.rag.intent.IntentRoutingService;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopeDecision;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopePreflightGate;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.MemoryRetrievalMode;
import com.pppp.zhimesh.common.rag.intent.MemoryRetrievalPolicy;
import com.pppp.zhimesh.common.rag.intent.RetrievalPlan;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.service.ModelPlatformService;
import com.pppp.zhimesh.common.service.UserMcpService;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.util.ZhiMeshStringUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import com.pppp.zhimesh.common.vo.RetrievalQueryContext;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.CHARACTER_ID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.KB_UUID;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.MetadataKey.MEMORY_TYPE;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.RAG_RETRIEVE_MIN_SCORE_DEFAULT;

/**
 * Character 聊天辅助类
 * <p>
 * 从 {@link com.pppp.zhimesh.common.service.CharacterMessageService} 提取的核心逻辑，
 * 供 Agent 节点和 Character 聊天服务共用。
 * </p>
 * <p>
 * Character chat helper — core logic extracted from CharacterMessageService,
 * shared between Agent node and Character chat service.
 * </p>
 */
@Slf4j
public class CharacterChatHelper {

    private CharacterChatHelper() {
    }

    /**
     * 多知识库搜索、记忆搜索
     * <p>
     * Concurrent RAG retrieval from knowledge bases and character memory.
     * </p>
     *
     * @param characterId 角色ID / Character ID
     * @param filteredKb  有效的已关联的知识库 / Filtered enabled knowledge bases
     * @param llmService  大模型服务 / LLM service
     * @param embeddingModel 向量化模型 / Embedding model
     * @param queryText   查询文本 / Query text
     * @param memoryId short-term memory used only when the query needs context
     * @return 检索结果列表 / Retrieval result list
     */
    public static List<RetrieverWrapper> retrieve(Long characterId, List<KbInfoResp> filteredKb,
                                                  AbstractLLMService llmService, EmbeddingModel embeddingModel,
                                                  String queryText, String memoryId) {
        MemoryRetrievalPolicy memoryPolicy = SpringUtil.getBean(MemoryRetrievalPolicy.class);
        if (shouldSkipAllExternalSources(queryText, memoryPolicy)) {
            log.debug("Retrieval preflight skipped all external sources, questionChars:{}",
                    StringUtils.length(queryText));
            return List.of();
        }
        ContextualQueryRewriter.Result resolvedQuery = filteredKb.isEmpty()
                ? new ContextualQueryRewriter.Result(queryText, queryText, false, false,
                "no attached knowledge base")
                : SpringUtil.getBean(ContextualQueryRewriter.class).resolve(queryText, memoryId, llmService);
        String retrievalQuery = resolvedQuery.retrievalQuery();
        RetrievalQueryContext queryContext = RetrievalQueryContext.create(retrievalQuery, embeddingModel);
        List<Embedding> scopeEmbeddings = new ArrayList<>();
        scopeEmbeddings.add(queryContext.embedding());
        if (resolvedQuery.rewritten()) {
            scopeEmbeddings.add(embeddingModel.embed(queryText).content());
        }
        TokenCountEstimator tokenEstimator = llmService.resolveTokenCountEstimator();

        List<RetrieverWrapper> retrieverWrappers = new ArrayList<>();
        Set<String> attachedKbUuids = new LinkedHashSet<>(
                filteredKb.stream().map(KbInfoResp::getUuid)
                        .filter(StringUtils::isNotBlank).toList());
        KnowledgeScopeDecision scopeDecision = SpringUtil.getBean(KnowledgeScopePreflightGate.class)
                .evaluate(queryText, retrievalQuery, scopeEmbeddings, filteredKb);
        boolean knowledgeScopeAllowed = !scopeDecision.skipKnowledgeBaseRouting();
        Set<String> kbUuids = resolveKnowledgeBaseRetrievalScope(filteredKb, scopeDecision);
        if (scopeDecision.hasRetrievalScope()) {
            log.info("Knowledge-base scope filtered before retrieval, originalKbCount:{}, retainedKbCount:{}, "
                            + "excludedKbCount:{}, reason:{}",
                    attachedKbUuids.size(), kbUuids.size(), attachedKbUuids.size() - kbUuids.size(),
                    scopeDecision.reason());
        }
        EnumSet<RetrievalRoute> availableRoutes = EnumSet.noneOf(RetrievalRoute.class);
        if (!kbUuids.isEmpty() && knowledgeScopeAllowed) {
            availableRoutes.add(RetrievalRoute.VECTOR);
            availableRoutes.add(RetrievalRoute.GRAPH);
            log.info("Preparing to search related knowledge bases, kbCount:{},questionChars:{}",
                    kbUuids.size(), StringUtils.length(queryText));
            try {
                Bm25ReadinessService readinessService = SpringUtil.getBean(Bm25ReadinessService.class);
                if (readinessService.readyKnowledgeBases(kbUuids).containsAll(kbUuids)) {
                    availableRoutes.add(RetrievalRoute.BM25);
                }
            } catch (RuntimeException exception) {
                log.warn("Unable to check BM25 readiness for character knowledge bases; using vector/graph: {}",
                        exception.getMessage());
            }
        } else if (!kbUuids.isEmpty()) {
            log.info("Knowledge-base intent recognition and retrieval skipped by scope preflight, reason:{}",
                    scopeDecision.reason());
        }

        IntentRoutingService routingService = SpringUtil.getBean(IntentRoutingService.class);
        RetrievalPlan routingPlan = kbUuids.isEmpty() || !knowledgeScopeAllowed
                ? null : routingService.route(retrievalQuery, queryContext.embedding(), null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB), availableRoutes);
        MemoryRetrievalMode memoryMode = memoryPolicy.decide(
                queryText, routingPlan == null ? null : routingPlan.decision());
        Set<RetrievalRoute> effectiveRoutes = routingPlan == null
                ? Set.of() : routingService.effectiveRoutes(routingPlan);
        if (memoryMode == MemoryRetrievalMode.NONE && effectiveRoutes.isEmpty()) {
            return List.of();
        }
        ZhiMeshProperties properties = SpringUtil.getBean(ZhiMeshProperties.class);
        BgeReranker reranker = resolveReranker(filteredKb);
        List<KbInfoResp> retrievalKnowledgeBases = filteredKb.stream()
                .filter(kb -> kbUuids.contains(kb.getUuid())).toList();
        int rerankTopN = retrievalKnowledgeBases.stream().map(KbInfoResp::getRerankTopN)
                .filter(value -> value != null && value > 0).max(Integer::compareTo).orElse(3);
        int memoryCandidateResults = memoryMode.isAuto()
                ? Math.max(1, properties.getMemory().getAutoProbeCandidateResults())
                : Math.max(1, properties.getMemory().getExplicitCandidateResults());
        int memoryMaxResults = memoryMode.isAuto()
                ? Math.max(1, properties.getMemory().getAutoProbeMaxResults())
                : Math.max(1, properties.getMemory().getExplicitMaxResults());
        memoryMaxResults = Math.min(memoryMaxResults, memoryCandidateResults);
        double memoryMinScore = memoryMode.isAuto()
                ? clampScore(properties.getMemory().getAutoProbeMinScore())
                : clampScore(properties.getMemory().getRetrieveMinScore());

        if (memoryMode.includesSemantic()) {
            Filter semanticFilter = new And(
                    new IsEqualTo(CHARACTER_ID, characterId),
                    new IsNotEqualTo(MEMORY_TYPE, MemoryType.EPISODIC.getDesc()));
            RetrieverCreateParam memoryRetrieveParam = RetrieverCreateParam.builder()
                    .retrievalRoutes(Set.of(RetrievalRoute.VECTOR))
                    .queryEmbedding(queryContext.embedding())
                    .tokenEstimator(tokenEstimator)
                    .filter(semanticFilter)
                    .maxResults(memoryCandidateResults)
                    .minScore(memoryMinScore)
                    .breakIfSearchMissed(false)
                    .reranker(reranker)
                    .rerankTopN(memoryMaxResults)
                    .relevanceGateEnabled(true)
                    .rerankSingleCandidate(true)
                    .build();
            retrieverWrappers.addAll(new CompositeRag(
                    ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY).createRetriever(memoryRetrieveParam));
        }

        if (memoryMode.includesEpisodic()
                && EmbeddingRagContext.get(ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC) != null) {
            RetrieverCreateParam episodicRetrieveParam = RetrieverCreateParam.builder()
                    .retrievalRoutes(Set.of(RetrievalRoute.VECTOR))
                    .queryEmbedding(queryContext.embedding())
                    .tokenEstimator(tokenEstimator)
                    .filter(new IsEqualTo(CHARACTER_ID, characterId))
                    .maxResults(memoryCandidateResults)
                    .minScore(memoryMinScore)
                    .breakIfSearchMissed(false)
                    .reranker(reranker)
                    .rerankTopN(memoryMaxResults)
                    .relevanceGateEnabled(true)
                    .rerankSingleCandidate(true)
                    .build();
            retrieverWrappers.addAll(new CompositeRag(
                    ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC)
                    .createRetriever(episodicRetrieveParam));
        }

        if (!kbUuids.isEmpty()) {
            if (!effectiveRoutes.isEmpty()) {
                ChatModel graphQueryModel = null;
                if (effectiveRoutes.contains(RetrievalRoute.GRAPH)) {
                    try {
                        graphQueryModel = llmService.buildChatLLM(
                                ChatModelBuilderProperties.builder()
                                        .temperature(ZhiMeshConstant.LLM_TEMPERATURE_DEFAULT)
                                        .build());
                    } catch (RuntimeException exception) {
                        EnumSet<RetrievalRoute> fallback = EnumSet.copyOf(effectiveRoutes);
                        fallback.remove(RetrievalRoute.GRAPH);
                        if (fallback.isEmpty()) {
                            throw exception;
                        }
                        effectiveRoutes = Set.copyOf(fallback);
                        log.warn("Unable to initialize graph retrieval for character knowledge bases; "
                                        + "continuing with remaining routes, kbUuids:{}, routes:{}, reason:{}",
                                kbUuids, effectiveRoutes, exception.getMessage());
                    }
                }
                RetrieverCreateParam kbRetrieveParam = RetrieverCreateParam.builder()
                        .retrievalRoutes(effectiveRoutes)
                        .knowledgeBaseUuids(Set.copyOf(kbUuids))
                        .chatModel(graphQueryModel)
                        .queryEmbedding(effectiveRoutes.contains(RetrievalRoute.VECTOR)
                                ? queryContext.embedding() : null)
                        .tokenEstimator(tokenEstimator)
                        .filter(new IsIn(KB_UUID, kbUuids))
                        .maxResults(3)
                        .minScore(RAG_RETRIEVE_MIN_SCORE_DEFAULT)
                        .breakIfSearchMissed(false)
                        .reranker(reranker)
                        .rerankTopN(rerankTopN)
                        .rerankSingleCandidate(true)
                        .maxInputTokens(llmService.getAiModel().getMaxInputTokens())
                        .relevanceGateEnabled(true)
                        .build();
                List<RetrieverWrapper> kbRetrievers = new CompositeRag(
                        ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE).createRetriever(kbRetrieveParam);
                retrieverWrappers.addAll(kbRetrievers);
            } else {
                log.info("Knowledge-base retrieval skipped by enforced NO_RAG intent");
            }
        }

        // Execute wrappers on the chat orchestration thread. Composite retrievers
        // fan out their leaf vector/graph/BM25 routes on ragRetrievalExecutor.
        // A chat task therefore never submits children to its own executor and
        // then occupies a worker while waiting for those queued children.
        for (RetrieverWrapper retriever : retrieverWrappers) {
            try {
                List<Content> contents = retriever.getRetriever().retrieve(Query.from(retrievalQuery));
                retriever.setResponse(contents);
            } catch (Exception e) {
                log.error("Retrieve content error", e);
            }
        }
        return retrieverWrappers;
    }

    /**
     * Only inputs that are provably unable to need retrieval may stop before the
     * fail-open KB and memory gates. Context-dependent, low-information turns
     * such as “这个呢” or “继续” must proceed so conversation-aware routing can
     * return UNCERTAIN instead of being mistaken for a trivial greeting.
     */
    static boolean shouldSkipAllExternalSources(String queryText, MemoryRetrievalPolicy memoryPolicy) {
        return StringUtils.isBlank(queryText) || memoryPolicy.isTrivialTurn(queryText);
    }

    static Set<String> resolveKnowledgeBaseRetrievalScope(List<KbInfoResp> filteredKb,
                                                            KnowledgeScopeDecision decision) {
        Set<String> attached = new LinkedHashSet<>();
        if (filteredKb != null) {
            filteredKb.stream().map(KbInfoResp::getUuid)
                    .filter(StringUtils::isNotBlank).forEach(attached::add);
        }
        if (decision == null || !decision.hasRetrievalScope()) {
            return attached;
        }
        attached.retainAll(decision.retrievalKnowledgeBaseUuids());
        return attached;
    }

    /** Resolves one request-scoped cross-encoder, preferring the knowledge-base setting. */
    private static BgeReranker resolveReranker(List<KbInfoResp> knowledgeBases) {
        try {
            AiModelService aiModelService = SpringUtil.getBean(AiModelService.class);
            Long configuredId = knowledgeBases.stream()
                    .map(KbInfoResp::getRerankModelId)
                    .filter(id -> id != null && id > 0)
                    .findFirst()
                    .orElse(null);
            AiModel model = configuredId == null
                    ? aiModelService.listEnabledByType(ZhiMeshConstant.ModelType.RERANK).stream()
                    .findFirst().orElse(null)
                    : aiModelService.getById(configuredId);
            if (model == null || !Boolean.TRUE.equals(model.getIsEnable())
                    || !ZhiMeshConstant.ModelType.RERANK.equals(model.getType())) {
                return null;
            }
            ModelPlatform platform = SpringUtil.getBean(ModelPlatformService.class)
                    .getByName(model.getPlatform());
            return platform == null ? null : new BgeReranker(model, platform);
        } catch (RuntimeException exception) {
            log.warn("Unable to configure request reranker; relevance gate will use conservative fallback: {}",
                    exception.getMessage());
            return null;
        }
    }

    private static double clampScore(double score) {
        return Math.max(0D, Math.min(1D, score));
    }

    /**
     * 将检索结果分离为记忆文本和知识文本
     * <p>
     * Separate retrieved content into memory text and knowledge text. Memory text
     * combines two channels: stable semantic knowledge and a timeline-flavored
     * "past events" section for episodic memories. Episodic items are formatted
     * with their content from the vector segment as-is (timestamps/importance/event_type
     * already live in the segment metadata and could be surfaced here later if needed).
     * <p>
     * 将检索结果分离为记忆文本和知识文本。记忆文本融合两个通道：稳定的语义知识 +
     * "过往事件"段（episodic）。
     * </p>
     */
    public static Pair<String, String> buildMemoryAndKnowledge(List<RetrieverWrapper> wrappers) {
        StringBuilder semanticMemory = new StringBuilder();
        StringBuilder episodicMemory = new StringBuilder();
        StringBuilder knowledge = new StringBuilder();
        wrappers.forEach(item -> {
            String retrieveType = item.getContentFrom();
            List<Content> response = item.getResponse();
            if (response == null) {
                return;
            }
            if (ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY.equals(retrieveType)) {
                for (Content content : response) {
                    semanticMemory.append("- ").append(content.textSegment().text()).append("\n");
                }
            } else if (ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC.equals(retrieveType)) {
                for (Content content : response) {
                    episodicMemory.append("- ").append(content.textSegment().text()).append("\n");
                }
            } else if (ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE.equals(retrieveType)) {
                for (Content content : response) {
                    knowledge.append(content.textSegment().text()).append("\n");
                }
            }
        });

        StringBuilder memory = new StringBuilder();
        if (semanticMemory.isEmpty() && episodicMemory.isEmpty()) {
            memory.append("None\n");
        } else {
            if (!semanticMemory.isEmpty()) {
                memory.append("[Stable knowledge about the user]\n").append(semanticMemory).append("\n");
            }
            if (!episodicMemory.isEmpty()) {
                memory.append("[Past events you recall]\n").append(episodicMemory).append("\n");
            }
        }
        if (knowledge.isEmpty()) {
            knowledge.append("None\n");
        } else {
            knowledge.append("\n");
        }
        return Pair.of(memory.toString(), knowledge.toString());
    }

    /**
     * 根据 Character 配置构建聊天请求参数
     * <p>
     * Build ChatModelRequest from Character configuration.
     * </p>
     *
     * @param character       角色 / Character
     * @param userPrompt      处理后的用户提示词 / Processed user prompt
     * @param user            用户 / User
     * @param llmService      LLM 服务 / LLM service
     * @param enableMcp       是否启用 MCP 工具 / Enable MCP tools
     * @param enableWebSearch 是否启用联网搜索 / Enable web search
     * @return 聊天请求参数 / Chat request params
     */
    public static ChatModelRequest buildChatRequestParams(Character character, String userPrompt,
                                                                  User user, AbstractLLMService llmService,
                                                                  boolean enableMcp, boolean enableWebSearch,
                                                                  List<String> imageUrls) {
        return buildChatRequestParams(character, character.getUuid(), userPrompt, user, llmService,
                enableMcp, enableWebSearch, imageUrls);
    }

    public static ChatModelRequest buildChatRequestParams(Character character, String memoryId,
                                                                  String userPrompt, User user,
                                                                  AbstractLLMService llmService,
                                                                  boolean enableMcp, boolean enableWebSearch,
                                                                  List<String> imageUrls) {
        ChatModelRequest.ChatModelRequestBuilder builder = ChatModelRequest.builder();

        //System message
        if (StringUtils.isNotBlank(character.getAiSystemMessage())) {
            builder.systemMessage(character.getAiSystemMessage());
        }

        //Memory (for context understanding)
        if (Boolean.TRUE.equals(character.getUnderstandContextEnable())) {
            builder.memoryId(memoryId);
        }

        //User message
        builder.userMessage(userPrompt);

        //Image URLs (multimodal)
        if (imageUrls != null && !imageUrls.isEmpty()) {
            builder.imageUrls(imageUrls);
        }

        //MCP tools
        if (enableMcp && StringUtils.isNotBlank(character.getMcpIds())) {
            UserMcpService userMcpService = SpringUtil.getBean(UserMcpService.class);
            List<Long> mcpIds = ZhiMeshStringUtil.stringToList(character.getMcpIds(), ",", Long::parseLong);
            List<McpClient> mcpClients = userMcpService.createMcpClients(character.getUserId(), mcpIds);
            builder.mcpClients(mcpClients);
        }

        //Thinking
        AiModel aiModel = llmService.getAiModel();
        Boolean returnThinking = checkIfReturnThinking(aiModel, character);
        builder.returnThinking(returnThinking);

        //Web search — drop the flag if the resolved model does not support it,
        //so a stale config or capability change cannot cause a confusing LLM error.
        boolean effectiveWebSearch = enableWebSearch;
        if (effectiveWebSearch && !Boolean.TRUE.equals(aiModel.getIsSupportWebSearch())) {
            log.warn("Web search requested but model {}/{} does not support it; ignoring flag",
                    aiModel.getPlatform(), aiModel.getName());
            effectiveWebSearch = false;
        }
        builder.enableWebSearch(effectiveWebSearch);

        return builder.build();
    }

    /**
     * 判断是否需要返回推理过程
     * <p>
     * Check if thinking/reasoning should be returned.
     * </p>
     *
     * @param aiModel   模型 / AI model
     * @param character 角色 / Character
     * @return 是否返回推理过程 / Whether to return thinking
     */
    public static Boolean checkIfReturnThinking(AiModel aiModel, Character character) {
        if (!aiModel.getIsReasoner()) {
            return null;
        }
        return Boolean.FALSE.equals(aiModel.getIsThinkingClosable()) || Boolean.TRUE.equals(character.getIsEnableThinking());
    }
}
