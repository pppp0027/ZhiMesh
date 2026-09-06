package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.conversation.ConversationDto;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.mapper.ConversationMapper;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryCleanupTask;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CHARACTER_NOT_EXIST;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CONVERSATION_NOT_FOUND;

@Slf4j
@Service
public class ConversationService extends ServiceImpl<ConversationMapper, Conversation> {

    private static final int STATUS_ACTIVE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_TITLE = "新对话";

    @Resource
    private CharacterMapper characterMapper;

    @Resource
    private ShortTermMemoryCleanupTask shortTermMemoryCleanupTask;

    @Resource
    private CharacterMessageService characterMessageService;

    @Transactional
    public Conversation create(Long userId, String characterUuid, String title) {
        Character character = findOwnedCharacter(userId, characterUuid);
        Conversation conversation = newConversation(userId, character.getId(), title, false);
        baseMapper.insert(conversation);
        return getOwnedOrThrow(userId, conversation.getUuid());
    }

    public Conversation getOwnedOrThrow(Long userId, String conversationUuid) {
        Conversation conversation = lambdaQuery()
                .eq(Conversation::getUuid, conversationUuid)
                .eq(Conversation::getUserId, userId)
                
                .one();
        if (conversation == null) {
            throw new BaseException(A_CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    public Page<ConversationDto> listByUser(Long userId, int currentPage, int pageSize) {
        int safePage = Math.max(1, currentPage);
        int safeSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        Page<Conversation> page = lambdaQuery()
                .eq(Conversation::getUserId, userId)
                
                .orderByDesc(Conversation::getUpdateTime)
                .orderByDesc(Conversation::getId)
                .page(new Page<>(safePage, safeSize));
        return MPPageUtil.convertToPage(page, ConversationDto.class);
    }

    @Transactional
    public boolean editTitle(Long userId, String conversationUuid, String title) {
        Conversation conversation = getOwnedOrThrow(userId, conversationUuid);
        return lambdaUpdate()
                .eq(Conversation::getId, conversation.getId())
                
                .set(Conversation::getTitle, normalizeTitle(title))
                .update();
    }

    @Transactional
    public boolean softDelete(Long userId, String conversationUuid) {
        Conversation conversation = getOwnedOrThrow(userId, conversationUuid);
        boolean deleted = removeById(conversation.getId());
        if (deleted) {
            characterMessageService.softDeleteByConversationIds(userId, List.of(conversation.getId()));
            cleanupConversationMemory(conversationUuid);
        }
        return deleted;
    }

    @Transactional
    public Conversation getOrCreateDefault(Long userId, String characterUuid) {
        return getOrCreateDefault(userId, findOwnedCharacter(userId, characterUuid));
    }

    @Transactional
    public Conversation getOrCreateDefault(Long userId, Character character) {
        if (character == null || !userId.equals(character.getUserId())) {
            throw new BaseException(A_CHARACTER_NOT_EXIST);
        }
        Conversation existing = findDefault(userId, character.getId());
        if (existing != null) {
            return existing;
        }

        Conversation candidate = newConversation(userId, character.getId(), character.getTitle(), true);
        baseMapper.insertDefaultIfAbsent(candidate);
        Conversation saved = findDefault(userId, character.getId());
        if (saved == null) {
            throw new BaseException(A_CONVERSATION_NOT_FOUND);
        }
        return saved;
    }

    public void touch(Long conversationId, LocalDateTime lastMessageTime) {
        lambdaUpdate()
                .eq(Conversation::getId, conversationId)
                
                .set(Conversation::getLastMessageTime, lastMessageTime == null ? LocalDateTime.now() : lastMessageTime)
                .update();
    }

    @Transactional
    public void softDeleteByCharacter(Long userId, Long characterId) {
        Character character = characterMapper.selectById(characterId);
        List<Conversation> conversations = lambdaQuery()
                .select(Conversation::getId, Conversation::getUuid)
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getCharacterId, characterId)
                
                .list();
        List<Long> conversationIds = conversations.stream().map(Conversation::getId).toList();
        List<String> conversationUuids = conversations.stream().map(Conversation::getUuid).toList();
        boolean deleted = this.remove(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getCharacterId, characterId)
        );
        if (deleted && character != null) {
            characterMessageService.softDeleteByConversationIds(userId, conversationIds);
            try {
                shortTermMemoryCleanupTask.deleteCharacter(character.getUuid(), conversationUuids);
            } catch (RuntimeException ex) {
                log.warn("Failed to clean short-term memory after character deletion, characterId:{}", characterId, ex);
            }
        }
    }

    private Character findOwnedCharacter(Long userId, String characterUuid) {
        Character character = characterMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Character>()
                .eq(Character::getUuid, characterUuid)
                .eq(Character::getUserId, userId)
                );
        if (character == null) {
            throw new BaseException(A_CHARACTER_NOT_EXIST);
        }
        return character;
    }

    private Conversation findDefault(Long userId, Long characterId) {
        return lambdaQuery()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getCharacterId, characterId)
                .eq(Conversation::getIsDefault, true)
                
                .one();
    }

    private Conversation newConversation(Long userId, Long characterId, String title, boolean isDefault) {
        Conversation conversation = new Conversation();
        conversation.setUuid(UuidUtil.createShort());
        conversation.setUserId(userId);
        conversation.setCharacterId(characterId);
        String normalizedTitle = normalizeTitle(title);
        conversation.setTitle(StringUtils.defaultIfBlank(normalizedTitle, DEFAULT_TITLE));
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setIsDefault(isDefault);
        return conversation;
    }

    private String normalizeTitle(String title) {
        return StringUtils.substring(StringUtils.defaultString(title).trim(), 0, 100);
    }

    private void cleanupConversationMemory(String conversationUuid) {
        try {
            shortTermMemoryCleanupTask.deleteConversation(conversationUuid);
        } catch (RuntimeException ex) {
            log.warn("Failed to clean short-term memory after conversation deletion, conversationUuid:{}",
                    conversationUuid, ex);
        }
    }
}
