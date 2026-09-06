package com.pppp.zhimesh.chat.controller;

import com.pppp.zhimesh.common.dto.RefEmbeddingDto;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.dto.KeywordRefDto;
import com.pppp.zhimesh.common.service.CharacterMessageRefEmbeddingService;
import com.pppp.zhimesh.common.service.CharacterMessageRefGraphService;
import com.pppp.zhimesh.common.service.CharacterMessageRefMemoryEmbeddingService;
import com.pppp.zhimesh.common.service.CharacterMessageRefBm25Service;
import com.pppp.zhimesh.common.service.CharacterMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Message CRUD controller: handles message query, delete, and reference viewing only.
 */
@Tag(name = "Character Message")
@RestController
@RequestMapping("/character/message")
@Validated
public class CharacterMessageController {

    @Resource
    private CharacterMessageService characterMessageService;

    @Resource
    private CharacterMessageRefEmbeddingService characterMessageRefEmbeddingService;

    @Resource
    private CharacterMessageRefMemoryEmbeddingService characterMessageRefMemoryEmbeddingService;

    @Resource
    private CharacterMessageRefGraphService characterMessageRefGraphService;

    @Resource
    private CharacterMessageRefBm25Service characterMessageRefBm25Service;

    @Operation(summary = "根据音频uuid获取对应的文本 | Get Text by Audio UUID")
    @GetMapping("/text/{audioUuid}")
    public String getTextByAudioUuid(@PathVariable String audioUuid) {
        return characterMessageService.getTextByAudioUuid(audioUuid);
    }

    @GetMapping("/knowledge-embedding-ref/{uuid}")
    public List<RefEmbeddingDto> embeddingRef(@PathVariable String uuid) {
        characterMessageService.getOwnedOrThrow(uuid);
        return characterMessageRefEmbeddingService.listRefEmbeddings(uuid);
    }

    @GetMapping("/memory-embedding-ref/{msgUuid}")
    public List<RefEmbeddingDto> memoryEmbeddingRef(@PathVariable String msgUuid) {
        characterMessageService.getOwnedOrThrow(msgUuid);
        return characterMessageRefMemoryEmbeddingService.listRefEmbeddings(msgUuid);
    }

    @GetMapping("/graph-ref/{uuid}")
    public RefGraphDto graphRef(@PathVariable String uuid) {
        characterMessageService.getOwnedOrThrow(uuid);
        return characterMessageRefGraphService.getByMsgUuid(uuid);
    }

    @GetMapping("/keyword-ref/{uuid}")
    public KeywordRefDto keywordRef(@PathVariable String uuid) {
        characterMessageService.getOwnedOrThrow(uuid);
        return characterMessageRefBm25Service.getByMsgUuid(uuid);
    }

    @PostMapping("/del/{uuid}")
    public boolean softDelete(@PathVariable String uuid) {
        return characterMessageService.softDelete(uuid);
    }

}
