package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.conversation.ConversationCreateReq;
import com.pppp.zhimesh.common.dto.conversation.ConversationDto;
import com.pppp.zhimesh.common.dto.conversation.ConversationEditReq;
import com.pppp.zhimesh.common.dto.CharacterMsgListResp;
import com.pppp.zhimesh.common.entity.Conversation;
import com.pppp.zhimesh.common.service.ConversationService;
import com.pppp.zhimesh.common.service.CharacterService;
import com.pppp.zhimesh.common.util.MPPageUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Conversation")
@RestController
@RequestMapping("/conversation")
@Validated
public class ConversationController {

    @Resource
    private ConversationService conversationService;

    @Resource
    private CharacterService characterService;

    @Operation(summary = "创建会话 | Create Conversation")
    @PostMapping("/add")
    public ConversationDto add(@RequestBody @Valid ConversationCreateReq req) {
        Conversation saved = conversationService.create(
                ThreadContext.getCurrentUserId(), req.characterUuid(), req.title());
        return MPPageUtil.convertTo(saved, ConversationDto.class);
    }

    @Operation(summary = "分页查询当前用户会话 | List Conversations")
    @GetMapping("/list")
    public Page<ConversationDto> list(
            @RequestParam(defaultValue = "1") @Min(1) int currentPage,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return conversationService.listByUser(ThreadContext.getCurrentUserId(), currentPage, pageSize);
    }

    @Operation(summary = "获取或创建角色默认会话 | Get or Create Default Conversation")
    @GetMapping("/default")
    public ConversationDto defaultConversation(@RequestParam String characterUuid) {
        Conversation conversation = conversationService.getOrCreateDefault(
                ThreadContext.getCurrentUserId(), characterUuid);
        return MPPageUtil.convertTo(conversation, ConversationDto.class);
    }

    @Operation(summary = "查询会话 | Get Conversation")
    @GetMapping("/{uuid}")
    public ConversationDto detail(@PathVariable String uuid) {
        Conversation conversation = conversationService.getOwnedOrThrow(ThreadContext.getCurrentUserId(), uuid);
        return MPPageUtil.convertTo(conversation, ConversationDto.class);
    }

    @Operation(summary = "分页查询会话消息 | List Conversation Messages")
    @GetMapping("/{uuid}/messages")
    public CharacterMsgListResp messages(
            @PathVariable String uuid,
            @RequestParam(defaultValue = "") String maxMsgUuid,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return characterService.detailByConversation(
                ThreadContext.getCurrentUserId(), uuid, maxMsgUuid, pageSize);
    }

    @Operation(summary = "修改会话标题 | Edit Conversation Title")
    @PostMapping("/edit/{uuid}")
    public boolean edit(@PathVariable String uuid, @RequestBody @Valid ConversationEditReq req) {
        return conversationService.editTitle(ThreadContext.getCurrentUserId(), uuid, req.title());
    }

    @Operation(summary = "删除会话 | Delete Conversation")
    @PostMapping("/del/{uuid}")
    public boolean delete(@PathVariable String uuid) {
        return conversationService.softDelete(ThreadContext.getCurrentUserId(), uuid);
    }
}
