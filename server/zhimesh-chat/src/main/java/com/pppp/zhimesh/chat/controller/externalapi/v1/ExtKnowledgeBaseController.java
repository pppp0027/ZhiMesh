package com.pppp.zhimesh.chat.controller.externalapi.v1;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbQaDto;
import com.pppp.zhimesh.common.dto.QARecordReq;
import com.pppp.zhimesh.common.dto.extapi.ExtApiKbQaReq;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.service.KnowledgeBaseQaService;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_MODEL_NOT_AVAILABLE;

@Tag(name = "External API - Knowledge Base")
@RestController
@RequestMapping("/ext/v1/knowledge")
@Validated
public class ExtKnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeBaseQaService knowledgeBaseQaService;

    @Resource
    private AiModelService aiModelService;

    @Operation(summary = "Knowledge base Q&A")
    @PostMapping
    public Object knowledgeBaseQa(@RequestBody @Validated ExtApiKbQaReq req) {
        User user = ThreadContext.getCurrentUser();
        String kbUuid = ThreadContext.getExtApiEntityUuid();

        KnowledgeBase knowledgeBase = knowledgeBaseService.getOrThrow(kbUuid);

        QARecordReq qaRecordReq = new QARecordReq();
        qaRecordReq.setQuestion(req.getQuery());

        if (StringUtils.isNotBlank(req.getModel())) {
            AiModel aiModel = aiModelService.getByName(req.getModel());
            if (null == aiModel || !aiModel.getIsEnable()) {
                throw new BaseException(A_MODEL_NOT_AVAILABLE);
            }
            qaRecordReq.setModelName(req.getModel());
        }

        KbQaDto qaDto = knowledgeBaseQaService.add(knowledgeBase, qaRecordReq);

        if ("blocking".equalsIgnoreCase(req.getResponseMode())) {
            return knowledgeBaseService.blockingAsk(user, knowledgeBase, qaDto);
        }

        return knowledgeBaseService.sseAsk(qaDto.getUuid());
    }
}
