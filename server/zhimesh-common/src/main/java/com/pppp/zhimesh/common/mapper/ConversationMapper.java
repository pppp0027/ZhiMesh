package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pppp.zhimesh.common.entity.Conversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Insert("""
            INSERT INTO adi_conversation
                (uuid, user_id, character_id, title, status, is_default)
            VALUES
                (#{uuid}, #{userId}, #{characterId}, #{title}, #{status}, true)
            ON CONFLICT (user_id, character_id)
                WHERE is_default = true
            DO NOTHING
            """)
    int insertDefaultIfAbsent(Conversation conversation);
}
