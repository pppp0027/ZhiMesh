package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.util.CharacterChatHelper;
import com.pppp.zhimesh.common.util.PromptUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryKeyResolver;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryWindow;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.vo.*;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CHARACTER_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CONVERSATION_CHARACTER_MISMATCH;

/**
 * Local in-process implementation of {@link AgentService}.
 *
 * <p>Loads a Character's configuration and executes the full agent pipeline:
 * RAG retrieval → prompt enhancement → LLM call.</p>
 *
 * <p>When {@code manageMemoryTurn} is enabled, this service owns the complete
 * short-memory turn and appends the final AI message. The LLM consumption is
 * charged to the initiating user's daily cost here (the workflow agent node
 * runs under a workflow uuid that no aggregator ever reads), while database
 * message persistence and audio/TTS remain the caller's responsibility.</p>
 */
@Slf4j
@Service
public class LocalAgentService implements AgentService {

    @Resource
    private CharacterService characterService;

    @Resource
    private CharacterMapper characterMapper;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ShortTermMemoryKeyResolver shortTermMemoryKeyResolver;
    @Resource
    private ShortTermMemoryService shortTermMemoryService;
    @Resource
    private ShortTermMemoryTurnCoordinator shortTermMemoryTurnCoordinator;

    @Resource
    private EmbeddingModel embeddingModel;

    @Override
    public AgentResult invoke(AgentRequest request, User user, String uuid) {
        // 1. Load Character
        Character character = findOwnedCharacter(request, user);

        String memoryId = resolveMemoryId(request, user, character);
        boolean manageMemoryTurn = !Boolean.FALSE.equals(request.getManageMemoryTurn());
        ShortTermMemoryTurnCoordinator.TurnLease turnLease = manageMemoryTurn
                ? shortTermMemoryTurnCoordinator.acquire(memoryId, null)
                : shortTermMemoryTurnCoordinator.acquire(null, null);
        try {

        // 2. Resolve LLM service (use config override or system default)
        AbstractLLMService llmService = LLMContext.getServiceOrDefault(
                request.getModelPlatform(), request.getModelName());

        // 3. RAG retrieval (if enabled)
        List<RetrieverWrapper> retrieverWrappers = new ArrayList<>();
        int retrievalCount = 0;
        if (request.isEnableRag()) {
            // System KBs from a preset intentionally are not persisted in the
            // user-owned kbIds field. Resolve by Character so every RAG entry
            // point applies the preset binding with the same authorization.
            List<KbInfoResp> filteredKb = characterService.filterEnableKb(user, character);
            // 上下文恒启用（2026-09-24 产品决策：understandContextEnable 开关已下线，
            // 列保留不读），检索恒携带短期记忆ID（memoryId 为 null 语义不变）
            // Context is always on (2026-09-24 product decision: the
            // understandContextEnable toggle is retired, the column is kept but
            // never read); retrieval always carries the short-term memory id
            // (a null memoryId keeps its meaning)
            retrieverWrappers = CharacterChatHelper.retrieve(
                    character.getId(), filteredKb, llmService, embeddingModel,
                    request.getInputText(), memoryId);
            retrievalCount = retrieverWrappers.stream()
                    .mapToInt(w -> w.getResponse() != null ? w.getResponse().size() : 0).sum();
        }

        // 4. Prompt enhancement
        Pair<String, String> memoryAndKnowledge = CharacterChatHelper.buildMemoryAndKnowledge(retrieverWrappers);
        String effectiveLocale = StringUtils.isNotBlank(user.getLocale())
                ? user.getLocale()
                : Objects.toString(SysConfigService.getByKey(ZhiMeshConstant.SysConfigKey.DEFAULT_LOCALE), "zh-CN");
        String processedPrompt = PromptUtil.createPrompt(
                request.getInputText(), memoryAndKnowledge.getLeft(), memoryAndKnowledge.getRight(), "", effectiveLocale);

        // 5. Build request params (system message + memory + MCP tools + thinking + web search)
        ChatModelRequest chatRequestParams = CharacterChatHelper.buildChatRequestParams(
                character, memoryId,
                processedPrompt, user, llmService, request.isEnableMcp(), request.isEnableWebSearch(), null);
        chatRequestParams.setShortTermMemoryUserMessage(request.getInputText());

        // 6. Blocking LLM call
        SseAskParam sseAskParam = new SseAskParam();
        sseAskParam.setUser(user);
        sseAskParam.setUuid(uuid);
        sseAskParam.setModelName(llmService.getAiModel().getName());
        sseAskParam.setHttpRequestParams(chatRequestParams);
        sseAskParam.setModelProperties(
                ChatModelBuilderProperties.builder()
                        .temperature(character.getLlmTemperature())
                        .returnThinking(chatRequestParams.getReturnThinking())
                        .build()
        );

        ChatResponse chatResponse = llmService.chat(sseAskParam);

        if (manageMemoryTurn && StringUtils.isNotBlank(memoryId)) {
            int maxTokens = chatRequestParams.getMemoryWindowMaxTokens() != null
                    && chatRequestParams.getMemoryWindowMaxTokens() > 0
                    ? chatRequestParams.getMemoryWindowMaxTokens()
                    : llmService.getAiModel().getMaxInputTokens();
            ShortTermMemoryWindow.append(
                    shortTermMemoryService,
                    memoryId,
                    maxTokens,
                    llmService.resolveTokenCountEstimator(),
                    AiMessage.builder()
                            .text(chatResponse.aiMessage().text())
                            .thinking(chatResponse.aiMessage().thinking())
                            .build());
        }

        // 7. Build result
        Integer inputTokens = null;
        Integer outputTokens = null;
        if (chatResponse.metadata() != null && chatResponse.metadata().tokenUsage() != null) {
            inputTokens = chatResponse.metadata().tokenUsage().inputTokenCount();
            outputTokens = chatResponse.metadata().tokenUsage().outputTokenCount();
            appendLlmCostToUserSafely(user, llmService, chatResponse.metadata().tokenUsage());
        }
        return AgentResult.builder()
                .answer(chatResponse.aiMessage().text())
                .thinking(chatResponse.aiMessage().thinking())
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .retrievalCount(retrievalCount)
                .memoryWindowMaxTokens(chatRequestParams.getMemoryWindowMaxTokens())
                .build();
        } finally {
            turnLease.close();
        }
    }

