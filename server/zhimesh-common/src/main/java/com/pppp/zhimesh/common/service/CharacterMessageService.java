package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.entity.CharacterMessage;
import com.pppp.zhimesh.common.entity.CharacterMessageRefEmbedding;
import com.pppp.zhimesh.common.entity.CharacterMessageRefGraph;
import com.pppp.zhimesh.common.entity.CharacterMessageRefMemoryEmbedding;
import com.pppp.zhimesh.common.entity.CharacterMessageRefBm25;
import com.pppp.zhimesh.common.rag.bm25.Bm25ContentRetriever;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.MemoryType;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.CharacterMessageMapper;
import com.pppp.zhimesh.common.rag.ZhiMeshEmbeddingStoreContentRetriever;
import com.pppp.zhimesh.common.rag.GraphStoreContentRetriever;
import com.pppp.zhimesh.common.util.JsonUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pppp.zhimesh.common.enums.ErrorEnum.B_MESSAGE_NOT_FOUND;

/**
 * Message CRUD service: pure database operations, no chat orchestration logic.
 */
@Slf4j
@Service
public class CharacterMessageService extends ServiceImpl<CharacterMessageMapper, CharacterMessage> {

    @Resource
    private CharacterMessageRefEmbeddingService characterMessageRefEmbeddingService;

    @Resource
    private CharacterMessageRefGraphService characterMessageRefGraphService;

    @Resource
    private CharacterMessageRefMemoryEmbeddingService characterMessageRefMemoryEmbeddingService;

    @Resource
    private CharacterMessageRefBm25Service characterMessageRefBm25Service;

