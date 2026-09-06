package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbQaDto;
import com.pppp.zhimesh.common.dto.QARecordReq;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.entity.*;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseQaRecordMapper;
import com.pppp.zhimesh.common.util.EntityRefreshUtil;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.util.LocalCache.MODEL_ID_TO_OBJ;

@Slf4j
@Service
public class KnowledgeBaseQaService extends ServiceImpl<KnowledgeBaseQaRecordMapper, KnowledgeBaseQa> {

    @Resource
    private KnowledgeBaseQaRecordReferenceService knowledgeBaseQaRecordReferenceService;

    @Resource
    private KnowledgeBaseQaRefGraphService knowledgeBaseQaRecordRefGraphService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private LLMCallRecordService llmCallRecordService;

    public KbQaDto add(KnowledgeBase knowledgeBase, QARecordReq req) {
        // A browser can submit the same composer action twice (keypress + click,
        // or a reconnect). Reuse an unfinished personal record instead of
        // creating a second QA that will race through retrieval.
        KnowledgeBaseQa pending = lambdaQuery()
                .eq(KnowledgeBaseQa::getKbUuid, knowledgeBase.getUuid())
                .eq(KnowledgeBaseQa::getUserId, ThreadContext.getCurrentUserId())
                .eq(KnowledgeBaseQa::getQuestion, req.getQuestion())
                .and(wrapper -> wrapper.isNull(KnowledgeBaseQa::getAnswer)
                        .or().eq(KnowledgeBaseQa::getAnswer, ""))
                .orderByDesc(KnowledgeBaseQa::getUpdateTime)
                .last("limit 1")
                .one();
        if (pending != null) {
            KbQaDto existing = new KbQaDto();
            BeanUtils.copyProperties(pending, existing);
            return existing;
        }
        KnowledgeBaseQa newRecord = new KnowledgeBaseQa();
        newRecord.setAiModelId(aiModelService.getIdByName(req.getModelName()));
        newRecord.setQuestion(req.getQuestion());
        newRecord.setKbId(knowledgeBase.getId());
        newRecord.setKbUuid((knowledgeBase.getUuid()));
        newRecord.setUuid(UuidUtil.createShort());
        newRecord.setUserId(ThreadContext.getCurrentUserId());
        baseMapper.insert(newRecord);

        // Re-read the row so database-managed columns (create_time / update_time)
        // are populated before copying into the DTO.
        KnowledgeBaseQa fresh = EntityRefreshUtil.refresh(baseMapper, newRecord);

        KbQaDto result = new KbQaDto();
        BeanUtils.copyProperties(fresh, result);
        return result;
    }

    public Page<KbQaDto> search(String kbUuid, String keyword, Integer currentPage, Integer pageSize) {
        LambdaQueryWrapper<KnowledgeBaseQa> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBaseQa::getKbUuid, kbUuid);
        // The chat-side history is personal, including for an administrator who
        // is using the user portal. Management-side reporting has its own APIs.
        wrapper.eq(KnowledgeBaseQa::getUserId, ThreadContext.getCurrentUserId());
        if (StringUtils.isNotBlank(keyword)) {
            wrapper.like(KnowledgeBaseQa::getQuestion, keyword);
        }
        wrapper.orderByDesc(KnowledgeBaseQa::getUpdateTime);
        Page<KnowledgeBaseQa> page = baseMapper.selectPage(new Page<>(currentPage, pageSize), wrapper);

        // Batch query LLM call records for token and duration
        List<Long> qaIds = page.getRecords().stream().map(KnowledgeBaseQa::getId).toList();
        Map<Long, LLMCallRecord> idToCallRecord = Map.of();
        if (!qaIds.isEmpty()) {
            idToCallRecord = llmCallRecordService.listBySource(
                    LLMCallRecordSourceType.KNOWLEDGE_BASE_QA.getValue(), qaIds)
                    .stream().collect(Collectors.toMap(LLMCallRecord::getSourceId, r -> r, (a, b) -> a));
        }
        Set<Long> embeddingRefQaIds = qaIds.isEmpty() ? Set.of()
                : new HashSet<>(knowledgeBaseQaRecordReferenceService.listQaRecordIdsWithRefs(qaIds));
        Set<Long> graphRefQaIds = qaIds.isEmpty() ? Set.of()
                : new HashSet<>(knowledgeBaseQaRecordRefGraphService.listQaRecordIdsWithRefs(qaIds));

