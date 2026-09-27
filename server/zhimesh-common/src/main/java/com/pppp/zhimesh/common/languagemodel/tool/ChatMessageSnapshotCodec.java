package com.pppp.zhimesh.common.languagemodel.tool;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

/**
 * 挂起-恢复链路的消息链快照编解码器：List&lt;ChatMessage&gt; ↔ JSON 字符串，
 * 供 adi_agent_pending_checkpoint.messages_snapshot 落库与恢复重建。
 * <p>
 * 直接复用 langchain4j 自带的 {@link dev.langchain4j.data.message.ChatMessageSerializer} /
 * {@link dev.langchain4j.data.message.ChatMessageDeserializer}（内部 Jackson mixin
 * 覆盖全部消息子类型），而非自建 DTO 中转：多态判别（type 字段）、UserMessage 的
 * 多段 contents（text/image）、AiMessage 的 thinking 与 toolExecutionRequests、
 * ToolExecutionResultMessage 的 id/toolName/text 均由官方编解码保证逐字段保真，
 * 且随 langchain4j 版本升级自动保持兼容。项目内先例：
 * memory/shortterm/ShortTermMemoryMessageCodec 同样是该对静态方法的薄封装。
 * <p>
 * 与短期记忆编解码的差异在失败语义：短期记忆把空/坏输入宽容成空链；快照一旦
 * 解码失败意味着 checkpoint 已损坏，必须抛出明确的
 * {@link SnapshotDecodeException} 让上层（挂起-恢复内核）走 fail-safe
 * （作废 checkpoint、当普通新消息处理），绝不静默吞掉。
 * <p>
 * Message-chain snapshot codec for the suspend/resume flow:
 * List&lt;ChatMessage&gt; ↔ JSON, persisted into
 * adi_agent_pending_checkpoint.messages_snapshot and rebuilt on resume.
 * Delegates to langchain4j's built-in {@link dev.langchain4j.data.message.ChatMessageSerializer}
 * / {@link dev.langchain4j.data.message.ChatMessageDeserializer} (whose Jackson
 * mixins cover every message subtype) instead of hand-rolled DTOs: polymorphic
 * type discrimination, multi-part UserMessage contents (text/image), AiMessage
 * thinking + toolExecutionRequests, and ToolExecutionResultMessage
 * id/toolName/text are all preserved field-by-field by the official codec and
 * stay compatible across langchain4j upgrades. Precedent in this repo:
 * memory/shortterm/ShortTermMemoryMessageCodec is a thin wrapper over the same
 * pair of statics.
 * <p>
 * The deliberate difference from the short-term-memory codec is failure
 * semantics: a snapshot that fails to decode means the checkpoint is corrupt,
 * so decode throws an explicit {@link SnapshotDecodeException} for the caller's
 * fail-safe path (invalidate the checkpoint, treat the message as a fresh one)
 * instead of leniently returning an empty chain.
 */
public final class ChatMessageSnapshotCodec {

    private ChatMessageSnapshotCodec() {
    }

    /**
     * 编码消息链快照。null 视为空链，输出恒为合法 JSON 数组（空链为 "[]"），
     * 空链不作为损坏信号——恢复侧允许把空快照当成“无历史”处理。
     * <p>
     * Encode a message-chain snapshot. null is treated as an empty chain; the
     * output is always a valid JSON array ("[]" for an empty chain). An empty
     * chain is not treated as corruption — the resume side may legitimately
     * rebuild from an empty snapshot.
     */
    public static String encode(List<ChatMessage> messages) {
        List<ChatMessage> snapshot = messages == null ? List.of() : messages;
        return messagesToJson(snapshot);
    }

    /**
     * 解码消息链快照。空白或损坏的 JSON 抛 {@link SnapshotDecodeException}
     * （cause 保留底层 Jackson 异常），供上层 fail-safe 捕获，绝不返回半吊子数据。
     * <p>
     * Decode a message-chain snapshot. Blank or corrupt JSON throws
     * {@link SnapshotDecodeException} (with the underlying Jackson exception as
     * cause) for the caller's fail-safe path — never half-decoded data.
     */
    public static List<ChatMessage> decode(String json) {
        if (json == null || json.isBlank()) {
            throw new SnapshotDecodeException("Checkpoint messages snapshot is blank");
        }
        try {
            List<ChatMessage> messages = messagesFromJson(json);
            return messages == null ? List.of() : messages;
        } catch (RuntimeException e) {
            throw new SnapshotDecodeException("Failed to decode checkpoint messages snapshot", e);
        }
    }

    /**
     * 快照解码失败异常：意味着 checkpoint 已损坏，上层应作废该 checkpoint 并
     * 走“当普通新消息处理”的 fail-safe 分支，而非 500。
     * <p>
     * Thrown when a snapshot cannot be decoded: the checkpoint is corrupt. The
     * caller should invalidate it and fall back to treating the incoming
     * message as a fresh one instead of failing the request.
     */
    public static class SnapshotDecodeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SnapshotDecodeException(String message) {
            super(message);
        }

        public SnapshotDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
