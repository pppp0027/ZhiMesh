package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.CharacterPresetAddReq;
import com.pppp.zhimesh.common.dto.CharacterPresetEditReq;
import com.pppp.zhimesh.common.dto.CharacterPresetUserResp;
import com.pppp.zhimesh.common.entity.CharacterPreset;
import com.pppp.zhimesh.common.entity.CharacterPresetRel;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.CharacterPresetMapper;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.util.MPPageUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.CharacterConstant.MAX_KNOWLEDGE_BASES;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_CHARACTER_KB_MAX_LIMIT;

@Slf4j
@Service
public class CharacterPresetService extends ServiceImpl<CharacterPresetMapper, CharacterPreset> {
    @jakarta.annotation.Resource
    private KnowledgeBaseService knowledgeBaseService;

    @jakarta.annotation.Resource
    private CharacterPresetRelService characterPresetRelService;
    public Page<CharacterPreset> search(String keyword, int currentPage, int pageSize) {
        return this.lambdaQuery()
                
                .like(!StringUtils.isBlank(keyword), CharacterPreset::getTitle, keyword)
                .orderByDesc(CharacterPreset::getUpdateTime)
                .page(new Page<>(currentPage, pageSize));
    }

    /**
     * Returns preset data that is safe for the regular user workspace.
     * System knowledge-base IDs are resolved only on the server at execution
     * time and must not be exposed to the browser.
     */
    public Page<CharacterPresetUserResp> searchForUser(String keyword, int currentPage, int pageSize) {
        Page<CharacterPreset> page = search(keyword, currentPage, pageSize);
        Page<CharacterPresetUserResp> result = new Page<>();
        return MPPageUtil.convertToPage(page, result, CharacterPresetUserResp.class, (source, target) -> {
            target.setSystemKnowledgeEnabled(StringUtils.isNotBlank(source.getSystemKbIds()));
            return target;
        });
    }

    public CharacterPreset addOne(CharacterPresetAddReq presetAddReq) {
        if (StringUtils.isAnyBlank(presetAddReq.getTitle(), presetAddReq.getRemark())) {
            throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
        }
        CharacterPreset newOne = new CharacterPreset();
        newOne.setUuid(UuidUtil.createShort());
        newOne.setTitle(presetAddReq.getTitle());
        newOne.setRemark(presetAddReq.getRemark());
        newOne.setAiSystemMessage(presetAddReq.getAiSystemMessage());
        newOne.setSystemKbIds(StringUtils.join(filterSystemKnowledgeBaseIds(presetAddReq.getSystemKbIds()), ","));
        newOne.setMcpIds(StringUtils.join(presetAddReq.getMcpIds(), ","));
        newOne.setType(presetAddReq.getType());
        newOne.setIsSystem(false);
        this.save(newOne);
        return newOne;
    }

    public boolean edit(String uuid, CharacterPresetEditReq editReq) {
        if (StringUtils.isAnyBlank(uuid, editReq.getTitle(), editReq.getRemark())) {
            throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
        }
        if (Boolean.FALSE.equals(ThreadContext.getCurrentUser().getIsAdmin())) {
            throw new BaseException(ErrorEnum.A_USER_NOT_AUTH);
        }
        return this.lambdaUpdate()
                .eq(CharacterPreset::getUuid, uuid)
                .set(CharacterPreset::getTitle, editReq.getTitle())
                .set(CharacterPreset::getRemark, editReq.getRemark())
                .set(CharacterPreset::getAiSystemMessage, editReq.getAiSystemMessage())
                .set(CharacterPreset::getSystemKbIds, StringUtils.join(filterSystemKnowledgeBaseIds(editReq.getSystemKbIds()), ","))
                .set(CharacterPreset::getMcpIds, StringUtils.join(editReq.getMcpIds(), ","))
                .set(CharacterPreset::getType, editReq.getType())
                .update();
    }

    /**
     * Permanently delete a preset and all user-to-preset relation rows.
     * A preset is a reusable template; deleting it must not delete user
     * characters that were already created from that template.
     */
    @Transactional
    public boolean delete(String uuid) {
        if (Boolean.FALSE.equals(ThreadContext.getCurrentUser().getIsAdmin())) {
            throw new BaseException(ErrorEnum.A_USER_NOT_AUTH);
        }
        CharacterPreset preset = this.lambdaQuery()
                .eq(CharacterPreset::getUuid, uuid)
                
                .oneOpt()
                .orElseThrow(() -> new BaseException(ErrorEnum.A_PRESET_CHARACTER_NOT_EXIST));
        // Existing deployments do not enforce a foreign key here, so remove
        // relations explicitly to prevent stale preset references.
        characterPresetRelService.lambdaUpdate()
                .eq(CharacterPresetRel::getPresetCharacterId, preset.getId())
                .remove();
        return this.removeById(preset.getId());
    }

    private List<Long> filterSystemKnowledgeBaseIds(List<Long> requestedIds) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(
                knowledgeBaseService.filterEnabledSystemIds(requestedIds).stream()
                        .filter(Objects::nonNull).collect(Collectors.toList()));
        if (ids.size() > MAX_KNOWLEDGE_BASES) {
            throw new BaseException(A_CHARACTER_KB_MAX_LIMIT, String.valueOf(MAX_KNOWLEDGE_BASES));
        }
        return List.copyOf(ids);
    }
}
