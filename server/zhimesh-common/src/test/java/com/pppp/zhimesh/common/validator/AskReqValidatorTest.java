package com.pppp.zhimesh.common.validator;

import com.pppp.zhimesh.common.dto.AskReq;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AskReqValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsCharacterOnlyRequest() {
        AskReq req = request();
        req.setCharacterUuid("12345678123442348123123456789012");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void acceptsConversationOnlyRequest() {
        AskReq req = request();
        req.setConversationUuid("12345678123442348123123456789012");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void rejectsMissingOrMalformedContextUuid() {
        AskReq missing = request();
        AskReq malformed = request();
        malformed.setConversationUuid("not-a-uuid");

        assertThat(validator.validate(missing)).hasSize(1);
        assertThat(validator.validate(malformed)).isNotEmpty();
    }

    private AskReq request() {
        AskReq req = new AskReq();
        req.setPrompt("hello");
        return req;
    }
}
