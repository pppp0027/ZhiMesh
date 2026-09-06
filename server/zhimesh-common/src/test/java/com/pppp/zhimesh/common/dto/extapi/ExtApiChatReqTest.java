package com.pppp.zhimesh.common.dto.extapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtApiChatReqTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsOptionalSnakeCaseConversationUuid() throws Exception {
        String uuid = "bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb";
        ExtApiChatReq request = objectMapper.readValue(
                "{\"query\":\"hello\",\"conversation_uuid\":\"" + uuid + "\"}", ExtApiChatReq.class);

        assertThat(request.getConversationUuid()).isEqualTo(uuid);
        assertThat(request.getResponseMode()).isEqualTo("streaming");
    }
}
