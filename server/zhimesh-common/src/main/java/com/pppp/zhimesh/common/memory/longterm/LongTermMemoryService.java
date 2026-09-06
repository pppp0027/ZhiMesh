package com.pppp.zhimesh.common.memory.longterm;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.LLMCallRecord;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.memory.vo.BatchActionMemories;
import com.pppp.zhimesh.common.memory.vo.ExtractedEpisodicEvent;
import com.pppp.zhimesh.common.memory.vo.ExtractedFact;
import com.pppp.zhimesh.common.memory.vo.ExtractedMemories;
import com.pppp.zhimesh.common.memory.vo.MemoryAddParam;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.service.LLMCallRecordService;
import com.pppp.zhimesh.common.service.UserDayCostService;
import com.pppp.zhimesh.common.util.ZhiMeshStringUtil;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
import com.pppp.zhimesh.common.vo.SseAskParam;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.RESPONSE_FORMAT_TYPE_JSON_OBJECT;

/**
 * Long-term memory dispatcher. Owns the LLM extraction round-trip and dispatches
 * the result to {@link SemanticMemoryService} (semantic facts) and
 * {@link EpisodicMemoryService} (episodic events). Per-call recording + per-call
 * user day-cost accounting happen here in lock-step ("同频结算"): every LLM call
 * is followed by a paired {@code adi_llm_call_record} write and
 * {@code userDayCost} update, so accounts stay accurate even if the flow fails
 * mid-way.
 * <p>
 * 长期记忆调度器。本身负责 LLM 抽取，并把结果分发给 {@link SemanticMemoryService}（语义事实）
 * 和 {@link EpisodicMemoryService}（情景事件）。LLM 调用记录写库与用户日消费结算"同频"——
 * 每次 LLM 调用之后立刻成对发生，流程中途失败也不会漏算成本。
 */
@Slf4j
@Service
public class LongTermMemoryService {

    @Resource
    private UserDayCostService userDayCostService;
    @Resource
    private LLMCallRecordService llmCallRecordService;
    @Resource
    private SemanticMemoryService semanticMemoryService;
    @Resource
    private EpisodicMemoryService episodicMemoryService;
    @Resource
    private LongTermMemoryUpdateCoordinator memoryUpdateCoordinator;

    @Async("backgroundExecutor")
    public void asyncAdd(MemoryAddParam request) {
        log.info("Converting messages to memory, characterId:{}", request.getCharacterId());
        String inputMessage = toInputMessage(request.getUserMessage(), request.getAssistantMessage());
        log.info("Long-term memory input prepared, characterId:{},inputChars:{}",
                request.getCharacterId(), inputMessage.length());
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(request.getModelPlatform(), request.getModelName());

        // ===== Stage 1: fact extraction (1 LLM call) =====
        ChatResponse response = llmService.chat(buildExtractionRequest(request, inputMessage));
        recordLlmCall(request, response, LLMCallRecordSourceType.LONG_TERM_MEMORY_EXTRACTION);

        log.info("Fact extraction completed, characterId:{},responseChars:{}", request.getCharacterId(),
                StringUtils.length(response.aiMessage().text()));
        String factResponse = ZhiMeshStringUtil.removeCodeBlock(response.aiMessage().text());
        if (StringUtils.isBlank(factResponse)) {
            log.warn("Unable to extract factual information from this content");
            return;
        }
        ExtractedMemories extracted = parseExtractedMemories(factResponse);
        List<String> facts = extracted.getSemanticFacts() != null
                ? new ArrayList<>(extracted.getSemanticFacts())
                : new ArrayList<>();
        List<ExtractedEpisodicEvent> episodes = extracted.getEpisodicEvents() != null
                ? new ArrayList<>(extracted.getEpisodicEvents())
                : new ArrayList<>();

        // Dispatch episodic: append-only, independent of the semantic merge path.
        // 分发情景事件：append-only，与语义合并互不干扰。
        if (!episodes.isEmpty()) {
            try {
                episodicMemoryService.batchAdd(request.getCharacterId(),
                        request.getUser().getId(),
                        request.getSourceMsgId(),
                        sourceMessageTime(request),
                        request.getUserMessage(),
                        episodes,
                        request.isFreeToken());
            } catch (Exception e) {
                log.error("Failed to add episodic memories for characterId={}", request.getCharacterId(), e);
            }
        }

        if (CollectionUtils.isEmpty(facts)) {
            if (episodes.isEmpty()) {
                log.info("No memorable info this round, skipped");
            }
            return;
        }

        // Stages 2-4 are serialized per character. Candidate search and the action
        // decision must observe the same logical memory generation.
        try (LongTermMemoryUpdateCoordinator.Lease lease =
                     memoryUpdateCoordinator.acquire(request.getCharacterId())) {
            // ===== Stage 2: per-fact merge-candidate search (embedding only, no LLM) =====
            SemanticMemoryService.MergeCandidates candidates =
                    semanticMemoryService.searchMergeCandidates(facts, request.getCharacterId());
            if (candidates.isEmpty()) {
                log.info("No non-blank fact left to analyze");
                return;
            }

            // ===== Stage 3: batch memory analysis (1 LLM call for ALL facts) =====
            String analyzePrompt = LongTermMemoryPrompt.buildBatchUpdatePrompt(
                    candidates.factToOldMemoriesForPrompt);
            ChatResponse analyzeResp = llmService.chat(buildAnalysisRequest(request, analyzePrompt));
            recordLlmCall(request, analyzeResp, LLMCallRecordSourceType.LONG_TERM_MEMORY_ANALYSIS);

            String resp = analyzeResp.aiMessage().text();
            log.info("Batch memory analysis completed, characterId:{},responseChars:{}",
                    request.getCharacterId(), StringUtils.length(resp));
            String analyzedMsg = ZhiMeshStringUtil.removeCodeBlock(resp);
            BatchActionMemories batchActions = parseBatchActions(analyzedMsg);
            if (batchActions == null || CollectionUtils.isEmpty(batchActions.getActions())) {
                log.warn("Batch memory analysis returned empty actions, responseChars:{}",
                        StringUtils.length(analyzedMsg));
                return;
            }

            lease.requireValid();
            // ===== Stage 4: atomic semantic actions (no LLM) =====
            semanticMemoryService.applyActions(batchActions, request.getCharacterId(),
                    request.getUser().getId(), request.getSourceMsgId(), candidates);
        } catch (RuntimeException exception) {
            // Fail closed: an unavailable/lost lock must never permit concurrent
            // mutation of the same character's semantic memory.
            log.error("Semantic memory merge skipped safely, characterId:{}",
                    request.getCharacterId(), exception);
        }
    }

