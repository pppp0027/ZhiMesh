package com.pppp.zhimesh.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.AiModelDto;
import com.pppp.zhimesh.common.dto.AiModelSearchReq;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.interfaces.AiModelAddGroup;
import com.pppp.zhimesh.common.interfaces.AiModelEditGroup;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.util.AiModelUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;

@Tag(name = "AI模型管理 | AI Model Management", description = "AI模型管理 | AI Model Management")
@RestController
@RequestMapping("/admin/model")
@Validated
public class AdminModelController {

    @Resource
    private AiModelService aiModelService;

    @Operation(summary = "搜索模型 | Search Models")
    @PostMapping("/search")
    public Page<AiModelDto> page(@RequestBody AiModelSearchReq aiModelSearchReq, @NotNull @Min(1) Integer currentPage, @NotNull @Min(10) Integer pageSize) {
        return aiModelService.search(aiModelSearchReq, currentPage, pageSize);
    }

    @Operation(summary = "添加模型 | Add Model")
    @PostMapping("/addOne")
    public AiModelDto addOne(@Validated(AiModelAddGroup.class) @RequestBody AiModelDto aiModelDto) {
        check(aiModelDto.getType());
        return aiModelService.addOne(aiModelDto);
    }

    @Operation(summary = "编辑模型 | Edit Model")
    @PostMapping("/edit")
    public void edit(@Validated(AiModelEditGroup.class) @RequestBody AiModelDto aiModelDto) {
        check(aiModelDto.getType());
        aiModelService.edit(aiModelDto);
    }

    @Operation(summary = "删除模型 | Delete Model")
    @PostMapping("/del/{id}")
    public void delete(@PathVariable Long id) {
        aiModelService.softDelete(id);
    }

    private void check(String type) {
        if (StringUtils.isNotBlank(type) && !AiModelUtil.checkModelType(type)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
    }
}
