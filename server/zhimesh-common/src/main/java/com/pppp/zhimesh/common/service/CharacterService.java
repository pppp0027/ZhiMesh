package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.*;
import com.pppp.zhimesh.common.dto.mcp.UserMcpUpdateReq;
import com.pppp.zhimesh.common.entity.*;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.exception.BaseException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.mapper.CharacterMapper;
import com.pppp.zhimesh.common.mapper.CharacterMessageToolCallMapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.LocalCache;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.UuidUtil;

import java.util.Objects;
import com.pppp.zhimesh.common.vo.AudioConfig;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import com.pppp.zhimesh.common.vo.TtsSetting;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.enums.ErrorEnum.*;
import static com.pppp.zhimesh.common.util.LocalCache.MODEL_ID_TO_OBJ;

@Slf4j
@Service
public class CharacterService extends ServiceImpl<CharacterMapper, Character> {

    @Lazy
    @Resource
    private CharacterService self;

    @Resource
    private SysConfigService sysConfigService;

    @Resource
    private CharacterMessageService characterMessageService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ConversationBackfillService conversationBackfillService;

    @Resource
    private CharacterPresetService characterPresetService;

    @Resource
    private CharacterPresetRelService characterPresetRelService;

    @Resource
    private UserMcpService userMcpService;

    @Resource
    private McpService mcpService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeBaseAccessService knowledgeBaseAccessService;

    @Resource
    private FileService fileService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private LLMCallRecordService llmCallRecordService;

    @Resource
    private CharacterMessageToolCallMapper characterMessageToolCallMapper;

    public Page<CharacterDto> search(CharacterSearchReq characterSearchReq, int currentPage, int pageSize) {
        Page<Character> page = this.lambdaQuery()
                
                .like(!StringUtils.isBlank(characterSearchReq.getTitle()), Character::getTitle, characterSearchReq.getTitle())
                .orderByDesc(Character::getId)
                .page(new Page<>(currentPage, pageSize));
        return MPPageUtil.convertToPage(page, CharacterDto.class);
    }

    public List<CharacterDto> listByUser() {
        User user = ThreadContext.getCurrentUser();
        List<Character> list = this.lambdaQuery()
                .eq(Character::getUserId, user.getId())
                
                .orderByDesc(Character::getId)
                .last("limit " + sysConfigService.getCharacterMaxNum())
                .list();
        return MPPageUtil.convertToList(list, CharacterDto.class, (source, target) -> {
            setMcpToDto(source, target);
            setKbInfoToDto(source, target);
            return target;
        });
    }

    /**
     * 查询对话{@code uuid}的消息列表
     *
     * @param uuid       对话的uuid
     * @param maxMsgUuid 最大uuid（转换成id进行判断）
     * @param pageSize   每页数量
     * @return 列表
     */
    public CharacterMsgListResp detail(String uuid, String maxMsgUuid, int pageSize) {
        Long userId = ThreadContext.getCurrentUserId();
        Character character = this.lambdaQuery()
                .eq(Character::getUuid, uuid)
                .eq(Character::getUserId, userId)
                .one();
        if (null == character) {
            log.error("character not exist, uuid: {}", uuid);
            throw new BaseException(A_CHARACTER_NOT_EXIST);
        }

        long maxId = Long.MAX_VALUE;
        if (StringUtils.isNotBlank(maxMsgUuid)) {
            CharacterMessage maxMsg = characterMessageService.lambdaQuery()
                    .select(CharacterMessage::getId)
                    .eq(CharacterMessage::getUuid, maxMsgUuid)
                    .eq(CharacterMessage::getCharacterId, character.getId())
                    .eq(CharacterMessage::getUserId, userId)
                    .one();
            if (null == maxMsg) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
            maxId = maxMsg.getId();
        }

        List<CharacterMessage> questions = characterMessageService.listQuestionsByCharacterId(character.getId(), maxId, pageSize);
        return assembleMessageList(questions);
    }

