package com.pppp.zhimesh.common.languagemodel.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息链快照编解码回环锁死：四类 ChatMessage（含工具请求、thinking、多段 contents、
 * 图片 base64）逐字段保真。这是挂起-恢复的最大技术点——任何字段漂移都会让恢复轮
 * 重建出不等价的消息链，直接影响模型行为。
 * <p>
 * Round-trip lock-down of the message-chain snapshot codec: all four
 * ChatMessage types (tool requests, thinking, multi-part contents, base64
 * images) must survive field-by-field. This is the riskiest piece of
 * suspend/resume — any drift rebuilds a non-equivalent chain on resume and
 * directly changes model behaviour.
 */
class ChatMessageSnapshotCodecTest {

    @Test
    void roundTripsAllFourMessageTypesFieldByField() {
        // 挂起瞬间的典型链：系统提示 → 用户(带图) → 助手(含 thinking + 工具请求) → 工具结果
        // <p>
        // A typical chain at suspension time: system prompt → user (with image) →
        // assistant (thinking + tool request) → tool result
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-abc-123")
                .name("search_knowledge")
                .arguments("{\"query\":\"Q3 预算\",\"topK\":5}")
                .build();
        List<ChatMessage> chain = List.of(
                SystemMessage.from("你是财务助手。"),
                UserMessage.from("小王",
                        TextContent.from("帮我分析这张图"),
                        ImageContent.from(
                                Image.builder().base64Data("aGVsbG8=").mimeType("image/png").build(),
                                ImageContent.DetailLevel.HIGH),
                        ImageContent.from(URI.create("https://example.com/chart.png"))),
                AiMessage.builder()
                        .thinking("用户要查预算，需要先调检索工具")
                        .text("我先查一下知识库。")
                        .toolExecutionRequests(List.of(request))
                        .build(),
                ToolExecutionResultMessage.from(request, "Q3 预算总额 100 万"));

        List<ChatMessage> decoded = ChatMessageSnapshotCodec.decode(ChatMessageSnapshotCodec.encode(chain));

        assertThat(decoded).hasSize(4);

        SystemMessage system = (SystemMessage) decoded.get(0);
        assertThat(system.text()).isEqualTo("你是财务助手。");

        UserMessage user = (UserMessage) decoded.get(1);
        assertThat(user.name()).isEqualTo("小王");
        assertThat(user.contents()).hasSize(3);
        assertThat(user.contents().get(0)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) user.contents().get(0)).text()).isEqualTo("帮我分析这张图");
        assertThat(user.contents().get(1)).isInstanceOf(ImageContent.class);
        ImageContent base64Image = (ImageContent) user.contents().get(1);
        // base64 图必须三要素齐全：数据、mimeType、detailLevel
        assertThat(base64Image.image().base64Data()).isEqualTo("aGVsbG8=");
        assertThat(base64Image.image().mimeType()).isEqualTo("image/png");
        assertThat(base64Image.detailLevel()).isEqualTo(ImageContent.DetailLevel.HIGH);
        ImageContent urlImage = (ImageContent) user.contents().get(2);
        assertThat(urlImage.image().url()).isEqualTo(URI.create("https://example.com/chart.png"));

        AiMessage ai = (AiMessage) decoded.get(2);
        // textual thinking 与正文分开保真，二者不可互相覆盖
        assertThat(ai.thinking()).isEqualTo("用户要查预算，需要先调检索工具");
        assertThat(ai.text()).isEqualTo("我先查一下知识库。");
        assertThat(ai.toolExecutionRequests()).hasSize(1);
        ToolExecutionRequest decodedRequest = ai.toolExecutionRequests().get(0);
        // 三字段保真是 langchain4j 配对完整性的硬约束：id 对不上恢复轮会构造出孤儿结果消息
        assertThat(decodedRequest.id()).isEqualTo("call-abc-123");
        assertThat(decodedRequest.name()).isEqualTo("search_knowledge");
        assertThat(decodedRequest.arguments()).isEqualTo("{\"query\":\"Q3 预算\",\"topK\":5}");

        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) decoded.get(3);
        assertThat(toolResult.id()).isEqualTo("call-abc-123");
        assertThat(toolResult.toolName()).isEqualTo("search_knowledge");
        assertThat(toolResult.text()).isEqualTo("Q3 预算总额 100 万");

        // 库级 equals 作为兜底断言：逐字段之外，整条链也逐消息相等
        assertThat(decoded).isEqualTo(chain);
    }

    @Test
    void roundTripsUserMessageWithMultipleTextContents() {
        // 单文本 UserMessage 也可含多段 contents（RAG 注入证据的形态），段序不可乱
        // <p>
        // A text-only UserMessage may still carry multiple contents (the RAG
        // evidence-injection shape); part order must be preserved
        UserMessage message = UserMessage.from(List.of(
                TextContent.from("问题：Q3 预算多少？"),
                TextContent.from("参考资料：报销制度 v2")));

        List<ChatMessage> decoded = ChatMessageSnapshotCodec.decode(ChatMessageSnapshotCodec.encode(List.of(message)));

        assertThat(decoded).hasSize(1);
        UserMessage restored = (UserMessage) decoded.get(0);
        assertThat(restored.contents()).hasSize(2);
        assertThat(((TextContent) restored.contents().get(0)).text()).isEqualTo("问题：Q3 预算多少？");
        assertThat(((TextContent) restored.contents().get(1)).text()).isEqualTo("参考资料：报销制度 v2");
    }

    @Test
    void roundTripsEmptyChainAsEmptyJsonArray() {
        // 空链是合法快照（不是损坏）：编码恒为 "[]"，解码回空链；null 同样按空链编码
        // <p>
        // An empty chain is a legal snapshot (not corruption): encode always
        // yields "[]", decode yields an empty list; null encodes as empty too
        assertThat(ChatMessageSnapshotCodec.encode(List.of())).isEqualTo("[]");
        assertThat(ChatMessageSnapshotCodec.encode(null)).isEqualTo("[]");
        assertThat(ChatMessageSnapshotCodec.decode("[]")).isEmpty();
    }

    @Test
    void decodeThrowsExplicitExceptionOnBlankSnapshot() {
        // 空白快照 = checkpoint 损坏，必须抛明确异常让上层 fail-safe 捕获，
        // 绝不宽容成空链（那会让恢复轮静默丢掉全部历史）
        assertThatThrownBy(() -> ChatMessageSnapshotCodec.decode(null))
                .isInstanceOf(ChatMessageSnapshotCodec.SnapshotDecodeException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> ChatMessageSnapshotCodec.decode("   "))
                .isInstanceOf(ChatMessageSnapshotCodec.SnapshotDecodeException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void decodeThrowsExplicitExceptionOnCorruptJson() {
        // 非法 JSON：异常类型明确且保留底层 cause，供日志定位损坏位置
        assertThatThrownBy(() -> ChatMessageSnapshotCodec.decode("not-json{"))
                .isInstanceOf(ChatMessageSnapshotCodec.SnapshotDecodeException.class)
                .hasMessageContaining("Failed to decode")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void decodeThrowsExplicitExceptionOnUnknownMessageType() {
        // 未知 type 判别值（未来版本写入的新消息类型回滚后读取）：同样走明确异常
        assertThatThrownBy(() -> ChatMessageSnapshotCodec.decode("[{\"type\":\"BOGUS\"}]"))
                .isInstanceOf(ChatMessageSnapshotCodec.SnapshotDecodeException.class)
                .hasMessageContaining("Failed to decode");
    }
}
