package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 搜索知识库（管理员）
     *
     * @param keyword 关键词
     * @return
     */
    Page<KnowledgeBase> searchByAdmin(Page<KnowledgeBase> page, @Param("keyword") String keyword);

    /**
     * 搜索知识库（用户）：includeVisible 时混合可见范围（我的个人库 + 我所在
     * 团队 + 企业库 STAFF 或管理员全量），联表带出团队名供前端归属展示。
     *
     * @param ownerId 用户id
     * @param keyword 关键词
     * @param isAdmin 调用者是否管理员（决定 EXECUTIVE 企业库是否可见）
     * @return 知识库列表（KbInfoResp，TEAM 行含 teamName）
     */
    Page<KbInfoResp> searchByUser(Page<KbInfoResp> page, @Param("ownerId") long ownerId, @Param("keyword") String keyword, @Param("includeVisible") Boolean includeVisible, @Param("isAdmin") boolean isAdmin);

    /**
     * 搜索当前用户所在团队的团队知识库（联表带出团队名与我的角色）。
     *
     * @param userId  用户id
     * @param keyword 标题关键词
     * @return 团队知识库列表（KbInfoResp，含 teamUuid/teamName/myRole）
     */
    Page<KbInfoResp> searchTeamKbByUser(Page<KbInfoResp> page, @Param("userId") long userId, @Param("keyword") String keyword);

    /**
     * 根据知识点获取知识库信息
     *
     * @param itemUuid 知识点uuid
     * @return
     */
    KnowledgeBase getByItemUuid(@Param("itemUuid") String itemUuid);

    /**
     * 更新统计数据
     *
     * @param uuid
     */
    void updateStatByUuid(@Param("uuid") String uuid, @Param("embeddingCount") int embeddingCount);

    Integer countCreatedByTimePeriod(@Param("beginTime") LocalDateTime beginTime, @Param("endTime") LocalDateTime endTime);

    Integer countAllCreated();

    int markRouteProfileStale(@Param("kbUuid") String kbUuid);

    int markRouteProfileBuilding(@Param("kbUuid") String kbUuid, @Param("generation") long generation);

    int markRouteProfileFailed(@Param("kbUuid") String kbUuid, @Param("generation") long generation);

    int activateRouteProfile(@Param("kbUuid") String kbUuid,
                             @Param("generation") long generation,
                             @Param("profileSetUuid") String profileSetUuid,
                             @Param("sourceHash") String sourceHash,
                             @Param("modelId") long modelId,
                             @Param("modelIdentity") String modelIdentity);
}
