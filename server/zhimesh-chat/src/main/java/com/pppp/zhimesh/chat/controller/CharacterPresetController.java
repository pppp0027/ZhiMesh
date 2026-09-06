package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.CharacterPresetSearchReq;
import com.pppp.zhimesh.common.dto.CharacterPresetUserResp;
import com.pppp.zhimesh.common.service.CharacterPresetService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/character-preset")
@RestController
public class CharacterPresetController {

    @Resource
    private CharacterPresetService characterPresetService;

    @Operation(summary = "搜索预设会话(角色) | Search Preset Characters (Characters)")
    @PostMapping("/search")
    public Page<CharacterPresetUserResp> page(@RequestBody CharacterPresetSearchReq searchReq, @NotNull @Min(1) Integer currentPage, @NotNull @Min(10) Integer pageSize) {
        return characterPresetService.searchForUser(searchReq.getTitle(), currentPage, pageSize);
    }
}
