package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pppp.zhimesh.common.entity.CharacterMessageRefBm25;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CharacterMessageRefBm25Mapper extends BaseMapper<CharacterMessageRefBm25> {
    List<CharacterMessageRefBm25> listByMsgUuid(@Param("uuid") String uuid);
}
