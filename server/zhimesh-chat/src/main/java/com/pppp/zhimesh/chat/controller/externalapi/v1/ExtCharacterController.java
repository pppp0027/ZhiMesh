package com.pppp.zhimesh.chat.controller.externalapi.v1;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.AskReq;
import com.pppp.zhimesh.common.dto.extapi.ExtApiChatReq;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.service.AiModelService;
import com.pppp.zhimesh.common.service.CharacterChatService;
import com.pppp.zhimesh.common.service.CharacterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_MODEL_NOT_AVAILABLE;

@Tag(name = "External API - Character")
@RestController
@RequestMapping("/ext/v1/character")
@Validated
public class ExtCharacterController {

    @Resource
    private CharacterChatService characterChatService;

    @Resource
    private CharacterService characterService;

    @Resource
    private AiModelService aiModelService;

    @Operation(summary = "Chat with a character")
    @PostMapping
    public Object chatMessages(@RequestBody @Validated ExtApiChatReq req) {
        String characterUuid = ThreadContext.getExtApiEntityUuid();

        Character character = characterService.lambdaQuery()
                .eq(Character::getUuid, characterUuid)
                
                .one();
        if (null == character) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }

        AskReq askReq = new AskReq();
        askReq.setCharacterUuid(characterUuid);
        askReq.setConversationUuid(req.getConversationUuid());
        askReq.setPrompt(req.getQuery());

        if (StringUtils.isNotBlank(req.getModel())) {
            AiModel aiModel = aiModelService.getByName(req.getModel());
            if (null == aiModel || !aiModel.getIsEnable()) {
                throw new BaseException(A_MODEL_NOT_AVAILABLE);
            }
            askReq.setModelName(req.getModel());
        }

        if ("blocking".equalsIgnoreCase(req.getResponseMode())) {
            return characterChatService.blockingAsk(askReq);
        }

        return characterChatService.sseAsk(askReq);
    }
}