    private SseAskParam buildExtractionRequest(MemoryAddParam request, String inputMessage) {
        SseAskParam sseAskParam = new SseAskParam();
        sseAskParam.setUuid(UuidUtil.createShort());
        sseAskParam.setHttpRequestParams(
                ChatModelRequest.builder()
                        .systemMessage(LongTermMemoryPrompt.buildExtractionPrompt(
                                sourceMessageTime(request), ZoneId.systemDefault()))
                        .userMessage(inputMessage)
                        .responseFormat(RESPONSE_FORMAT_TYPE_JSON_OBJECT)
                        .build()
        );
        sseAskParam.setModelName(request.getModelName());
        sseAskParam.setUser(request.getUser());
        return sseAskParam;
    }

    private SseAskParam buildAnalysisRequest(MemoryAddParam request, String analyzePrompt) {
        return SseAskParam.builder()
                .uuid(UuidUtil.createShort())
                .httpRequestParams(
                        ChatModelRequest.builder()
                                .userMessage(analyzePrompt)
                                .responseFormat(RESPONSE_FORMAT_TYPE_JSON_OBJECT)
                                .build()
                )
                .modelName(request.getModelName())
                .user(request.getUser())
                .build();
    }

    /**
     * Record this LLM call to {@code adi_llm_call_record} and immediately append
     * its tokens to the user's day-cost (同频结算). Pairing the two writes here
     * eliminates the bookkeeping risk of accumulating tokens and "early settling"
     * on every failure path.
     * <p>
     * 写入 LLM 调用记录并立即累加用户日消费——成对发生，避免"中途失败漏算"的复杂处理。
     *
     * @param sourceType identifies which LLM step produced this call; one value per
     *                   request, so it doubles as the call's business provenance.
     */
    private void recordLlmCall(MemoryAddParam request, ChatResponse response,
                                LLMCallRecordSourceType sourceType) {
        int[] tokens = extractTokenUsage(response);
        saveCallRecord(request.getUser(), request.getModelPlatform(), request.getModelName(),
                tokens[0], tokens[1], sourceType);
        appendCostSafely(request.getUser(), tokens[0] + tokens[1], request.isFreeToken());
    }