    public CharacterMsgListResp detailByConversation(Long userId, String conversationUuid,
                                                      String maxMsgUuid, int pageSize) {
        Conversation conversation = conversationService.getOwnedOrThrow(userId, conversationUuid);
        if (Boolean.TRUE.equals(conversation.getIsDefault())) {
            // Legacy messages predate independent conversations and have
            // conversation_id = 0. Backfill them lazily before the first read
            // so enabling the conversation feature never makes history vanish.
            conversationBackfillService.backfillCharacter(userId, conversation.getCharacterId());
        }
        long maxId = Long.MAX_VALUE;
        if (StringUtils.isNotBlank(maxMsgUuid)) {
            CharacterMessage maxMsg = characterMessageService.lambdaQuery()
                    .select(CharacterMessage::getId)
                    .eq(CharacterMessage::getUuid, maxMsgUuid)
                    .eq(CharacterMessage::getConversationId, conversation.getId())
                    .eq(CharacterMessage::getUserId, userId)
                    
                    .one();
            if (maxMsg == null) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
            maxId = maxMsg.getId();
        }
        List<CharacterMessage> questions = characterMessageService
                .listQuestionsByConversationId(conversation.getId(), maxId, pageSize);
        return assembleMessageList(questions);
    }

    private CharacterMsgListResp assembleMessageList(List<CharacterMessage> questions) {
        if (questions.isEmpty()) {
            return new CharacterMsgListResp(StringUtils.EMPTY, Collections.emptyList());
        }
        String minUuid = questions.stream().reduce(questions.get(0), (a, b) -> {
            if (a.getId() < b.getId()) {
                return a;
            }
            return b;
        }).getUuid();
        //Wrap question content
        List<CharacterMsgDto> userMessages = MPPageUtil.convertToList(questions, CharacterMsgDto.class, (source, target) -> {
            if (StringUtils.isNotBlank(source.getAttachments())) {
                List<String> urls = fileService.getUrls(Arrays.stream(source.getAttachments().split(",")).toList());
                target.setAttachmentUrls(urls);
            } else {
                target.setAttachmentUrls(Collections.emptyList());
            }
            if (StringUtils.isNotBlank(source.getAudioUuid())) {
                target.setAudioUrl(fileService.getUrl(source.getAudioUuid()));
            } else {
                target.setAudioUrl("");
            }
            return target;
        });
        CharacterMsgListResp result = new CharacterMsgListResp(minUuid, userMessages);

        //Wrap answer content
        List<Long> parentIds = questions.stream().map(CharacterMessage::getId).toList();
        List<CharacterMessage> childMessages = characterMessageService
                .lambdaQuery()
                .in(CharacterMessage::getParentMessageId, parentIds)
                
                .list();
        Map<Long, List<CharacterMessage>> idToMessages = childMessages.stream().collect(Collectors.groupingBy(CharacterMessage::getParentMessageId));

        //Batch query LLM call records for token and duration
        List<Long> childIds = childMessages.stream().map(CharacterMessage::getId).toList();
        List<LLMCallRecord> callRecords = llmCallRecordService.listBySource(
                LLMCallRecordSourceType.CHARACTER_CHAT.getValue(), childIds);
        Map<Long, LLMCallRecord> idToCallRecord = callRecords.stream()
                .collect(Collectors.toMap(LLMCallRecord::getSourceId, r -> r, (a, b) -> a));

        //Batch query agentic tool-call traces (single IN query grouped by message id, no N+1),
        //ordered by seq within each message for frontend step replay
        Map<Long, List<ToolCallTrace>> idToToolCalls = listToolCallsByMessageIds(childIds);

        //Fill AI answer to the request of user
        result.getMsgList().forEach(item -> {
            List<CharacterMsgDto> children = MPPageUtil.convertToList(idToMessages.get(item.getId()), CharacterMsgDto.class);
            if (children.size() > 1) {
                children = children.stream().sorted(Comparator.comparing(CharacterMsgDto::getCreateTime).reversed()).toList();
            }

            for (CharacterMsgDto characterMsgDto : children) {
                AiModel aiModel = MODEL_ID_TO_OBJ.get(characterMsgDto.getAiModelId());
                characterMsgDto.setAiModelPlatform(null == aiModel ? "" : aiModel.getPlatform());
                if (StringUtils.isNotBlank(characterMsgDto.getAudioUuid())) {
                    characterMsgDto.setAudioUrl(fileService.getUrl(characterMsgDto.getAudioUuid()));
                } else {
                    characterMsgDto.setAudioUrl("");
                }
                //Fill token and duration from LLM call record
                LLMCallRecord callRecord = idToCallRecord.get(characterMsgDto.getId());
                if (callRecord != null) {
                    characterMsgDto.setInputTokens(callRecord.getInputTokens());
                    characterMsgDto.setOutputTokens(callRecord.getOutputTokens());
                    characterMsgDto.setDuration(callRecord.getDuration());
                }
                //Fill agentic tool-call traces; non-agentic messages keep toolCalls null
                List<ToolCallTrace> toolCalls = idToToolCalls.get(characterMsgDto.getId());
                if (CollectionUtils.isNotEmpty(toolCalls)) {
                    characterMsgDto.setToolCalls(toolCalls);
                }
            }
            item.setChildren(children);
        });
        return result;
    }

