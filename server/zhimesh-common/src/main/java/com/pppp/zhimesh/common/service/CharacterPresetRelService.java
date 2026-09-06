package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.CharacterPresetRelDto;
import com.pppp.zhimesh.common.entity.CharacterPresetRel;
import com.pppp.zhimesh.common.mapper.CharacterPresetRelMapper;
import com.pppp.zhimesh.common.util.MPPageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class CharacterPresetRelService extends ServiceImpl<CharacterPresetRelMapper, CharacterPresetRel> {

    public List<CharacterPresetRelDto> listByUser(Long userId, Integer limit) {
        List<CharacterPresetRel> list = this.lambdaQuery()
                .eq(CharacterPresetRel::getUserId, userId)
                
                .last("limit " + limit)
                .list();
        return MPPageUtil.convertToList(list, CharacterPresetRelDto.class);
    }

    public boolean softDelBy(Long userId, Long characterId) {
        return this.remove(new LambdaQueryWrapper<CharacterPresetRel>()
                .eq(CharacterPresetRel::getUserId, userId)
                .eq(CharacterPresetRel::getUserCharacterId, characterId)
        );
    }
}
