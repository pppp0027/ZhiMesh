package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.interfaces.IStreamingChatAssistant;
import com.pppp.zhimesh.common.interfaces.ITempStreamingChatAssistant;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.rag.bm25.Bm25Rag;
import com.pppp.zhimesh.common.rag.bm25.Bm25RagContext;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.LegacyRetrievalModeAdapter;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RetrievalRouteKey;
import com.pppp.zhimesh.common.rag.intent.RoutedRetriever;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.util.LLMTokenUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.pppp.zhimesh.common.enums.ErrorEnum.B_BREAK_SEARCH;
import static com.pppp.zhimesh.common.enums.ErrorEnum.B_LLM_SERVICE_DISABLED;

/**
 * 组合向量及图谱数据进行RAG
 */
@Slf4j
public class CompositeRag {

    private final EmbeddingRag embeddingRag;
    private final GraphRag graphRag;
    private final Bm25Rag bm25Rag;
    private final String retrieverName;

    public CompositeRag(String retrieverName) {
        this.retrieverName = retrieverName;
        this.embeddingRag = EmbeddingRagContext.get(retrieverName);
        this.graphRag = GraphRagContext.get(retrieverName);
        this.bm25Rag = Bm25RagContext.get(retrieverName);
    }

    /**
     * 创建Retriever列表
     *
     * @param param 参数
     * @return ContentRetriever列表
     */
    public List<RetrieverWrapper> createRetriever(RetrieverCreateParam param) {
        List<RoutedRetriever> sourceRetrievers = new ArrayList<>();
        Set<RetrievalRoute> requestedRoutes = effectiveRoutes(param);
        // Individual routes must fail softly. Strictness is enforced only after
        // all routes have completed and their results have been merged.
        RetrieverCreateParam routeParam = copyForSoftRoute(param);
        if (requestedRoutes.contains(RetrievalRoute.VECTOR) && null != embeddingRag) {
            sourceRetrievers.add(new RoutedRetriever(
                    new RetrievalRouteKey(sourceType(retrieverName), RetrievalRoute.VECTOR, retrieverName),
                    embeddingRag.createRetriever(routeParam)));
        }
        if (requestedRoutes.contains(RetrievalRoute.GRAPH) && null != graphRag) {
            sourceRetrievers.add(new RoutedRetriever(
                    new RetrievalRouteKey(sourceType(retrieverName), RetrievalRoute.GRAPH, retrieverName),
                    graphRag.createRetriever(routeParam)));
        }
        if (requestedRoutes.contains(RetrievalRoute.BM25) && null != bm25Rag) {
            sourceRetrievers.add(new RoutedRetriever(
                    new RetrievalRouteKey(sourceType(retrieverName), RetrievalRoute.BM25, retrieverName),
                    bm25Rag.createRetriever(routeParam)));
        }
        Set<RetrievalRoute> configuredRoutes = sourceRetrievers.stream()
                .map(routed -> routed.key().route())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        EnumSet<RetrievalRoute> missingRoutes = EnumSet.copyOf(requestedRoutes);
        missingRoutes.removeAll(configuredRoutes);
        if (!missingRoutes.isEmpty()) {
            throw new IllegalStateException("No RAG retriever is configured for routes " + missingRoutes);
        }
        ZhiMeshProperties adiProperties = SpringUtil.getBean(ZhiMeshProperties.class);
        AsyncTaskExecutor retrievalExecutor = SpringUtil.getBean("ragRetrievalExecutor", AsyncTaskExecutor.class);
        ContentRetriever retriever = new DeduplicatingContentRetriever(
                sourceRetrievers, param.getReranker(), param.getRerankTopN(),
                param.isBreakIfSearchMissed(), retrievalExecutor, adiProperties.getRetrieval(),
                param.getMaxInputTokens(), param.getSystemMessage(), true,
                param.getTokenEstimator(), param.isRelevanceGateEnabled(),
                param.isRerankSingleCandidate());
        return List.of(RetrieverWrapper.builder()
                .contentFrom(retrieverName)
                .retriever(retriever)
                .response(new ArrayList<>())
                .build());
    }

    static boolean usesVector(RetrievalMode mode) {
        return LegacyRetrievalModeAdapter.toRoutes(mode).contains(RetrievalRoute.VECTOR);
    }

    static boolean usesGraph(RetrievalMode mode) {
        return LegacyRetrievalModeAdapter.toRoutes(mode).contains(RetrievalRoute.GRAPH);
    }

    static boolean usesBm25(Set<RetrievalRoute> routes) {
        return routes != null && routes.contains(RetrievalRoute.BM25);
    }

    static Set<RetrievalRoute> effectiveRoutes(RetrieverCreateParam param) {
        if (param.getRetrievalRoutes() != null && !param.getRetrievalRoutes().isEmpty()) {
            return Set.copyOf(param.getRetrievalRoutes());
        }
        return Set.copyOf(LegacyRetrievalModeAdapter.toRoutes(param.getRetrievalMode()));
    }