    public List<CharacterMessage> listQuestionsByCharacterId(long characterId, long maxId, int pageSize) {
        LambdaQueryWrapper<CharacterMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CharacterMessage::getCharacterId, characterId);
        queryWrapper.eq(CharacterMessage::getParentMessageId, 0);
        queryWrapper.lt(CharacterMessage::getId, maxId);
        queryWrapper.last("limit " + pageSize);
        queryWrapper.orderByDesc(CharacterMessage::getId);
        return getBaseMapper().selectList(queryWrapper);
    }

    public List<CharacterMessage> listQuestionsByConversationId(long conversationId, long maxId, int pageSize) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        return lambdaQuery()
                .eq(CharacterMessage::getConversationId, conversationId)
                .eq(CharacterMessage::getParentMessageId, 0L)
                .lt(CharacterMessage::getId, maxId)
                
                .orderByDesc(CharacterMessage::getId)
                .last("limit " + safePageSize)
                .list();
    }

    public CharacterMessage getOwnedQuestionInConversation(String questionUuid, Long conversationId, Long userId) {
        CharacterMessage question = lambdaQuery()
                .eq(CharacterMessage::getUuid, questionUuid)
                .eq(CharacterMessage::getConversationId, conversationId)
                .eq(CharacterMessage::getUserId, userId)
                .eq(CharacterMessage::getParentMessageId, 0L)
                
                .one();
        if (question == null) {
            throw new BaseException(B_MESSAGE_NOT_FOUND);
        }
        return question;
    }

    /**
     * Returns a message only when it belongs to the current user.  Reference
     * records are keyed by message UUID, so the ownership check must happen
     * before returning their vector, graph, or memory details.
     */
    public CharacterMessage getOwnedOrThrow(String uuid) {
        CharacterMessage message = lambdaQuery()
                .eq(CharacterMessage::getUuid, uuid)
                .eq(CharacterMessage::getUserId, ThreadContext.getCurrentUserId())
                .one();
        if (message == null) {
            throw new BaseException(B_MESSAGE_NOT_FOUND);
        }
        return message;
    }

    public long countByConversationId(Long conversationId) {
        return lambdaQuery()
                .eq(CharacterMessage::getConversationId, conversationId)
                
                .count();
    }

    /**
     * Soft-delete every message owned by a Conversation. Keeping this in the same
     * transaction as Conversation deletion prevents legacy Character history APIs
     * from exposing messages from a deleted Conversation.
     */
    public boolean softDeleteByConversationIds(Long userId, List<Long> conversationIds) {
        if (userId == null || CollectionUtils.isEmpty(conversationIds)) {
            return false;
        }
        return this.remove(new LambdaQueryWrapper<CharacterMessage>()
                .eq(CharacterMessage::getUserId, userId)
                .in(CharacterMessage::getConversationId, conversationIds)
        );
    }

    public boolean softDelete(String uuid) {
        return this.remove(new LambdaQueryWrapper<CharacterMessage>()
                .eq(CharacterMessage::getUuid, uuid)
                .eq(CharacterMessage::getUserId, ThreadContext.getCurrentUserId())
        );
    }

    public String getTextByAudioUuid(String audioUuid) {
        if (audioUuid == null || audioUuid.isBlank()) {
            return null;
        }
        CharacterMessage conversationMessage = this.lambdaQuery()
                .eq(CharacterMessage::getAudioUuid, audioUuid)
                .eq(CharacterMessage::getUserId, ThreadContext.getCurrentUserId())
                
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        if (null == conversationMessage) {
            return null;
        }
        return conversationMessage.getRemark();
    }

    /**
     * 增加嵌入引用记录
     *
     * @param user             用户
     * @param messageId        消息id
     * @param embeddingToScore 嵌入向量id和分数的映射
     */
    public void createEmbeddingRefs(User user, Long messageId, Map<String, Double> embeddingToScore) {
        log.info("Creating vector reference, userId:{}, qaRecordId:{}, embeddingToScore.size:{}", user.getId(), messageId, embeddingToScore.size());
        for (Map.Entry<String, Double> entry : embeddingToScore.entrySet()) {
            String embeddingId = entry.getKey();
            CharacterMessageRefEmbedding recordReference = new CharacterMessageRefEmbedding();
            recordReference.setMessageId(messageId);
            recordReference.setEmbeddingId(embeddingId);
            recordReference.setScore(embeddingToScore.get(embeddingId));
            recordReference.setUserId(user.getId());
            characterMessageRefEmbeddingService.save(recordReference);
        }
    }

    public void createMemoryRefs(User user, Long messageId, Map<String, Double> embeddingToScore, MemoryType memoryType) {
        log.info("Creating memory vector reference, userId:{}, qaRecordId:{}, memoryType:{}, embeddingToScore.size:{}",
                user.getId(), messageId, memoryType, embeddingToScore.size());
        for (Map.Entry<String, Double> entry : embeddingToScore.entrySet()) {
            String embeddingId = entry.getKey();
            CharacterMessageRefMemoryEmbedding refEmb = new CharacterMessageRefMemoryEmbedding();
            refEmb.setMessageId(messageId);
            refEmb.setEmbeddingId(embeddingId);
            refEmb.setScore(embeddingToScore.get(embeddingId));
            refEmb.setMemoryType(memoryType);
            refEmb.setUserId(user.getId());
            characterMessageRefMemoryEmbeddingService.save(refEmb);
        }
    }

    public void createBm25Refs(User user, Long messageId, List<String> queryTerms,
                               List<Bm25ContentRetriever.Bm25RetrievedHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        String termsJson = JsonUtil.toJson(queryTerms == null ? List.of() : queryTerms);
        for (Bm25ContentRetriever.Bm25RetrievedHit hit : hits) {
            CharacterMessageRefBm25 ref = new CharacterMessageRefBm25();
            ref.setMessageId(messageId);
            ref.setQueryTerms(termsJson);
            ref.setChunkUuid(hit.chunkUuid());
            ref.setKbUuid(hit.kbUuid());
            ref.setKbItemUuid(hit.kbItemUuid());
            ref.setContentSnapshot(hit.content());
            ref.setScore(hit.score());
            ref.setHitRank(hit.rank());
            ref.setUserId(user.getId());
            characterMessageRefBm25Service.save(ref);
        }
    }

    /**
     * 增加图谱引用记录
     *
     * @param user      用户
     * @param messageId 消息id
     * @param graphDto  图谱引用数据传输对象
     */
    public void createGraphRefs(User user, Long messageId, RefGraphDto graphDto) {
        log.info("Preparing to create graph reference, userId:{}, qaRecordId:{}, vertices.Size:{}, edges.size:{}", user.getId(), messageId, graphDto.getVertices().size(), graphDto.getEdges().size());
        if (graphDto.getVertices().isEmpty() && graphDto.getEdges().isEmpty()) {
            log.warn("Graph reference data is empty, cannot create graph reference record, userId:{}, qaRecordId:{}", user.getId(), messageId);
            return;
        }
        String entities = null == graphDto.getEntitiesFromQuestion() ? "" : String.join(",", graphDto.getEntitiesFromQuestion());
        Map<String, Object> graphFromStore = new HashMap<>();
        graphFromStore.put("vertices", graphDto.getVertices());
        graphFromStore.put("edges", graphDto.getEdges());
        CharacterMessageRefGraph refGraph = new CharacterMessageRefGraph();
        refGraph.setMessageId(messageId);
        refGraph.setUserId(user.getId());
        refGraph.setEntitiesFromQuestion(entities);
        refGraph.setGraphFromStore(JsonUtil.toJson(graphFromStore));
        characterMessageRefGraphService.save(refGraph);
    }
}