    /**
     * 批量查询消息的工具调用轨迹并按 message_id 分组：一次 IN 查询（避免 N+1），
     * 每组内按 seq 升序排序（Java 端显式排序，不依赖数据库返回顺序）
     * <p>
     * Batch-load tool-call traces for the given messages grouped by message_id:
     * one IN query (no N+1), each group explicitly sorted by seq ascending in
     * Java instead of relying on database return order.
     */
    private Map<Long, List<ToolCallTrace>> listToolCallsByMessageIds(List<Long> messageIds) {
        if (CollectionUtils.isEmpty(messageIds)) {
            return Collections.emptyMap();
        }
        List<CharacterMessageToolCall> records = characterMessageToolCallMapper.selectList(
                new LambdaQueryWrapper<CharacterMessageToolCall>()
                        .in(CharacterMessageToolCall::getMessageId, messageIds));
        Map<Long, List<ToolCallTrace>> grouped = new HashMap<>();
        for (CharacterMessageToolCall record : records) {
            grouped.computeIfAbsent(record.getMessageId(), key -> new ArrayList<>())
                    .add(toToolCallTrace(record));
        }
        grouped.values().forEach(traces -> traces.sort(Comparator.comparingInt(ToolCallTrace::getSeq)));
        return grouped;
    }

    /** 实体行 → 历史回放用的轨迹 DTO / Entity row → trace DTO for history replay */
    private static ToolCallTrace toToolCallTrace(CharacterMessageToolCall record) {
        return ToolCallTrace.builder()
                .toolName(record.getToolName())
                .args(record.getArgs())
                .resultSummary(record.getResultSummary())
                .durationMs(null == record.getDurationMs() ? 0L : record.getDurationMs())
                .success(Boolean.TRUE.equals(record.getSuccess()))
                .seq(null == record.getSeq() ? 0 : record.getSeq())
                .build();
    }

    public int createDefault(Long userId) {
        return createDefault(userId, null);
    }

    public int createDefault(Long userId, String locale) {
        assertCanCreateCharacter(userId);
        Character character = new Character();
        character.setUuid(UuidUtil.createShort());
        character.setUserId(userId);
        String defaultLocale = StringUtils.isNotBlank(locale) ? locale
                : Objects.toString(SysConfigService.getByKey(ZhiMeshConstant.SysConfigKey.DEFAULT_LOCALE), "zh-CN");
        character.setTitle(defaultLocale.startsWith("zh")
                ? ZhiMeshConstant.CharacterConstant.DEFAULT_NAME
                : ZhiMeshConstant.CharacterConstant.DEFAULT_NAME_EN);
        return baseMapper.insert(character);
    }

    public Character createByFirstMessage(Long userId, String uuid, String title) {
        assertCanCreateCharacter(userId);
        Character character = new Character();
        character.setUuid(uuid);
        character.setUserId(userId);
        character.setTitle(StringUtils.substring(title, 0, 45));
        baseMapper.insert(character);

        return this.lambdaQuery().eq(Character::getUuid, uuid).oneOpt().orElse(null);
    }