    private static KnowledgeSourceType sourceType(String retrieverName) {
        if (ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY.equals(retrieverName)) {
            return KnowledgeSourceType.CHARACTER_MEMORY;
        }
        if (ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC.equals(retrieverName)) {
            return KnowledgeSourceType.EPISODIC_MEMORY;
        }
        return KnowledgeSourceType.DOCUMENT_KB;
    }

    static RetrieverCreateParam copyForSoftRoute(RetrieverCreateParam source) {
        return RetrieverCreateParam.builder()
                .chatModel(source.getChatModel())
                .queryEmbedding(source.getQueryEmbedding())
                .tokenEstimator(source.getTokenEstimator())
                .retrievalMode(source.getRetrievalMode())
                .retrievalRoutes(source.getRetrievalRoutes())
                .knowledgeBaseUuids(source.getKnowledgeBaseUuids())
                .filter(source.getFilter())
                .maxResults(source.getMaxResults())
                .minScore(source.getMinScore())
                .breakIfSearchMissed(false)
                .graphHopDepth(source.getGraphHopDepth())
                .reranker(source.getReranker())
                .rerankTopN(source.getRerankTopN())
                .maxInputTokens(source.getMaxInputTokens())
                .systemMessage(source.getSystemMessage())
                .relevanceGateEnabled(source.isRelevanceGateEnabled())
                .rerankSingleCandidate(source.isRerankSingleCandidate())
                .prefetchedVectorContents(source.getPrefetchedVectorContents())
                .build();
    }
    /**
     * 使用RAG处理提问
     *
     * @param retrievers   ContentRetriever列表
     * @param sseAskParam 请求参数
     * @param consumer     回调
     */
    public void ragChat(List<ContentRetriever> retrievers, SseAskParam sseAskParam, TriConsumer<String, PromptMeta, AnswerMeta> consumer) {
        SseManager sseManager = SpringUtil.getBean(SseManager.class);
        AsyncTaskExecutor completionExecutor = SpringUtil.getBean("chatExecutor", AsyncTaskExecutor.class);
        User user = sseAskParam.getUser();
        String memoryId = sseAskParam.getHttpRequestParams() == null
                ? null : sseAskParam.getHttpRequestParams().getMemoryId();
        ShortTermMemoryTurnCoordinator.TurnLease turnLease =
                SpringUtil.getBean(ShortTermMemoryTurnCoordinator.class)
                        .acquire(memoryId, sseAskParam.getSseUuid());
        try {
            // 重新生成复用同一 uuid（如知识库 QA 记录 uuid）：先清上次尝试的累计，否则
            // calculateToken 读回时新旧累加，updateQaRecord 按双重数字计费
            // Regeneration reuses the same uuid (e.g. the KB QA record uuid): clear the
            // previous attempt's accumulation first, or calculateToken reads old + new
            // together and updateQaRecord bills the doubled numbers
            LLMTokenUtil.resetTokenUsage(SpringUtil.getBean(StringRedisTemplate.class), sseAskParam.getUuid());
            sseManager.registerEventStreamListener(sseAskParam);
            query(retrievers, sseAskParam, turnLease, (response, promptMeta, answerMeta) -> {
                try {
                    completionExecutor.execute(() -> {
                        try {
                            turnLease.requireValid();
                            consumer.accept(response, promptMeta, answerMeta);
                        } catch (Exception e) {
                            log.error("ragProcess error", e);
                            SseManager.errorAndShutdown(e, sseAskParam.getSseUuid());
                        } finally {
                            turnLease.close();
                        }
                    });
                } catch (RuntimeException submissionError) {
                    turnLease.close();
                    log.warn("RAG completion task rejected, sseUuid:{}",
                            sseAskParam.getSseUuid(), submissionError);
                    SseManager.errorAndShutdown(
                            new IllegalStateException(SpringUtil.getMessage("A_SYSTEM_BUSY"), submissionError),
                            sseAskParam.getSseUuid());
                }
            });
        } catch (Exception baseException) {
            turnLease.close();
            if (isBreakSearch(baseException)) {
                completeWithoutEvidence(sseAskParam, consumer);
            } else {
                log.error("ragProcess error", baseException);
                sseManager.sendErrorAndComplete(user.getId(), sseAskParam.getSseUuid(), baseException.getMessage());
            }
        }

    }

