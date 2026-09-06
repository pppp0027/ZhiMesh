package com.pppp.zhimesh.common.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterEditReqValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void partialToggleDoesNotRequireSystemMessage() {
        CharacterEditReq request = new CharacterEditReq();
        request.setUnderstandContextEnable(false);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void explicitlySubmittedSystemMessageCannotBeBlank() {
        CharacterEditReq request = new CharacterEditReq();
        request.setAiSystemMessage("   ");

        assertFalse(validator.validate(request).isEmpty());
    }
}