    public CharacterDto add(CharacterAddReq characterAddReq) {
        Long userId = ThreadContext.getCurrentUserId();
        assertCanCreateCharacter(userId);
        Character character = this.lambdaQuery()
                .eq(Character::getUserId, userId)
                .eq(Character::getTitle, characterAddReq.getTitle())
                
                .one();
        if (null != character) {
            throw new BaseException(A_CHARACTER_TITLE_EXIST);
        }

        List<Long> filteredMcpIds = filterEnableMcpIds(characterAddReq.getMcpIds());
        List<Long> filteredKbIds = filterEnableKbIds(ThreadContext.getCurrentUser(), characterAddReq.getKbIds());

        String uuid = UuidUtil.createShort();
        Character one = new Character();
        BeanUtils.copyProperties(characterAddReq, one);
        // Agentic 为产品默认（迁移 042）：未显式传入时按开启创建，DB 列默认值已同步翻转
        // Agentic is the product default (migration 042): create as enabled when
        // absent; the column default has been flipped to match
        if (null == characterAddReq.getIsAgentic()) {
            one.setIsAgentic(true);
        }
        one.setUuid(uuid);
        one.setUserId(userId);
        one.setMcpIds(StringUtils.join(filteredMcpIds, ","));
        one.setKbIds(StringUtils.join(filteredKbIds, ","));
        if (null != characterAddReq.getAudioConfig()) {
            one.setAudioConfig(characterAddReq.getAudioConfig());
        }
        baseMapper.insert(one);

        Character saved = this.lambdaQuery().eq(Character::getUuid, uuid).one();
        CharacterDto dto = MPPageUtil.convertTo(saved, CharacterDto.class);
        setMcpToDto(saved, dto);
        setKbInfoToDto(saved, dto);
        return dto;
    }

    /**
     * Enforce the per-user Agent limit when a Character is created. This must not
     * be checked on the chat path: users who have reached the limit can still use
     * the Characters they already own.
     */
    private void assertCanCreateCharacter(Long userId) {
        long characterCount = this.lambdaQuery()
                .eq(Character::getUserId, userId)
                
                .count();
        int characterMax = sysConfigService.getCharacterMaxNum();
        if (characterCount >= characterMax) {
            throw new BaseException(A_CHARACTER_MAX_LIMIT, String.valueOf(characterMax));
        }
    }

    /**
     * 组装MCP信息
     *
     * @param character 对话信息
     * @param dto          对话DTO
     */
    private void setMcpToDto(Character character, CharacterDto dto) {
        if (StringUtils.isNotBlank(character.getMcpIds())) {
            dto.setMcpIds(Arrays.stream(character.getMcpIds().split(","))
                    .map(Long::parseLong)
                    .toList());
        } else {
            dto.setMcpIds(new ArrayList<>());
        }
    }

    /**
     * 组装已关联的知识库信息
     *
     * @param conv 对话信息
     * @param dto  对话DTO
     */
    private void setKbInfoToDto(Character character, CharacterDto dto) {
        //组装已关联的知识库信息
        List<Long> kids = new ArrayList<>();
        List<CharacterKnowledge> characterKnowledgeList = new ArrayList<>();
        Set<Long> systemIds = getCurrentSystemKbIds(character);
        LinkedHashSet<Long> effectiveIds = mergeKnowledgeBaseIds(systemIds, parseIds(character.getKbIds()));
        dto.setSystemKnowledgeEnabled(!systemIds.isEmpty());
        dto.setSystemKnowledgeCount(Math.min(systemIds.size(), ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES));
        dto.setKnowledgeBaseLimit(ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES);
        if (!effectiveIds.isEmpty()) {
            List<Long> kbIds = new ArrayList<>(effectiveIds);
            knowledgeBaseService.listByIds(kbIds).forEach(kb -> {
                CharacterKnowledge characterKnowledge = convertToCharacterKbDto(ThreadContext.getCurrentUser(), kb);
                boolean systemKb = systemIds.contains(kb.getId())
                        && Boolean.TRUE.equals(kb.getIsSystem())
                        && !Boolean.FALSE.equals(kb.getIsEnabled());
                // System KBs are resolved again on the server from the preset
                // binding. Do not expose their metadata or identifiers to the
                // user-facing character payload.
                if (systemKb) {
                    return;
                }
                characterKnowledge.setIsSystem(systemKb);
                characterKnowledge.setIsReadOnly(systemKb);
                // Hide metadata when the unified tier rules no longer grant a
                // read: membership may have been revoked after binding.
                if (!knowledgeBaseAccessService.canRead(ThreadContext.getCurrentUser(), kb)) {
                    characterKnowledge.setKbInfo(null);
                    characterKnowledge.setIsEnable(false);
                }
                characterKnowledgeList.add(characterKnowledge);
                kids.add(kb.getId());
            });
        }
        dto.setKbIds(kids);
        dto.setCharacterKnowledgeList(characterKnowledgeList);
    }

