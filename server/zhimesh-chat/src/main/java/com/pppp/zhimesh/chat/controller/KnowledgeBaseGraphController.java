package com.pppp.zhimesh.chat.controller;

import com.pppp.zhimesh.common.service.KnowledgeBaseGraphService;
import com.pppp.zhimesh.common.service.KnowledgeBaseItemService;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.vo.GraphEdge;
import com.pppp.zhimesh.common.vo.GraphVertex;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;

@RestController
@RequestMapping("/knowledge-base-graph")
@Validated
public class KnowledgeBaseGraphController {
    @Resource
    private KnowledgeBaseGraphService knowledgeBaseGraphService;

    @Resource
    private KnowledgeBaseItemService knowledgeBaseItemService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/list/{kbItemUuid}")
    public Map<String, Object> list(@PathVariable String kbItemUuid, @RequestParam(defaultValue = Long.MAX_VALUE + "") Long maxVertexId, @RequestParam(defaultValue = Long.MAX_VALUE + "") Long maxEdgeId, @RequestParam(defaultValue = "-1") int limit) {
        checkUserWorkspaceItemReadPrivilege(kbItemUuid);
        List<GraphVertex> vertices = new ArrayList<>(knowledgeBaseGraphService.listVerticesByKbItemUuid(kbItemUuid, maxVertexId, limit));
        List<Triple<GraphVertex, GraphEdge, GraphVertex>> edgeWithVertices = knowledgeBaseGraphService.listEdgesByKbItemUuid(kbItemUuid, maxEdgeId, limit);
        Pair<List<GraphVertex>, List<GraphEdge>> pair = knowledgeBaseGraphService.getFromTriple(edgeWithVertices);
        vertices.addAll(pair.getLeft());
//Deduplicate
        //去重
        List<GraphVertex> filteredVertices = vertices
                .stream()
                .collect(
                        Collectors.toMap(
                                GraphVertex::getId, Function.identity(), (s, a) -> s
                        )
                )
                .values()
                .stream()
                .toList();
        return Map.of("vertices", filteredVertices, "edges", pair.getRight());
    }

    private void checkUserWorkspaceItemReadPrivilege(String kbItemUuid) {
        KnowledgeBaseItem item = knowledgeBaseItemService.getEnable(kbItemUuid);
        if (item == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        knowledgeBaseService.checkReadPrivilege(item.getKbUuid());
    }
}
