package com.pppp.zhimesh.common.validator;

import com.pppp.zhimesh.common.annotation.AskReqCheck;
import com.pppp.zhimesh.common.dto.AskReq;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

public class AskReqValidator implements
        ConstraintValidator<AskReqCheck, AskReq> {

    @Override
    public void initialize(AskReqCheck constraintAnnotation) {
        //无需校验
    }

    @Override
    public boolean isValid(AskReq value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        if (StringUtils.isAllBlank(value.getPrompt(), value.getRegenerateQuestionUuid(), value.getAudioUuid())) {
            return violation(context, "prompt, regenerateQuestionUuid and audioUuid cannot all be empty");
        }

        String uuidRegex = "^[0-9a-fA-F]{8}[0-9a-fA-F]{4}4[0-9a-fA-F]{3}[89abAB][0-9a-fA-F]{3}[0-9a-fA-F]{12}$";
        if (StringUtils.isAllBlank(value.getCharacterUuid(), value.getConversationUuid())) {
            return violation(context, "characterUuid and conversationUuid cannot both be empty");
        }
        if (StringUtils.isNotBlank(value.getCharacterUuid())
                && !Pattern.matches(uuidRegex, value.getCharacterUuid())) {
            return violation(context, "characterUuid format is invalid");
        }
        if (StringUtils.isNotBlank(value.getConversationUuid())
                && !Pattern.matches(uuidRegex, value.getConversationUuid())) {
            return violation(context, "conversationUuid format is invalid");
        }
        //check regenerate msg uuid
        if (StringUtils.isNotBlank(value.getRegenerateQuestionUuid())) {
            boolean isValid2 = Pattern.matches(uuidRegex, value.getRegenerateQuestionUuid());
            if (!isValid2) {
                return violation(context, "regenerateQuestionUuid format is invalid");
            }
        }
        return true;
    }

    private boolean violation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