    /**
     * Parse the dual-task extraction payload. Falls back gracefully through several formats:
     * <ol>
     *   <li>New {@code {semantic_facts, episodic_events}} dual structure.</li>
     *   <li>Legacy {@code {facts: [...]}} form — populates only semanticFacts.</li>
     *   <li>Raw JSON array — populates only semanticFacts.</li>
     * </ol>
     * Any failure returns an empty {@link ExtractedMemories}; caller decides how to handle empties.
     * <p>
     * 解析双任务提取结果。依次尝试新结构、旧 {facts} 结构、纯数组。解析失败返回空对象。
     */
    private ExtractedMemories parseExtractedMemories(String raw) {
        ExtractedMemories empty = new ExtractedMemories();
        empty.setSemanticFacts(new ArrayList<>());
        empty.setEpisodicEvents(new ArrayList<>());
        if (StringUtils.isBlank(raw)) {
            return empty;
        }

        // Path 1: new dual structure.
        try {
            ExtractedMemories newFormat = JsonUtil.fromJson(raw, ExtractedMemories.class);
            if (newFormat != null
                    && (newFormat.getSemanticFacts() != null || newFormat.getEpisodicEvents() != null)) {
                if (newFormat.getSemanticFacts() == null) {
                    newFormat.setSemanticFacts(new ArrayList<>());
                }
                if (newFormat.getEpisodicEvents() == null) {
                    newFormat.setEpisodicEvents(new ArrayList<>());
                }
                return newFormat;
            }
        } catch (Exception e) {
            log.debug("New ExtractedMemories format parse failed, trying legacy, responseChars:{}", raw.length());
        }

        // Path 2 / 3: legacy {facts:[]} or raw array.
        try {
            List<String> facts;
            if (raw.trim().startsWith("[")) {
                facts = JsonUtil.toList(raw, String.class);
            } else {
                ExtractedFact extractedFact = JsonUtil.fromJson(raw, ExtractedFact.class);
                facts = extractedFact != null ? extractedFact.getFacts() : null;
            }
            if (facts != null && !facts.isEmpty()) {
                empty.setSemanticFacts(new ArrayList<>(facts));
            } else {
                log.warn("Content cannot be parsed as facts/memories, responseChars:{}", raw.length());
            }
        } catch (Exception e) {
            log.warn("Failed to parse extraction JSON, responseChars:{},errorType:{}",
                    raw.length(), e.getClass().getSimpleName());
        }
        return empty;
    }

    /**
     * Parse batch action result. Returns null on any parse failure; caller logs and skips.
     * <p>
     * 解析批量 action 结果。任何解析失败都返回 null，调用方记录后跳过本轮。
     */
    private BatchActionMemories parseBatchActions(String json) {
        try {
            return JsonUtil.fromJson(json, BatchActionMemories.class);
        } catch (Exception e) {
            log.warn("Failed to parse BatchActionMemories, responseChars:{},errorType:{}",
                    StringUtils.length(json), e.getClass().getSimpleName());
            return null;
        }
    }

    private int[] extractTokenUsage(ChatResponse response) {
        if (response != null && response.metadata() != null && response.metadata().tokenUsage() != null) {
            return new int[]{
                    response.metadata().tokenUsage().inputTokenCount() != null
                            ? response.metadata().tokenUsage().inputTokenCount() : 0,
                    response.metadata().tokenUsage().outputTokenCount() != null
                            ? response.metadata().tokenUsage().outputTokenCount() : 0
            };
        }
        return new int[]{0, 0};
    }

    private void saveCallRecord(User user, String modelPlatform, String modelName,
                                int inputTokens, int outputTokens, LLMCallRecordSourceType sourceType) {
        if (inputTokens == 0 && outputTokens == 0) {
            return;
        }
        try {
            LLMCallRecord record = new LLMCallRecord();
            record.setUuid(UuidUtil.createShort());
            record.setSourceType(sourceType.getValue());
            record.setSourceId(0L);
            record.setUserId(user.getId());
            record.setModelPlatform(modelPlatform);
            record.setModelName(modelName);
            record.setInputTokens(inputTokens);
            record.setOutputTokens(outputTokens);
        llmCallRecordService.saveRecord(record);
        } catch (Exception e) {
            log.error("Failed to save LLM call record, sourceType: {}", sourceType, e);
        }
    }

    private void appendCostSafely(User user, int totalTokens, boolean isFreeToken) {
        if (totalTokens <= 0) {
            return;
        }
        try {
            userDayCostService.appendCostToUser(user, totalTokens, isFreeToken);
        } catch (Exception e) {
            log.error("Failed to append long-term memory cost for userId: {}", user.getId(), e);
        }
    }

    private String toInputMessage(String userMessage, String assistantMessage) {
        return """
                Input:
                user: %s
                assistant: %s
                """.formatted(userMessage, assistantMessage);
    }

    private static LocalDateTime sourceMessageTime(MemoryAddParam request) {
        return request.getSourceMessageTime() != null
                ? request.getSourceMessageTime() : LocalDateTime.now();
    }
}
