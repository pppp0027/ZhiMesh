package com.pppp.zhimesh.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.KbEditReq;
import com.pppp.zhimesh.common.dto.KbItemEmbeddingDto;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.dto.KbItemDto;
import com.pppp.zhimesh.common.dto.KbSearchReq;
import com.pppp.zhimesh.common.dto.KbUploadResult;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.KnowledgeBaseGraphService;
import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import com.pppp.zhimesh.common.vo.GraphEdge;
import com.pppp.zhimesh.common.vo.GraphVertex;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;

/**
 * Knowledge Base
 */
@Tag(name = "知识库管理 | Knowledge Base Management", description = "知识库管理 | Knowledge Base Management")
@RestController
@RequestMapping("/admin/kb")
@Validated
public class AdminKbController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeBaseItemService knowledgeBaseItemService;

    @Resource
    private IKnowledgeEmbeddingService knowledgeEmbeddingService;

    @Resource
    private KnowledgeBaseGraphService knowledgeBaseGraphService;

    @Operation(summary = "搜索知识库 | Search Knowledge Bases")
    @PostMapping("/search")
    public Page<KbInfoResp> search(@RequestBody KbSearchReq kbSearchReq, @NotNull @Min(1) Integer currentPage, @NotNull @Min(10) Integer pageSize) {
        return knowledgeBaseService.search(kbSearchReq, currentPage, pageSize);
    }

    @Operation(summary = "删除知识库 | Delete Knowledge Base")
    @PostMapping("/del/{uuid}")
    public boolean delete(@PathVariable String uuid) {
        return knowledgeBaseService.softDelete(uuid);
    }

    @Operation(summary = "编辑知识库 | Edit Knowledge Base")
    @PostMapping("/edit")
    public boolean edit(@RequestBody KbEditReq kbEditReq) {
        knowledgeBaseService.saveOrUpdate(kbEditReq);
        return true;
    }

    /** Administration-only document workbench endpoints for system KBs. */
    @PostMapping(path = "/uploadDocs/{uuid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KbUploadResult> uploadDocs(@PathVariable String uuid,
                                            @RequestParam(value = "indexAfterUpload", defaultValue = "true") Boolean indexAfterUpload,
                                            @RequestParam(defaultValue = "") String indexTypes,
                                            @RequestParam("files") MultipartFile[] docs) {
        return knowledgeBaseService.uploadDocs(uuid, indexAfterUpload, docs, normalizeIndexTypes(indexAfterUpload, indexTypes));
    }

    @GetMapping("/items/search")
    public Page<KbItemDto> searchItems(@RequestParam String kbUuid,
                                        @RequestParam(defaultValue = "") String keyword,
                                        @NotNull @Min(1) Integer currentPage,
                                        @NotNull @Min(10) Integer pageSize) {
        return knowledgeBaseItemService.search(kbUuid, keyword, currentPage, pageSize);
    }

    /**
     * Management-only vector inspection. System knowledge bases deliberately do
     * not pass the user-workspace read check used by the public chat endpoints.
     */
    @GetMapping("/items/embeddings/{kbItemUuid}")
    public Page<KbItemEmbeddingDto> listItemEmbeddings(@PathVariable String kbItemUuid,
                                                        @RequestParam(defaultValue = "1") @Min(1) Integer currentPage,
                                                        @RequestParam(defaultValue = "10") @Min(1) Integer pageSize) {
        requireItem(kbItemUuid);
        return knowledgeEmbeddingService.listByItemUuid(kbItemUuid, currentPage, pageSize);
    }

    /** Management-only graph inspection for both system and user knowledge bases. */
    @GetMapping("/items/graph/{kbItemUuid}")
    public Map<String, Object> listItemGraph(@PathVariable String kbItemUuid,
                                             @RequestParam(defaultValue = "9223372036854775807") Long maxVertexId,
                                             @RequestParam(defaultValue = "9223372036854775807") Long maxEdgeId,
                                             @RequestParam(defaultValue = "-1") int limit) {
        requireItem(kbItemUuid);
        List<GraphVertex> vertices = new ArrayList<>(
                knowledgeBaseGraphService.listVerticesByKbItemUuid(kbItemUuid, maxVertexId, limit));
        List<Triple<GraphVertex, GraphEdge, GraphVertex>> edgeWithVertices =
                knowledgeBaseGraphService.listEdgesByKbItemUuid(kbItemUuid, maxEdgeId, limit);
        Pair<List<GraphVertex>, List<GraphEdge>> pair = knowledgeBaseGraphService.getFromTriple(edgeWithVertices);
        vertices.addAll(pair.getLeft());
        List<GraphVertex> distinctVertices = vertices.stream()
                .collect(Collectors.toMap(GraphVertex::getId, Function.identity(), (left, right) -> left))
                .values()
                .stream()
                .toList();
        return Map.of("vertices", distinctVertices, "edges", pair.getRight());
    }

    @PostMapping(value = "/items/indexing-list", params = {"uuids", "indexTypes"})
    public boolean indexItems(@RequestParam String[] uuids,
                              @RequestParam(defaultValue = "embedding") String[] indexTypes) {
        return knowledgeBaseService.indexItems(List.of(uuids), List.of(indexTypes));
    }

    @GetMapping("/indexing/check")
    public boolean checkIndexing(@RequestParam String kbUuid) {
        return knowledgeBaseService.checkIndexIsFinish(kbUuid);
    }

    private void requireItem(String kbItemUuid) {
        KnowledgeBaseItem item = knowledgeBaseItemService.getEnable(kbItemUuid);
        if (item == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
    }

    private List<String> normalizeIndexTypes(Boolean indexAfterUpload, String indexTypes) {
        List<String> result = Arrays.stream(indexTypes.split(","))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .toList();
        if (Boolean.TRUE.equals(indexAfterUpload) && result.isEmpty()) {
            return List.of("embedding");
        }
        return result;
    }
}