    /**
     * RAG请求，对prompt进行各种增强后发给AI
     * ps: 挂载了知识库的请求才进行RAG增强
     * <p>
     * TODO...计算并截断超长的请求参数内容（历史记录+向量知识+图谱知识+用户问题+工具）
     *
     * @param retrievers 文档召回器（向量、图谱）
     * @param params     前端传过来的请求参数
     * @param consumer   LLM响应内容的消费者
     */
    private void query(List<ContentRetriever> retrievers, SseAskParam params,
                       ShortTermMemoryTurnCoordinator.TurnLease turnLease,
                       TriConsumer<String, PromptMeta, AnswerMeta> consumer) {
        User user = params.getUser();
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(params.getModelPlatform(), params.getModelName());
        if (!llmService.isEnabled()) {
            log.error("llm service is disabled");
            throw new BaseException(B_LLM_SERVICE_DISABLED);
        }

        QueryRouter queryRouter = new DefaultQueryRouter(retrievers);
        TokenStream tokenStream;
        ChatModelRequest chatModelRequest = params.getHttpRequestParams();
        if (StringUtils.isNotBlank(chatModelRequest.getMemoryId())) {
            ChatMemoryStore memoryStore = leaseGuardedStore(
                    SpringUtil.getBean(ShortTermMemoryService.class), turnLease);
            ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(2)
                    .chatMemoryStore(memoryStore)
                    .build();
            QueryTransformer queryTransformer = StringUtils.isNotBlank(chatModelRequest.getRetrievalQuery())
                    ? ignored -> List.of(Query.from(chatModelRequest.getRetrievalQuery()))
                    : new CompressingQueryTransformer(llmService.buildChatLLM(params.getModelProperties()));
            RetrievalAugmentor retrievalAugmentor = retrievalAugmentor(queryRouter, queryTransformer);
            IStreamingChatAssistant assistant = AiServices.builder(IStreamingChatAssistant.class)
                    .streamingChatModel(llmService.buildStreamingChatModel(params.getModelProperties()))
                    .retrievalAugmentor(retrievalAugmentor)
                    .chatMemoryProvider(chatMemoryProvider)
                    .build();
            if (StringUtils.isNotBlank(chatModelRequest.getSystemMessage())) {
                tokenStream = assistant.chatWithSystem(chatModelRequest.getMemoryId(), chatModelRequest.getSystemMessage(), chatModelRequest.getUserMessage(), new ArrayList<>());
            } else {
                tokenStream = assistant.chat(chatModelRequest.getMemoryId(), chatModelRequest.getUserMessage(), new ArrayList<>());
            }
        } else {
            ITempStreamingChatAssistant assistant = AiServices.builder(ITempStreamingChatAssistant.class)
                    .streamingChatModel(llmService.buildStreamingChatModel(params.getModelProperties()))
                    .retrievalAugmentor(retrievalAugmentor(queryRouter, null))
                    .build();
            if (StringUtils.isNotBlank(chatModelRequest.getSystemMessage())) {
                tokenStream = assistant.chatWithSystem(chatModelRequest.getSystemMessage(), chatModelRequest.getUserMessage(), new ArrayList<>());
            } else {
                tokenStream = assistant.chatSimple(chatModelRequest.getUserMessage(), new ArrayList<>());
            }
        }
        tokenStream
                .onPartialResponse(content -> SseManager.parseAndSendPartialMsg(params.getSseUuid(), content))
                .onCompleteResponse(response -> {
                    Pair<PromptMeta, AnswerMeta> pair = SseManager.calculateToken(response, params.getUuid());
                    consumer.accept(response.aiMessage().text(), pair.getLeft(), pair.getRight());
                })
                .onError(error -> {
                    if (isBreakSearch(error)) {
                        completeWithoutEvidence(params, consumer);
                    } else {
                        SseManager.errorAndShutdown(error, params.getSseUuid());
                    }
                })
                .start();
    }

    private static RetrievalAugmentor retrievalAugmentor(QueryRouter queryRouter,
                                                         QueryTransformer queryTransformer) {
        // The builder implementation type is package-private in langchain4j,
        // so hold it through var and configure conditionally.
        var builder = DefaultRetrievalAugmentor.builder().queryRouter(queryRouter);
        if (queryTransformer != null) {
            builder = builder.queryTransformer(queryTransformer);
        }
        // Labeling distinguishes original chunks from inferred graph relations
        // in the injected context; disabled falls back to langchain4j defaults.
        if (SpringUtil.getBean(ZhiMeshProperties.class)
                .getRetrieval().isEvidenceLabelingEnabled()) {
            builder = builder.contentInjector(new LabeledEvidenceContentInjector());
        }
        return builder.build();
    }

    private static ChatMemoryStore leaseGuardedStore(ChatMemoryStore delegate,
                                                     ShortTermMemoryTurnCoordinator.TurnLease turnLease) {
        return new ChatMemoryStore() {
            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                turnLease.requireValid();
                return delegate.getMessages(memoryId);
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                turnLease.requireValid();
                delegate.updateMessages(memoryId, messages);
            }

            @Override
            public void deleteMessages(Object memoryId) {
                turnLease.requireValid();
                delegate.deleteMessages(memoryId);
            }
        };
    }

    private static boolean isBreakSearch(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BaseException baseException
                    && B_BREAK_SEARCH.getCode().equals(baseException.getCode())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void completeWithoutEvidence(SseAskParam params,
                                                TriConsumer<String, PromptMeta, AnswerMeta> consumer) {
        String noEvidenceAnswer = "根据当前知识库无法确定";
        // Keep the emitter and short-memory lease alive until the owning business
        // callback persists the QA record and sends the terminal SSE event.
        SseManager.parseAndSendPartialMsg(params.getSseUuid(), noEvidenceAnswer);
        consumer.accept(noEvidenceAnswer,
                PromptMeta.builder().inputTokens(0).build(),
                AnswerMeta.builder().outputTokens(0).build());
    }

}