    /**
     * 工作流 Agent 节点的 LLM 消耗计入发起用户日成本：节点跑在工作流 uuid 下、
     * 该 uuid 的 token 缓存没有任何聚合方读取，若不在此处直接入账则永久漏账；
     * 入账失败只记日志，不回滚已生成的节点回答（与 WorkflowUtil 入账语义一致）
     * <p>
     * Charge the workflow agent node's LLM consumption to the initiating user's
     * daily cost: the node runs under a workflow uuid whose token cache is never
     * read by any aggregator, so skipping the charge here leaks the cost
     * permanently; a ledger failure only logs and never rolls back the generated
     * node output (same semantics as the WorkflowUtil accounting).
     */
    void appendLlmCostToUserSafely(User user, AbstractLLMService llmService, TokenUsage tokenUsage) {
        if (null == tokenUsage) {
            return;
        }
        Integer totalTokens = tokenUsage.totalTokenCount();
        int tokens = null != totalTokens ? totalTokens
                : (null != tokenUsage.inputTokenCount() ? tokenUsage.inputTokenCount() : 0)
                        + (null != tokenUsage.outputTokenCount() ? tokenUsage.outputTokenCount() : 0);
        try {
            boolean isFree = null != llmService && null != llmService.getAiModel()
                    && Boolean.TRUE.equals(llmService.getAiModel().getIsFree());
            SpringUtil.getBean(UserDayCostService.class).appendCostToUser(user, tokens, isFree);
        } catch (Exception e) {
            log.error("Failed to append workflow agent LLM cost, characterUuid:{}", user.getUuid(), e);
        }
    }

    Character findOwnedCharacter(AgentRequest request, User user) {
        Character character = characterMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Character>()
                        .eq(Character::getUuid, request.getCharacterUuid())
                        .eq(Character::getUserId, user.getId())
                        );
        if (character == null) {
            throw new BaseException(A_CHARACTER_NOT_FOUND);
        }
        return character;
    }

    String resolveMemoryId(AgentRequest request, User user, Character character) {
        String memoryId = request.getMemoryId();
        if (StringUtils.isNotBlank(request.getConversationUuid())) {
            Conversation conversation = conversationService.getOwnedOrThrow(user.getId(), request.getConversationUuid());
            if (!character.getId().equals(conversation.getCharacterId())) {
                throw new BaseException(A_CONVERSATION_CHARACTER_MISMATCH);
            }
            memoryId = shortTermMemoryKeyResolver.forConversation(conversation.getUuid());
        }
        return memoryId;
    }
}
