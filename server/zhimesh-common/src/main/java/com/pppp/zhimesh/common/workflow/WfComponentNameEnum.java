package com.pppp.zhimesh.common.workflow;

import lombok.Getter;

import java.util.Arrays;

@Getter
public enum WfComponentNameEnum {
    START("Start"),

    END("End"),

    LLM_ANSWER("Answer"),

    DOCUMENT_EXTRACTOR("DocumentExtractor"),

    KEYWORD_EXTRACTOR("KeywordExtractor"),

    FAQ_EXTRACTOR("FaqExtractor"),

    KNOWLEDGE_RETRIEVER("KnowledgeRetrieval"),

    SWITCHER("Switcher"),

    CLASSIFIER("Classifier"),

    TEMPLATE("Template"),

    TEXT_TRANSFORM("TextTransform"),

    VARIABLE_AGGREGATOR("VariableAggregator"),

    GOOGLE_SEARCH("Google"),

    HUMAN_FEEDBACK("HumanFeedback"),

    MAIL_SEND("MailSend"),

    HTTP_REQUEST("HttpRequest");

    private final String name;

    WfComponentNameEnum(String name) {
        this.name = name;
    }

    public static WfComponentNameEnum getByName(String name) {
        return Arrays.stream(WfComponentNameEnum.values()).filter(item -> item.name.equals(name)).findFirst().orElse(null);
    }
}
