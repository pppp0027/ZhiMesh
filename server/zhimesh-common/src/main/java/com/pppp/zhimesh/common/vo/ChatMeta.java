package com.pppp.zhimesh.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatMeta {
    private PromptMeta question;
    private AnswerMeta answer;
    private AudioInfo audioInfo;
    private String conversationUuid;
}