    /**
     * 根据预设会话创建当前用户会话
     *
     * @param presetConvUuid 预设会话uuid
     */
    @Transactional
    public CharacterDto addByPresetCharacter(String presetConvUuid) {
        CharacterPreset presetCharacter = this.characterPresetService.lambdaQuery()
                .eq(CharacterPreset::getUuid, presetConvUuid)
                
                .oneOpt()
                .orElseThrow(() -> new BaseException(A_PRESET_CHARACTER_NOT_EXIST));
        CharacterPresetRel presetRel = this.characterPresetRelService.lambdaQuery()
                .eq(CharacterPresetRel::getUserId, ThreadContext.getCurrentUserId())
                .eq(CharacterPresetRel::getPresetCharacterId, presetCharacter.getId())
                
                .oneOpt()
                .orElse(null);
        if (null != presetRel) {
            Character character = this.getById(presetRel.getUserCharacterId());
            CharacterDto characterDto = MPPageUtil.convertTo(character, CharacterDto.class);
            setMcpToDto(character, characterDto);
            setKbInfoToDto(character, characterDto);
            return characterDto;
        }

        // System KB ids are authorized through the preset relation after the
        // user character is created; no knowledge base is created by name.
        List<Long> kbIds = Collections.emptyList();
        List<Long> presetMcpIds = parseIds(presetCharacter.getMcpIds());
        ensureParameterlessPresetMcps(presetMcpIds);

        CharacterAddReq characterAddReq = CharacterAddReq.builder()
                .title(presetCharacter.getTitle())
                .remark(presetCharacter.getRemark())
                .aiSystemMessage(presetCharacter.getAiSystemMessage())
                .kbIds(kbIds)
                .mcpIds(presetMcpIds)
                .build();
        CharacterDto characterDto = self.add(characterAddReq);
        characterPresetRelService.save(
                CharacterPresetRel.builder()
                        .presetCharacterId(presetCharacter.getId())
                        .userCharacterId(characterDto.getId())
                        .userId(ThreadContext.getCurrentUserId())
                        .build()
        );
        Character saved = this.getById(characterDto.getId());
        characterDto = MPPageUtil.convertTo(saved, CharacterDto.class);
        setMcpToDto(saved, characterDto);
        setKbInfoToDto(saved, characterDto);
        return characterDto;
    }

    /**
     * Enable preset MCPs that have no user-configurable parameters on first use.
     * Credential-bearing MCPs remain an explicit user opt-in.
     */
    private void ensureParameterlessPresetMcps(List<Long> mcpIds) {
        if (CollectionUtils.isEmpty(mcpIds)) {
            return;
        }
        Set<Long> enabled = userMcpService.searchEnableByUserId(ThreadContext.getCurrentUserId()).stream()
                .map(UserMcp::getMcpId)
                .collect(Collectors.toSet());
        for (Long mcpId : mcpIds) {
            if (enabled.contains(mcpId)) {
                continue;
            }
            Mcp mcp = mcpService.getOrThrow(mcpId, false);
            if (!CollectionUtils.isEmpty(mcp.getCustomizedParamDefinitions())) {
                continue;
            }
            UserMcpUpdateReq updateReq = new UserMcpUpdateReq();
            updateReq.setMcpId(mcpId);
            updateReq.setMcpCustomizedParams(Collections.emptyList());
            updateReq.setIsEnable(true);
            userMcpService.saveOrUpdate(updateReq);
        }
    }

    public boolean edit(String uuid, CharacterEditReq characterEditReq) {
        return edit(getOrThrow(uuid), characterEditReq);
    }

    /**
     * User-portal variant. An administrator using the user portal must remain
     * scoped to their own Characters; cross-user maintenance stays in the
     * dedicated management endpoint above.
     */
    public boolean editOwnedByCurrentUser(String uuid, CharacterEditReq characterEditReq) {
        return edit(getOwnedByCurrentUserOrThrow(uuid), characterEditReq);
    }

