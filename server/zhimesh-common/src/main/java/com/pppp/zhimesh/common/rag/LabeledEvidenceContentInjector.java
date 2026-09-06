package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;

import java.util.List;
import java.util.Map;

/**
 * Injects labeled retrieval evidence into the user message, replacing
 * langchain4j's default English template. The instruction tells the answer
 * model that entries labeled as graph relations are inferred hints, so
 * original document chunks win on conflict and hints are not quoted as facts.
 */
public class LabeledEvidenceContentInjector implements ContentInjector {

    static final PromptTemplate PROMPT_TEMPLATE = PromptTemplate.from("""
            请优先依据以下参考资料回答用户的问题。
            其中标注为“图谱关系”的条目是从知识图谱抽取的结构化提示，可能不完整或过于泛化；当其与“文档片段”原文冲突时，以原文为准。若参考资料不足以回答问题，请直接说明。
            {{information}}

            {{userMessage}}""");

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (!(chatMessage instanceof UserMessage userMessage) || !userMessage.hasSingleText()) {
            return chatMessage;
        }
        return UserMessage.from(PROMPT_TEMPLATE.apply(Map.of(
                "information", LabeledEvidenceFormatter.format(contents),
                "userMessage", userMessage.singleText())).text());
    }
}
