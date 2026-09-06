package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryKeyResolver;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.vo.ChatContext;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CHARACTER_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CONVERSATION_CHARACTER_MISMATCH;

@Service
public class ChatContextResolver {

    @Resource
    private CharacterMapper characterMapper;
    @Resource
    private ConversationService conversationService;
    @Resource
    private ShortTermMemoryKeyResolver memoryKeyResolver;
    public ChatContext resolve(User user, AskReq request) {
        Conversation conversation = null;
        Character character;

        if (StringUtils.isNotBlank(request.getConversationUuid())) {
            conversation = conversationService.getOwnedOrThrow(user.getId(), request.getConversationUuid());
            character = findOwnedCharacter(user.getId(), conversation.getCharacterId());
            if (StringUtils.isNotBlank(request.getCharacterUuid())
                    && !request.getCharacterUuid().equals(character.getUuid())) {
                throw new BaseException(A_CONVERSATION_CHARACTER_MISMATCH);
            }
        } else {
            character = characterMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Character>()
                    .eq(Character::getUuid, request.getCharacterUuid())
                    .eq(Character::getUserId, user.getId())
                    );
            if (character == null) {
                throw new BaseException(A_CHARACTER_NOT_FOUND);
            }
            // A stateful chat always has a concrete Conversation boundary. Legacy
            // characterUuid-only requests are attached to the one default conversation
            // owned by this user under this Character.
            conversation = conversationService.getOrCreateDefault(user.getId(), character);
        }

        String memoryId = memoryKeyResolver.forConversation(conversation.getUuid());

        return new ChatContext(user, character, conversation, conversation.getUuid(), memoryId);
    }

    private Character findOwnedCharacter(Long userId, Long characterId) {
        Character character = characterMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Character>()
                .eq(Character::getId, characterId)
                .eq(Character::getUserId, userId)
                );
        if (character == null) {
            throw new BaseException(A_CHARACTER_NOT_FOUND);
        }
        return character;
    }
}
