package com.pppp.zhimesh.chat.controller.externalapi.v1;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.CreateImageDto;
import com.pppp.zhimesh.common.dto.DrawDto;
import com.pppp.zhimesh.common.dto.extapi.ExtApiDrawReq;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.DrawService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.GenerateImage.INTERACTING_METHOD_GENERATE_IMAGE;

@Tag(name = "External API - Draw")
@RestController
@RequestMapping("/ext/v1/draw")
@Validated
public class ExtDrawController {

    @Resource
    private DrawService drawService;

    @Operation(summary = "Create a draw task | 创建绘图任务")
    @PostMapping("/generation")
    public Map<String, String> generation(@RequestBody @Validated ExtApiDrawReq req) {
        User user = ThreadContext.getCurrentUser();

        CreateImageDto dto = CreateImageDto.builder()
                .prompt(req.getPrompt())
                .negativePrompt(req.getNegativePrompt())
                .size(req.getSize())
                .quality(req.getQuality())
                .number(req.getNumber())
                .modelName(req.getModel())
                .seed(req.getSeed())
                .interactingMethod(INTERACTING_METHOD_GENERATE_IMAGE)
                .build();

        String uuid = drawService.generate(dto);
        return Map.of("uuid", uuid);
    }

    @Operation(summary = "Get draw task detail | 获取绘图任务详情")
    @GetMapping("/{uuid}")
    public DrawDto detail(@PathVariable String uuid) {
        User user = ThreadContext.getCurrentUser();
        DrawDto drawDto = drawService.getPublicOrMine(uuid);
        if (drawDto == null || !drawDto.getUserId().equals(user.getId())) {
            throw new BaseException(ErrorEnum.A_DRAW_NOT_FOUND);
        }
        return drawDto;
    }
}
