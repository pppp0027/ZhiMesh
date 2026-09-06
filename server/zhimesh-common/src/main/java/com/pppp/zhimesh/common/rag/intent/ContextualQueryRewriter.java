package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.util.ZhiMeshStringUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Condenses only context-dependent follow-ups into standalone retrieval queries.
 * Complete questions never pay for an extra model call. Failures preserve the
 * raw query so the knowledge gate can fail open.
 */
@Slf4j
@Component
public class ContextualQueryRewriter {

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_HISTORY_CHARS = 3200;
    private static final int MAX_REWRITE_CHARS = 1200;
    private static final String KEEP_ORIGINAL = "__KEEP_ORIGINAL__";
    private static final Pattern OUTPUT_PREFIX = Pattern.compile(
            "^(?:改写后的(?:独立)?问题|完整问题|standalone query|rewritten query)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。请根据最近对话，把用户最新的上下文依赖问题改写成一条可独立理解的检索问题。
            规则：
            1. 只补全对话中已经明确出现的主体、对象和约束，不新增事实，不回答问题；
            2. 保留原问题的语言、专有名词、错误码、版本号、否定条件和时间条件；
            3. 只输出改写后的单条问题，不要解释、标题、Markdown 或引号；
            4. 如果历史不足以可靠补全，原样输出 __KEEP_ORIGINAL__。
            """;

    private final RuleIntentRecognizer ruleRecognizer;
    private final ShortTermMemoryService shortTermMemoryService;

    public ContextualQueryRewriter(RuleIntentRecognizer ruleRecognizer,
                                   ShortTermMemoryService shortTermMemoryService) {
        this.ruleRecognizer = ruleRecognizer;
        this.shortTermMemoryService = shortTermMemoryService;
    }

    public Result resolve(String rawQuery, String memoryId, AbstractLLMService llmService) {
        String query = StringUtils.trimToEmpty(rawQuery);
        if (StringUtils.isBlank(query) || !ruleRecognizer.requiresContextRewrite(query)) {
            return Result.unchanged(query, false, "standalone query");
        }
        if (StringUtils.isBlank(memoryId) || llmService == null) {
            return Result.unchanged(query, true, "conversation context is unavailable");
        }

        String history;
        try {
            history = recentHistory(shortTermMemoryService.getMessages(memoryId));
        } catch (RuntimeException exception) {
            log.warn("Unable to read short-term memory for query rewrite, memoryId:{}, reason:{}",
                    memoryId, exception.getMessage());
            return Result.unchanged(query, true, "conversation context read failed");
        }
        if (StringUtils.isBlank(history)) {
            return Result.unchanged(query, true, "conversation context is empty");
        }

        try {
            ChatModel chatModel = llmService.buildChatLLM(
                    ChatModelBuilderProperties.builder().temperature(0.1D).build());
            String prompt = "最近对话：\n" + history + "\n\n用户最新问题：\n" + query;
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(prompt));
            String rewritten = sanitize(response == null || response.aiMessage() == null
                    ? null : response.aiMessage().text());
            if (!validRewrite(query, rewritten)) {
                return Result.unchanged(query, true, "rewrite output is unavailable or unsafe");
            }
            log.debug("Context-dependent retrieval query rewritten, originalChars:{}, rewrittenChars:{}",
                    query.length(), rewritten.length());
            return new Result(query, rewritten, true, true, "context rewrite completed");
        } catch (RuntimeException exception) {
            log.warn("Context-dependent query rewrite failed open, memoryId:{}, reason:{}",
                    memoryId, exception.getMessage());
            return Result.unchanged(query, true, "rewrite model call failed");
        }
    }

    private static String recentHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        List<String> selected = new ArrayList<>();
        int chars = 0;
        for (int index = messages.size() - 1;
             index >= 0 && selected.size() < MAX_HISTORY_MESSAGES && chars < MAX_HISTORY_CHARS;
             index--) {
            String line = historyLine(messages.get(index));
            if (StringUtils.isBlank(line)) continue;
            int remaining = MAX_HISTORY_CHARS - chars;
            String bounded = StringUtils.substring(line, 0, remaining);
            selected.add(0, bounded);
            chars += bounded.length();
        }
        return String.join("\n", selected);
    }

    private static String historyLine(ChatMessage message) {
        try {
            if (message instanceof UserMessage userMessage) {
                return "用户：" + StringUtils.trimToEmpty(userMessage.singleText());
            }
            if (message instanceof AiMessage aiMessage) {
                return "助手：" + StringUtils.trimToEmpty(aiMessage.text());
            }
        } catch (RuntimeException ignored) {
            // Multimodal or malformed historical turns are not useful for text condensation.
        }
        return "";
    }

    private static String sanitize(String output) {
        String value = StringUtils.trimToEmpty(
                ZhiMeshStringUtil.removeCodeBlock(StringUtils.defaultString(output)));
        value = OUTPUT_PREFIX.matcher(value).replaceFirst("").replaceAll("\\s+", " ").trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '“' && last == '”')
                    || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static boolean validRewrite(String original, String rewritten) {
        return StringUtils.isNotBlank(rewritten)
                && !KEEP_ORIGINAL.equalsIgnoreCase(rewritten)
                && rewritten.length() <= MAX_REWRITE_CHARS
                && !StringUtils.equals(original, rewritten);
    }

    public record Result(String originalQuery, String retrievalQuery, boolean rewriteAttempted,
                         boolean rewritten, String reason) {
        static Result unchanged(String query, boolean attempted, String reason) {
            return new Result(query, query, attempted, false, reason);
        }
    }
}
