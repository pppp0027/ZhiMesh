package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.dto.conversation.ConversationBackfillResult;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.CharacterMessage;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.ConversationBackfill;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.mapper.CharacterMessageMapper;
import com.pppp.zhimesh.common.mapper.ConversationBackfillMapper;
import com.pppp.zhimesh.common.util.UuidUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CHARACTER_NOT_EXIST;

@Slf4j
@Service
public class ConversationBackfillService {

    private static final int BATCH_SIZE = 200;
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String DONE = "DONE";
    private static final String FAILED = "FAILED";

    @Resource
    private CharacterMapper characterMapper;
    @Resource
    private CharacterMessageMapper characterMessageMapper;
    @Resource
    private ConversationBackfillMapper backfillMapper;
    @Resource
    private ConversationService conversationService;
    @Resource
    private TransactionTemplate transactionTemplate;

    public ConversationBackfillResult backfillCharacter(Long userId, Long characterId) {
        Character character = characterMapper.selectOne(new LambdaQueryWrapper<Character>()
                .eq(Character::getId, characterId)
                .eq(Character::getUserId, userId)
                );
        if (character == null) {
            throw new BaseException(A_CHARACTER_NOT_EXIST);
        }
        Conversation conversation = conversationService.getOrCreateDefault(userId, character);
        ConversationBackfill job = getOrCreateJob(userId, characterId, conversation.getId());
        if (DONE.equals(job.getStatus())) {
            return resultOf(job, 0);
        }

        try {
            markRunning(job);
            processUntilCaughtUp(job, conversation);
            job.setStatus(DONE);
            job.setErrorMessage(null);
            backfillMapper.updateById(job);
            return resultOf(job, 0);
        } catch (RuntimeException ex) {
            log.error("Conversation message backfill failed, userId:{}, characterId:{}, jobUuid:{}",
                    userId, characterId, job.getUuid(), ex);
            job.setStatus(FAILED);
            job.setErrorMessage(StringUtils.substring(ex.getMessage(), 0, 2000));
            backfillMapper.updateById(job);
            return resultOf(job, 1);
        }
    }

    private void processUntilCaughtUp(ConversationBackfill job, Conversation conversation) {
        while (true) {
            Long highWater = findMaxUnassignedMessageId(job.getUserId(), job.getCharacterId());
            if (highWater == null || highWater <= job.getLastProcessedMessageId()) {
                return;
            }
            job.setHighWaterMessageId(highWater);
            backfillMapper.updateById(job);

            while (job.getLastProcessedMessageId() < highWater) {
                List<CharacterMessage> batch = findBatch(job, highWater);
                if (batch.isEmpty()) {
                    job.setLastProcessedMessageId(highWater);
                    backfillMapper.updateById(job);
                    break;
                }
                transactionTemplate.executeWithoutResult(status -> updateBatch(job, conversation, batch));
            }
        }
    }

    private void updateBatch(ConversationBackfill job, Conversation conversation, List<CharacterMessage> batch) {
        List<Long> ids = batch.stream().map(CharacterMessage::getId).toList();
        int updated = characterMessageMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CharacterMessage>()
                        .in(CharacterMessage::getId, ids)
                        .eq(CharacterMessage::getUserId, job.getUserId())
                        .eq(CharacterMessage::getCharacterId, job.getCharacterId())
                        .eq(CharacterMessage::getConversationId, 0L)
                        
                        .set(CharacterMessage::getConversationId, conversation.getId())
                        .set(CharacterMessage::getConversationUuid, conversation.getUuid()));
        job.setScannedCount(job.getScannedCount() + batch.size());
        job.setUpdatedCount(job.getUpdatedCount() + updated);
        job.setLastProcessedMessageId(batch.get(batch.size() - 1).getId());
        backfillMapper.updateById(job);
    }

    private List<CharacterMessage> findBatch(ConversationBackfill job, Long highWater) {
        return characterMessageMapper.selectList(new LambdaQueryWrapper<CharacterMessage>()
                .select(CharacterMessage::getId)
                .eq(CharacterMessage::getUserId, job.getUserId())
                .eq(CharacterMessage::getCharacterId, job.getCharacterId())
                .eq(CharacterMessage::getConversationId, 0L)
                
                .gt(CharacterMessage::getId, job.getLastProcessedMessageId())
                .le(CharacterMessage::getId, highWater)
                .orderByAsc(CharacterMessage::getId)
                .last("limit " + BATCH_SIZE));
    }

    private Long findMaxUnassignedMessageId(Long userId, Long characterId) {
        CharacterMessage message = characterMessageMapper.selectOne(new LambdaQueryWrapper<CharacterMessage>()
                .select(CharacterMessage::getId)
                .eq(CharacterMessage::getUserId, userId)
                .eq(CharacterMessage::getCharacterId, characterId)
                .eq(CharacterMessage::getConversationId, 0L)
                
                .orderByDesc(CharacterMessage::getId)
                .last("limit 1"));
        return message == null ? null : message.getId();
    }

    private ConversationBackfill getOrCreateJob(Long userId, Long characterId, Long conversationId) {
        ConversationBackfill existing = backfillMapper.selectOne(new LambdaQueryWrapper<ConversationBackfill>()
                .eq(ConversationBackfill::getUserId, userId)
                .eq(ConversationBackfill::getCharacterId, characterId)
                );
        if (existing != null) {
            return existing;
        }
        ConversationBackfill job = new ConversationBackfill();
        job.setUuid(UuidUtil.createShort());
        job.setUserId(userId);
        job.setCharacterId(characterId);
        job.setConversationId(conversationId);
        job.setHighWaterMessageId(0L);
        job.setLastProcessedMessageId(0L);
        job.setScannedCount(0L);
        job.setUpdatedCount(0L);
        job.setStatus(PENDING);
        backfillMapper.insert(job);
        return job;
    }

    private void markRunning(ConversationBackfill job) {
        job.setStatus(RUNNING);
        job.setErrorMessage(null);
        backfillMapper.updateById(job);
    }

    private ConversationBackfillResult resultOf(ConversationBackfill job, long failedCount) {
        long scanned = job.getScannedCount() == null ? 0 : job.getScannedCount();
        long updated = job.getUpdatedCount() == null ? 0 : job.getUpdatedCount();
        return new ConversationBackfillResult(scanned, updated, Math.max(0, scanned - updated), failedCount);
    }
}
