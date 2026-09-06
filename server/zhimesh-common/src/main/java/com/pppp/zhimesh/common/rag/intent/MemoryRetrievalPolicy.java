package com.pppp.zhimesh.common.rag.intent;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * High-recall request-level router for long-term memory. Definite trivial turns
 * are skipped, explicit recall signals select the narrowest memory channel, and
 * all other informative questions use a bounded AUTO probe whose candidates
 * must still pass strict post-retrieval relevance checks.
 */
@Component
public class MemoryRetrievalPolicy {

    private static final Pattern TRIVIAL_TURN = Pattern.compile(
            "^(你好|您好|嗨|哈喽|hello|hi|谢谢|感谢|thanks|thank you|再见|拜拜|好的|好|收到|明白了|晚安)"
                    + "[!！,.，。?？~～\\s]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_RECALL = Pattern.compile(
            "(你还记得|还记得我|记得我|回忆一下|回想一下|关于我的记忆|what do you remember|do you remember|recall)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODIC_RECALL = Pattern.compile(
            "(我之前|之前我|上次|上回|那次|当时|曾经|以前|前几天|昨天|我们之前|之前聊过|"
                    + "之前说过|之前做过|再发我|再来一次|继续之前|历史上|"
                    + "earlier|previously|last time|before|we discussed)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEMANTIC_RECALL = Pattern.compile(
            "(我喜欢(?:吃|喝|看|玩|听|做)?什么|我爱(?:吃|喝|看|玩|听|做)?什么|我的偏好|我的喜好|我的爱好|"
                    + "我是谁|我叫什么|我的名字|我的职业|我的工作|我的习惯|我的性格|"
                    + "我的目标|你了解我|关于我|适合我的|按照我的偏好|老规矩|平时的口味|一贯的习惯|"
                    + "my preference|what do i like|my name|about me|what do you know about me)",
            Pattern.CASE_INSENSITIVE);

    public MemoryRetrievalMode decide(String rawQuery, IntentDecision intentDecision) {
        String query = normalize(rawQuery);
        if (StringUtils.isBlank(query) || isTrivialTurn(query)) {
            return MemoryRetrievalMode.NONE;
        }
        if (EXPLICIT_RECALL.matcher(query).find()) {
            return MemoryRetrievalMode.BOTH;
        }
        boolean semantic = SEMANTIC_RECALL.matcher(query).find();
        boolean episodic = EPISODIC_RECALL.matcher(query).find();
        if (semantic && episodic) return MemoryRetrievalMode.BOTH;
        if (semantic) return MemoryRetrievalMode.SEMANTIC;
        if (episodic) return MemoryRetrievalMode.EPISODIC;
        // Unknown is not equivalent to "memory is unnecessary". AUTO performs
        // a bounded high-recall probe; strict post-retrieval relevance decides
        // whether any memory can enter the prompt.
        return MemoryRetrievalMode.AUTO;
    }

    /** Avoids an extraction-model call for turns that cannot add durable memory. */
    public boolean shouldExtract(String rawUserMessage) {
        String query = normalize(rawUserMessage);
        return StringUtils.isNotBlank(query) && !isTrivialTurn(query);
    }

    public boolean isTrivialTurn(String rawQuery) {
        return TRIVIAL_TURN.matcher(normalize(rawQuery)).matches();
    }

    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }
}
