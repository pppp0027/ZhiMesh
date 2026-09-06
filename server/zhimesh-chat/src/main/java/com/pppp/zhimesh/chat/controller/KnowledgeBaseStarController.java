package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbStarInfoResp;
import com.pppp.zhimesh.common.service.KnowledgeBaseStarService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "知识库点赞controller | Knowledge Base Star Controller")
@Validated
@RequestMapping("/knowledge-base/star")
@RestController
public class KnowledgeBaseStarController {

    @Resource
    private KnowledgeBaseStarService knowledgeBaseStarService;

    @GetMapping("/mine")
    public Page<KbStarInfoResp> stars(int currentPage, int pageSize) {
        return knowledgeBaseStarService.listStarInfo(ThreadContext.getCurrentUser(), currentPage, pageSize);
    }
}