    private boolean edit(Character character, CharacterEditReq characterEditReq) {
        Character one = new Character();
        BeanUtils.copyProperties(characterEditReq, one);
        one.setId(character.getId());
        if (null != characterEditReq.getUnderstandContextEnable()) {
            one.setUnderstandContextEnable(characterEditReq.getUnderstandContextEnable());
        }
        if (null != characterEditReq.getMcpIds()) {
            List<Long> filteredMcpIds = filterEnableMcpIds(characterEditReq.getMcpIds());
            one.setMcpIds(StringUtils.join(filteredMcpIds, ","));
        }
        if (null != characterEditReq.getKbIds()) {
            List<Long> filteredKbIds = filterEnableKbIds(ThreadContext.getCurrentUser(), characterEditReq.getKbIds());
            assertEffectiveKnowledgeBaseLimit(filteredKbIds, getCurrentSystemKbIds(character));
            // System knowledge bases are resolved from the preset relation at
            // runtime. Persisting them here makes a user-owned Character retain
            // system IDs after it is edited and breaks the isolation boundary.
            one.setKbIds(StringUtils.join(filteredKbIds, ","));
        }
        if (null != characterEditReq.getAudioConfig()) {
            one.setAudioConfig(characterEditReq.getAudioConfig());
        }
        return baseMapper.updateById(one) > 0;
    }

    @Transactional
    public boolean softDel(String uuid) {
        return softDel(getOrThrow(uuid));
    }

    @Transactional
    public boolean softDelOwnedByCurrentUser(String uuid) {
        return softDel(getOwnedByCurrentUserOrThrow(uuid));
    }

    private boolean softDel(Character character) {
        characterPresetRelService.softDelBy(character.getUserId(), character.getId());
        conversationService.softDeleteByCharacter(character.getUserId(), character.getId());
        return this.removeById(character.getId());
    }

