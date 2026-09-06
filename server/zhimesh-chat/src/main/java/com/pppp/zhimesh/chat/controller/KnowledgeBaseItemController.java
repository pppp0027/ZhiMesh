package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.KbItemDto;
import com.pppp.zhimesh.common.dto.KbItemEditReq;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunkSet;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.CanonicalChunkQueryService;
import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;

@RestController
@RequestMapping("/knowledge-base-item")
@Validated
public class KnowledgeBaseItemController {

    @Resource
    private KnowledgeBaseItemService knowledgeBaseItemService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private CanonicalChunkQueryService canonicalChunkQueryService;

    @PostMapping("/saveOrUpdate")
    public KnowledgeBaseItem saveOrUpdate(@RequestBody KbItemEditReq itemEditReq) {
        if (itemEditReq.getId() == null || itemEditReq.getId() < 1) {
            knowledgeBaseService.checkUserWorkspaceWritePrivilege(itemEditReq.getKbUuid());
        } else {
            KnowledgeBaseItem existing = knowledgeBaseItemService.getById(itemEditReq.getId());
            if (existing == null) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
            knowledgeBaseService.checkUserWorkspaceWritePrivilege(existing.getKbUuid());
        }
        return knowledgeBaseItemService.saveOrUpdate(itemEditReq);
    }

    @GetMapping("/search")
    public Page<KbItemDto> search(String kbUuid, String keyword, @NotNull @Min(1) Integer currentPage, @NotNull @Min(10) Integer pageSize) {
        knowledgeBaseService.checkReadPrivilege(kbUuid);
        return knowledgeBaseItemService.search(kbUuid, keyword, currentPage, pageSize);
    }

    @GetMapping("/info/{uuid}")
    public KnowledgeBaseItem info(@PathVariable String uuid) {
        KnowledgeBaseItem item = knowledgeBaseItemService.info(uuid);
        if (item == null) {
            return null;
        }
        knowledgeBaseService.checkReadPrivilege(item.getKbUuid());
        return item;
    }

    @GetMapping("/{itemUuid}/chunk-sets")
    public List<KnowledgeBaseChunkSet> chunkSets(@PathVariable String itemUuid) {
        KnowledgeBaseItem item = requireReadableItem(itemUuid);
        knowledgeBaseService.checkReadPrivilege(item.getKbUuid());
        return canonicalChunkQueryService.listChunkSets(itemUuid);
    }

    @GetMapping("/{itemUuid}/chunk-sets/{chunkSetUuid}/chunks")
    public List<KnowledgeBaseChunk> chunks(@PathVariable String itemUuid, @PathVariable String chunkSetUuid) {
        KnowledgeBaseItem item = requireReadableItem(itemUuid);
        knowledgeBaseService.checkReadPrivilege(item.getKbUuid());
        return canonicalChunkQueryService.listChunks(itemUuid, chunkSetUuid);
    }

    @PostMapping("/del/{uuid}")
    public boolean softDelete(@PathVariable String uuid) {
        KnowledgeBaseItem item = knowledgeBaseItemService.getEnable(uuid);
        if (item == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        knowledgeBaseService.checkUserWorkspaceWritePrivilege(item.getKbUuid());
        return knowledgeBaseItemService.softDelete(uuid);
    }

    private KnowledgeBaseItem requireReadableItem(String itemUuid) {
        KnowledgeBaseItem item = knowledgeBaseItemService.getEnable(itemUuid);
        if (item == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        return item;
    }
}
