package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.dto.DrawCommentDto;
import com.pppp.zhimesh.common.entity.Draw;
import com.pppp.zhimesh.common.entity.DrawComment;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.DrawCommentMapper;
import com.pppp.zhimesh.common.util.PrivilegeUtil;
import com.pppp.zhimesh.common.util.RedisTemplateUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;

import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.DRAW_COMMENT_LIMIT_KEY;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_OPT_TOO_FREQUENTLY;

@Slf4j
@Service
public class DrawCommentService extends ServiceImpl<DrawCommentMapper, DrawComment> {

    @Resource
    private RedisTemplateUtil redisTemplateUtil;

    /**
     * 同一用户，5秒内只能提交一次评论
     *
     * @param user   用户
     * @param draw   绘图
     * @param remark 评论内容
     * @return 评论
     */
    public DrawCommentDto add(User user, Draw draw, String remark) {

        String redisKey = MessageFormat.format(DRAW_COMMENT_LIMIT_KEY, user.getId());
        if (!redisTemplateUtil.lock(redisKey, "", 5)) {
            throw new BaseException(A_OPT_TOO_FREQUENTLY);
        }
        // 不主动解锁，依赖 Redis TTL 自然过期实现 5 秒限流
        String uuid = UuidUtil.createShort();
        DrawComment newObj = new DrawComment();
        newObj.setUuid(uuid);
        newObj.setUserId(user.getId());
        newObj.setDrawId(draw.getId());
        newObj.setRemark(remark);
        baseMapper.insert(newObj);

        return DrawCommentDto.builder()
                .uuid(uuid)
                .drawUuid(draw.getUuid())
                .userUuid(user.getUuid())
                .userName(user.getName())
                .remark(remark)
                .createTime(newObj.getCreateTime())
                .build();
    }

    public Page<DrawCommentDto> listByPage(long drawId, int currentPage, int pageSize) {
        return baseMapper.listByPage(new Page<>(currentPage, pageSize), drawId);
    }

    public boolean softDel(Long id) {
        DrawComment drawComment = PrivilegeUtil.checkAndGetById(id, this.query(), A_DATA_NOT_FOUND);
        return this.removeById(drawComment.getId());
    }

}
