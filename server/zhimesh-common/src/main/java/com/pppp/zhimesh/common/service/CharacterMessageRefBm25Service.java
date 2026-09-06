package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.KeywordHitDto;
import com.pppp.zhimesh.common.dto.KeywordRefDto;
import com.pppp.zhimesh.common.entity.CharacterMessageRefBm25;
import com.pppp.zhimesh.common.mapper.CharacterMessageRefBm25Mapper;
import com.pppp.zhimesh.common.util.JsonUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class CharacterMessageRefBm25Service
        extends ServiceImpl<CharacterMessageRefBm25Mapper, CharacterMessageRefBm25> {

    public KeywordRefDto getByMsgUuid(String msgUuid) {
        List<CharacterMessageRefBm25> refs = getBaseMapper().listByMsgUuid(msgUuid);
        if (CollectionUtils.isEmpty(refs)) {
            return KeywordRefDto.builder().terms(Collections.emptyList()).hits(Collections.emptyList()).build();
        }
        List<String> terms = JsonUtil.toList(refs.get(0).getQueryTerms(), String.class);
        List<KeywordHitDto> hits = refs.stream().map(ref -> KeywordHitDto.builder()
                .chunkUuid(ref.getChunkUuid())
                .kbUuid(ref.getKbUuid())
                .kbItemUuid(ref.getKbItemUuid())
                .text(ref.getContentSnapshot())
                .score(ref.getScore())
                .rank(ref.getHitRank())
                .build()).toList();
        return KeywordRefDto.builder()
                .terms(terms == null ? Collections.emptyList() : terms)
                .hits(hits)
                .build();
    }
}
