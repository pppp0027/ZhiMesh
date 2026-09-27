package com.pppp.zhimesh.common.languagemodel.tool;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 挂起检查点落库回调：由 CharacterChatService 侧以闭包实现并经
 * {@link ToolContext#setSuspensionSink(SuspensionCheckpointSink)} 注入，让
 * AbstractLLMService 在不依赖任何 Spring bean 的现状下也能把挂起快照持久化到
 * adi_agent_pending_checkpoint（与它现有拿 SseManager 静态方法/静态工具的模式同构，
 * 回调接口定义在 tool 包）。
 * <p>
 * 返回已落库检查点的 uuid，供挂起信号回填与 AnswerMeta.suspension 载荷使用。
 * 实现抛出的异常会原样传播：挂起轮以错误收场（宁可失败也不出现「前端已见问题卡片
 * 但无检查点可恢复」的半挂起态）。
 * <p>
 * Checkpoint-persistence callback for a suspension: implemented as a closure on
 * the CharacterChatService side and injected via
 * {@link ToolContext#setSuspensionSink(SuspensionCheckpointSink)}, so
 * AbstractLLMService persists the suspension snapshot into
 * adi_agent_pending_checkpoint without taking on a Spring-bean dependency
 * (same shape as its existing SseManager statics / static-utility pattern; the
 * callback interface lives in the tool package).
 * <p>
 * Returns the persisted checkpoint's uuid for signal backfill and the
 * AnswerMeta.suspension payload. Exceptions from the implementation propagate:
 * the suspending round then fails loudly (never a half-suspended state where
 * the frontend saw a question card yet no checkpoint exists to resume from).
 */
@FunctionalInterface
public interface SuspensionCheckpointSink {

    /**
     * 落一条挂起检查点。
     *
     * @param signal           挂起信号（kind/工具名/请求id/question/options） / The suspension signal
     * @param messagesSnapshot 消息链快照（含本轮 AiMessage(toolRequests) 与已执行同伴结果消息；
     *                         挂起与被忽略的协作请求不带结果，恢复轮注入） / Message-chain snapshot
     * @param toolCallDepth    挂起后已耗迭代数（含挂起轮） / Tool-loop depth after this suspension round
     * @param suspensionCount  含本次的挂起次数 / Suspension count including this one
     * @return 检查点 uuid / The checkpoint uuid
     */
    String persist(SuspensionSignal signal, List<ChatMessage> messagesSnapshot,
                   int toolCallDepth, int suspensionCount);
}
