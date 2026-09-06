package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabeledEvidenceContentInjectorTest {

    private final LabeledEvidenceContentInjector injector = new LabeledEvidenceContentInjector();

    @Test
    void wrapsLabeledEvidenceAroundTheOriginalUserQuestion() {
        Content relation = Content.from(TextSegment.from("A——依赖——>B", new Metadata(Map.of(
                RetrievedCandidate.CONTENT_TYPE, RetrievedCandidate.GRAPH_RELATION))));
        Content chunk = Content.from(TextSegment.from("原始文档片段", new Metadata()));

        ChatMessage injected = injector.inject(
                List.of(chunk, relation), UserMessage.from("订单服务依赖什么？"));

        String text = ((UserMessage) injected).singleText();
        assertTrue(text.startsWith("请优先依据以下参考资料回答用户的问题"));
        assertTrue(text.contains("[1]【文档片段】\n原始文档片段"));
        assertTrue(text.contains("[2]【图谱关系·推断】\nA——依赖——>B"));
        assertTrue(text.endsWith("订单服务依赖什么？"));
    }

    @Test
    void keepsUserMessageWhenNoEvidenceWasRetrieved() {
        ChatMessage injected = injector.inject(List.of(), UserMessage.from("今天天气如何"));

        String text = ((UserMessage) injected).singleText();
        assertTrue(text.endsWith("今天天气如何"));
        assertTrue(text.startsWith("请优先依据以下参考资料"));
    }

    @Test
    void leavesNonUserMessagesUntouched() {
        SystemMessage systemMessage = SystemMessage.from("你是一个助手");

        assertSame(systemMessage, injector.inject(
                List.of(Content.from(TextSegment.from("evidence"))), systemMessage));
    }
}
