package com.pppp.zhimesh.common.validator;

import com.pppp.zhimesh.common.annotation.CreateImageReqCheck;
import com.pppp.zhimesh.common.dto.CreateImageDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.GenerateImage.*;

public class CreateImageReqValidator implements
        ConstraintValidator<CreateImageReqCheck, CreateImageDto> {
    @Override
    public boolean isValid(CreateImageDto createImageDto, ConstraintValidatorContext constraintValidatorContext) {
        if (createImageDto.getInteractingMethod() == INTERACTING_METHOD_GENERATE_IMAGE && StringUtils.isBlank(createImageDto.getPrompt())) {
            throw new IllegalArgumentException("Prompt can not be empty");
        }
        return true;
    }
}
