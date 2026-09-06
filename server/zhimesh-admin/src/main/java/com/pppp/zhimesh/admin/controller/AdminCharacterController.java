package com.pppp.zhimesh.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.CharacterDto;
import com.pppp.zhimesh.common.dto.CharacterEditReq;
import com.pppp.zhimesh.common.dto.CharacterSearchReq;
import com.pppp.zhimesh.common.dto.UserEditReq;
import com.pppp.zhimesh.common.service.CharacterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
* Character management
 * 聊天会话管理
 */
@Tag(name = "会话管理 | Character Management", description = "会话管理 | Character Management")
@RestController
@RequestMapping("/admin/character")
@Validated
public class AdminCharacterController {

    @Resource
    private CharacterService characterService;

    @Operation(summary = "搜索会话 | Search Characters")
    @PostMapping("/search")
    public Page<CharacterDto> search(@RequestBody CharacterSearchReq searchReq, @NotNull @Min(1) Integer currentPage, @NotNull @Min(10) Integer pageSize) {
        return characterService.search(searchReq, currentPage, pageSize);
    }

    @Operation(summary = "编辑会话 | Edit Character")
    @PostMapping("/edit/{uuid}")
    public void edit(@PathVariable String uuid, @Validated @RequestBody CharacterEditReq editReq) {
        characterService.edit(uuid, editReq);
    }

    @Operation(summary = "删除会话 | Delete Character")
    @PostMapping("/del/{uuid}")
    public boolean softDel(@PathVariable String uuid) {
        return characterService.softDel(uuid);
    }
}
