package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextualQueryRewriterTest {

    private final ShortTermMemoryService memoryService = mock(ShortTermMemoryService.class);
    private final AbstractLLMService llmService = mock(AbstractLLMService.class);
    private final ContextualQueryRewriter rewriter = new ContextualQueryRewriter(
            new RuleIntentRecognizer(), memoryService);

    @Test
    void completeQuestionStaysOnTheZeroExtraCallPath() {
        ContextualQueryRewriter.Result result = rewriter.resolve(
                "Nginx 如何配置 WebSocket 反向代理？", "memory-a", llmService);

        assertThat(result.rewritten()).isFalse();
        assertThat(result.retrievalQuery()).isEqualTo(result.originalQuery());
        verifyNoInteractions(memoryService, llmService);
    }

    @Test
    void embeddedPronounStillRequiresContextButNamedSubjectDoesNot() {
        assertThat(new RuleIntentRecognizer().requiresContextRewrite("请问这个接口为什么失败？"))
                .isTrue();
        assertThat(new RuleIntentRecognizer().requiresContextRewrite("Nginx WebSocket 接口为什么失败？"))
                .isFalse();
    }

    @Test
    void contextDependentQuestionUsesRecentHistoryOnce() {
        ChatModel chatModel = mock(ChatModel.class);
        when(memoryService.getMessages("memory-a")).thenReturn(List.of(
                UserMessage.from("知觅项目的后端服务监听哪个端口？"),
                AiMessage.from("后端服务监听 9999 端口。")));
        when(llmService.buildChatLLM(any(ChatModelBuilderProperties.class))).thenReturn(chatModel);
        when(chatModel.chat(any(ChatMessage[].class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("知觅项目的 9999 端口为什么会连接失败？"))
                .build());

        ContextualQueryRewriter.Result result = rewriter.resolve(
                "那它为什么会连接失败？", "memory-a", llmService);

        assertThat(result.rewritten()).isTrue();
        assertThat(result.originalQuery()).isEqualTo("那它为什么会连接失败？");
        assertThat(result.retrievalQuery()).isEqualTo("知觅项目的 9999 端口为什么会连接失败？");
    }

    @Test
    void missingHistoryFailsOpenToTheOriginalQuestion() {
        when(memoryService.getMessages("memory-a")).thenReturn(List.of());

        ContextualQueryRewriter.Result result = rewriter.resolve(
                "那个呢？", "memory-a", llmService);

        assertThat(result.rewritten()).isFalse();
        assertThat(result.retrievalQuery()).isEqualTo("那个呢？");
        verifyNoInteractions(llmService);
    }
}