        Map<Long, LLMCallRecord> finalIdToCallRecord = idToCallRecord;
        Page<KbQaDto> result = new Page<>();
        MPPageUtil.convertToPage(page, result, KbQaDto.class, (t1, t2) -> {
            AiModel aiModel = MODEL_ID_TO_OBJ.get(t1.getAiModelId());
            t2.setAiModelPlatform(null == aiModel ? "" : aiModel.getPlatform());
            // Fill token and duration from LLM call record
            LLMCallRecord callRecord = finalIdToCallRecord.get(t1.getId());
            if (callRecord != null) {
                t2.setInputTokens(callRecord.getInputTokens());
                t2.setOutputTokens(callRecord.getOutputTokens());
                t2.setDuration(callRecord.getDuration());
            }
            t2.setIsRefEmbedding(embeddingRefQaIds.contains(t1.getId()));
            t2.setIsRefGraph(graphRefQaIds.contains(t1.getId()));
            return t2;
        });
        return result;
    }

    /**
     * 增加嵌入引用记录
     *
     * @param user
     * @param qaRecordId       qa记录id
     * @param embeddingToScore
     */
    public void createEmbeddingRefs(User user, Long qaRecordId, Map<String, Double> embeddingToScore) {
        log.info("Updating vector reference, userId:{}, qaRecordId:{}, embeddingToScore.size:{}", user.getId(), qaRecordId, embeddingToScore.size());
        for (Map.Entry<String, Double> entry : embeddingToScore.entrySet()) {
            String embeddingId = entry.getKey();
            KnowledgeBaseQaRefEmbedding recordReference = new KnowledgeBaseQaRefEmbedding();
            recordReference.setQaRecordId(qaRecordId);
            recordReference.setEmbeddingId(embeddingId);
            recordReference.setScore(embeddingToScore.get(embeddingId));
            recordReference.setUserId(user.getId());
            knowledgeBaseQaRecordReferenceService.save(recordReference);
        }
    }

    /**
     * 增加图谱引用记录
     *
     * @param user
     * @param qaRecordId
     * @param graphDto
     */
    public void createGraphRefs(User user, Long qaRecordId, RefGraphDto graphDto) {
        log.info("Updating graph reference, userId:{}, qaRecordId:{}, vertices.Size:{}, edges.size:{}", user.getId(), qaRecordId, graphDto.getVertices().size(), graphDto.getEdges().size());
        String entities = null == graphDto.getEntitiesFromQuestion() ? "" : String.join(",", graphDto.getEntitiesFromQuestion());
        Map<String, Object> graphFromStore = new HashMap<>();
        graphFromStore.put("vertices", graphDto.getVertices());
        graphFromStore.put("edges", graphDto.getEdges());
        KnowledgeBaseQaRefGraph refGraph = new KnowledgeBaseQaRefGraph();
        refGraph.setQaRecordId(qaRecordId);
        refGraph.setUserId(user.getId());
        refGraph.setEntitiesFromQuestion(entities);
        refGraph.setGraphFromStore(JsonUtil.toJson(graphFromStore));
        knowledgeBaseQaRecordRefGraphService.save(refGraph);
    }

    public KnowledgeBaseQa getOrThrow(String uuid) {
        KnowledgeBaseQa exist = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBaseQa::getUuid, uuid)
                
                .one();
        if (null == exist) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        return exist;
    }

    /**
     * Gets a Q&A record that belongs to the caller.  Do not use the unrestricted
     * lookup on user-facing endpoints: UUIDs are request parameters and must not
     * be allowed to cross user boundaries.
     */
    public KnowledgeBaseQa getOwnedOrThrow(String uuid) {
        return getOwnedOrThrow(uuid, ThreadContext.getCurrentUserId());
    }

    /**
     * Same ownership check for asynchronous work, where ThreadContext is not
     * available and the authenticated user has already been passed explicitly.
     */
    public KnowledgeBaseQa getOwnedOrThrow(String uuid, Long userId) {
        KnowledgeBaseQa record = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBaseQa::getUuid, uuid)
                .eq(KnowledgeBaseQa::getUserId, userId)
                .one();
        if (record == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        return record;
    }

    public void clearByCurrentUser() {
        baseMapper.delete(new LambdaQueryWrapper<KnowledgeBaseQa>()
                .eq(KnowledgeBaseQa::getUserId, ThreadContext.getCurrentUserId()));
    }

    public boolean softDelete(String uuid) {
        KnowledgeBaseQa exist = getOwnedOrThrow(uuid);
        return baseMapper.deleteById(exist.getId()) > 0;
    }
}