    public int countTodayCreated() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), 0, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(1);
        return baseMapper.countCreatedByTimePeriod(beginTime, endTime);
    }

    public int countAllCreated() {
        return baseMapper.countAllCreated();
    }

    private Character getOrThrow(String uuid) {
        Character character = this.lambdaQuery()
                .eq(Character::getUuid, uuid)
                
                .one();
        if (null == character) {
            throw new BaseException(A_CHARACTER_NOT_EXIST);
        }
        if (!character.getUserId().equals(ThreadContext.getCurrentUserId()) && !ThreadContext.getCurrentUser().getIsAdmin()) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
        return character;
    }

    private Character getOwnedByCurrentUserOrThrow(String uuid) {
        Character character = this.lambdaQuery()
                .eq(Character::getUuid, uuid)
                .eq(Character::getUserId, ThreadContext.getCurrentUserId())
                .one();
        if (character == null) {
            throw new BaseException(A_CHARACTER_NOT_EXIST);
        }
        return character;
    }

    /**
     * 过滤出有效的MCP服务id列表 | Filter the list of valid MCP service IDs
     *
     * @param mcpIdsInReq 请求中传入的MCP服务id列表 | List of MCP service IDs passed in the request
     * @return 有效的MCP服务id列表 | List of valid MCP service IDs
     */
    private List<Long> filterEnableMcpIds(List<Long> mcpIdsInReq) {
        List<Long> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(mcpIdsInReq)) {
            return result;
        }
        List<UserMcp> userMcpList = userMcpService.searchEnableByUserId(ThreadContext.getCurrentUserId());

        for (Long mcpIdInReq : mcpIdsInReq) {
            if (userMcpList.stream().anyMatch(item -> item.getMcpId().equals(mcpIdInReq))) {
                result.add(mcpIdInReq);
            } else {
                log.warn("User mcp id {} not found or disabled in user mcp list, userId: {}, mcpId:{}", mcpIdInReq, ThreadContext.getCurrentUserId(), mcpIdInReq);
            }
        }
        return result;
    }

    public List<Long> filterEnableKbIds(User user, List<Long> kbIdsInReq) {
        if (CollectionUtils.isEmpty(kbIdsInReq)) {
            return Collections.emptyList();
        }
        List<KbInfoResp> validKbList = filterEnableKb(user, kbIdsInReq);
        LinkedHashSet<Long> validIds = validKbList.stream()
                .map(KbInfoResp::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (validIds.size() > ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES) {
            throw new BaseException(A_CHARACTER_KB_MAX_LIMIT,
                    String.valueOf(ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES));
        }
        return List.copyOf(validIds);
    }

    /**
     * 过滤出有效的知识库id列表 | Find the list of valid knowledge base IDs
     * 按 KnowledgeBaseAccessService 的三级归属规则判定可读性：本人的个人库、已加入团队的团队库、
     * 以及按 company_scope 对当前用户可见的企业库均有效。
     *
     * @param user 当前用户 | Current user
     * @param ids  知识库id列表 | List of knowledge base IDs
     * @return 有效的知识库列表 | List of valid knowledge base
     */
    public List<KbInfoResp> filterEnableKb(User user, List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return knowledgeBaseService.listByIds(ids).stream()
                .filter(CharacterService::isKnowledgeBaseEnabled)
                .filter(item -> !Boolean.TRUE.equals(item.getIsSystem()))
                .filter(item -> knowledgeBaseAccessService.canRead(user, item))
                .toList();
    }

    /** Resolve the role's current system-KB binding without trusting user input. */
    private Set<Long> getCurrentSystemKbIds(Character character) {
        if (character == null || character.getId() == null) {
            return Collections.emptySet();
        }
        CharacterPresetRel rel = characterPresetRelService.lambdaQuery()
                .eq(CharacterPresetRel::getUserId, character.getUserId())
                .eq(CharacterPresetRel::getUserCharacterId, character.getId())
                
                .oneOpt().orElse(null);
        if (rel == null) {
            return Collections.emptySet();
        }
        CharacterPreset preset = characterPresetService.getById(rel.getPresetCharacterId());
        if (preset == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(knowledgeBaseService.filterEnabledSystemIds(parseIds(preset.getSystemKbIds())));
    }

    /** KBs usable by a role include its current, enabled system bindings. */
    public List<KbInfoResp> filterEnableKb(User user, Character character) {
        LinkedHashSet<Long> effectiveIds = new LinkedHashSet<>(parseIds(character == null ? null : character.getKbIds()));
        effectiveIds.addAll(getCurrentSystemKbIds(character));
        return filterEnableKb(user, character, new ArrayList<>(effectiveIds));
    }

    public List<KbInfoResp> filterEnableKb(User user, Character character, List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        Set<Long> systemIds = getCurrentSystemKbIds(character);
        return knowledgeBaseService.listByIds(ids).stream()
                .filter(CharacterService::isKnowledgeBaseEnabled)
                .filter(item -> (systemIds.contains(item.getId()) && Boolean.TRUE.equals(item.getIsSystem()))
                        || (!Boolean.TRUE.equals(item.getIsSystem())
                        && knowledgeBaseAccessService.canRead(user, item)))
                .sorted(Comparator.comparing((KbInfoResp item) -> !systemIds.contains(item.getId())))
                .limit(ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES)
                .toList();
    }

    static boolean isKnowledgeBaseEnabled(KbInfoResp knowledgeBase) {
        return knowledgeBase != null && !Boolean.FALSE.equals(knowledgeBase.getIsEnabled());
    }

    private List<Long> parseIds(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(item -> item.matches("\\d+"))
                .map(Long::parseLong)
                .toList();
    }

    private LinkedHashSet<Long> mergeKnowledgeBaseIds(Collection<Long> systemIds,
                                                        Collection<Long> userIds) {
        LinkedHashSet<Long> effective = new LinkedHashSet<>();
        if (systemIds != null) systemIds.stream().filter(Objects::nonNull).forEach(effective::add);
        if (userIds != null) userIds.stream().filter(Objects::nonNull).forEach(effective::add);
        return effective.stream()
                .limit(ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void assertEffectiveKnowledgeBaseLimit(Collection<Long> userIds, Set<Long> systemIds) {
        long systemCount = systemIds == null ? 0L : systemIds.stream().filter(Objects::nonNull).distinct().count();
        long userCount = userIds == null ? 0L : userIds.stream().filter(Objects::nonNull)
                .filter(id -> systemIds == null || !systemIds.contains(id)).distinct().count();
        if (systemCount + userCount > ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES) {
            throw new BaseException(A_CHARACTER_KB_MAX_LIMIT,
                    String.valueOf(ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES));
        }
    }

    private CharacterKnowledge convertToCharacterKbDto(User user, KbInfoResp kbInfo) {
        CharacterKnowledge result = new CharacterKnowledge();
        BeanUtils.copyProperties(kbInfo, result);
        result.setKbInfo(kbInfo);
        result.setIsMine(user.getUuid().equals(kbInfo.getOwnerUuid()));
        return result;
    }
}
