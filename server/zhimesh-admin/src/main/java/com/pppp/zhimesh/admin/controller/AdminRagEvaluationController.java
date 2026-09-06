package com.pppp.zhimesh.admin.controller;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationAskReq;
import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationAskResp;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "RAG internal evaluation")
@RestController
@RequestMapping("/admin/rag-evaluation")
@Validated
public class AdminRagEvaluationController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "Run one isolated RAG evaluation question")
    @PostMapping("/ask/{kbUuid}")
    public RagEvaluationAskResp ask(@PathVariable String kbUuid,
                                    @Valid @RequestBody RagEvaluationAskReq request) {
        return knowledgeBaseService.evaluateAsk(ThreadContext.getExistCurrentUser(), kbUuid, request);
    }
}
