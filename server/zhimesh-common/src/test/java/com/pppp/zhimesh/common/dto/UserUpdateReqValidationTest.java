package com.pppp.zhimesh.common.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserUpdateReqValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsARepeatableDisplayNameAndTrimsIt() {
        UserUpdateReq request = new UserUpdateReq();
        request.setName("  同名用户  ");

        assertEquals("同名用户", request.getName());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsBlankDisplayName() {
        UserUpdateReq request = new UserUpdateReq();
        request.setName("   ");

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsDisplayNameLongerThanDatabaseColumn() {
        UserUpdateReq request = new UserUpdateReq();
        request.setName("x".repeat(46));

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void localeOnlyUpdateRemainsValid() {
        UserUpdateReq request = new UserUpdateReq();
        request.setLocale("zh-CN");

        assertTrue(validator.validate(request).isEmpty());
    }
}
