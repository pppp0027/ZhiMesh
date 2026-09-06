package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;

@RestController
@RequestMapping("/knowledge-base-embedding")
@Validated
public class KnowledgeBaseEmbeddingController {

    @Resource
    private IKnowledgeEmbeddingService iKnowledgeEmbeddingService;

    @Resource
    private KnowledgeBaseItemService knowledgeBaseItemService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/list/{kbItemUuid}")
    public Page<KbItemEmbeddingDto> list(@PathVariable String kbItemUuid, int currentPage, int pageSize) {
        checkUserWorkspaceItemReadPrivilege(kbItemUuid);
        return iKnowledgeEmbeddingService.listByItemUuid(kbItemUuid, currentPage, pageSize);
    }

    private void checkUserWorkspaceItemReadPrivilege(String kbItemUuid) {
        KnowledgeBaseItem item = knowledgeBaseItemService.getEnable(kbItemUuid);
        if (item == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        knowledgeBaseService.checkReadPrivilege(item.getKbUuid());
    }
}
