package com.pppp.zhimesh.common.helper;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.cosntant.RedisKeyConstant;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.interfaces.TriConsumer;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.languagemodel.data.LLMResponseContent;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.util.*;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * SSE 管理器
 * <p>
 * 统一管理 SSE 请求的完整生命周期：注册、限流、事件发送、清理。
 * 内部通过 {@link SseEntry} 同时追踪 SseEmitter 实例和关联的 userId，
 * entry 从 map 中移除即代表已完成，天然防止重复完成。
 * </p>
 * <p>
 * SSE manager — unified lifecycle management for SSE connections: registration,
 * rate limiting, event dispatching, and cleanup. The internal {@link SseEntry}
 * tracks both the emitter and its associated userId; removing the entry from
 * the map signals completion, providing natural double-completion prevention.
 * </p>
 */
@Slf4j
@Service
public class SseManager {

    /**
     * 内部条目：emitter + userId 一体
     * <p>
     * Internal entry bundling an emitter with its owning userId.
     * </p>
     */
    private record SseEntry(SseEmitter emitter, long userId) {
    }

    private final ConcurrentHashMap<String, SseEntry> entries = new ConcurrentHashMap<>();

    /**
     * Web 用户最大并发 SSE 数 / Max concurrent SSE for web users
     */
    private static final int MAX_WEB_CONCURRENT = 1;
    /**
     * API 用户最大并发 SSE 数 / Max concurrent SSE for API users
     */
    private static final int MAX_API_CONCURRENT = 5;

    /**
     * TOOL_CALL 事件 resultSummary 字段的下发截断长度，防止完整工具结果撑爆 SSE 载荷
     * <p>
     * Truncation length for the resultSummary field of the TOOL_CALL event,
     * keeping full tool results from blowing up the SSE payload.
     */
    private static final int TOOL_CALL_RESULT_SUMMARY_MAX_CHARS = 200;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RateLimitHelper rateLimitHelper;

    @Autowired(required = false)
    private ShortTermMemoryTurnCoordinator shortTermMemoryTurnCoordinator;

    // ==================== 注册/查找/注销 ====================

    /**
     * 注册 SseEmitter 并关联 userId
     * <p>
     * Register an emitter associated with a userId.
     * </p>
     *
     * @param uuid    SSE 请求标识 / SSE request identifier
     * @param emitter SseEmitter 实例 / SseEmitter instance
     * @param userId  用户 ID / User ID
     */
    public void register(String uuid, SseEmitter emitter, long userId) {
        entries.put(uuid, new SseEntry(emitter, userId));
    }

    /**
     * 注册 SseEmitter（无 userId，仅 blocking 模式兼容）
     * <p>
     * Register an emitter without userId (blocking mode compatibility).
     * </p>
     *
     * @param uuid    SSE 请求标识 / SSE request identifier
     * @param emitter SseEmitter 实例 / SseEmitter instance
     */
    public void register(String uuid, SseEmitter emitter) {

        entries.put(uuid, new SseEntry(emitter, -1));
    }

    /**
     * 获取 SseEmitter
     * <p>
     * Get the SseEmitter for the given uuid.
     * </p>
     *
     * @param uuid SSE 请求标识 / SSE request identifier
     * @return SseEmitter 实例，不存在则返回 null / SseEmitter or null
     */
    public SseEmitter get(String uuid) {
        SseEntry entry = entries.get(uuid);
        return entry != null ? entry.emitter() : null;
    }

    /**
     * 获取关联的 userId
     * <p>
     * Get the userId associated with the given uuid.
     * </p>
     *
     * @param uuid SSE 请求标识 / SSE request identifier
     * @return userId，不存在则返回 null / userId or null
     */
    public Long getUserId(String uuid) {
        SseEntry entry = entries.get(uuid);
        return entry != null ? entry.userId() : null;
    }

    /**
     * 注销 SseEmitter 并递减 Redis 并发计数（幂等）
     * <p>
     * Unregister the emitter and decrement the Redis concurrency counter (idempotent).
     * </p>
     *
     * @param uuid SSE 请求标识 / SSE request identifier
     */
    public void unregister(String uuid) {
        SseEntry removed = entries.remove(uuid);
        if (removed != null && removed.userId() > 0) {
            decActiveSseCount(removed.userId(), uuid); // Redis 并发数-1
        }
        if (shortTermMemoryTurnCoordinator != null) {
            shortTermMemoryTurnCoordinator.releaseRequest(uuid);
        }
    }

    /**
     * 判断是否已完成（entry 不存在 = 已完成）
     * <p>
     * Check if the emitter has been completed (entry absent = completed).
     * </p>
     */
    public boolean isCompleted(String uuid) {
        return !entries.containsKey(uuid);
    }

    // ==================== 请求入口（check + start） ====================

    /**
     * 检查请求是否允许（限流 + 并发数检查），不允许则直接发送错误并完成 SSE
     * <p>
     * Check if request is allowed (rate limit + concurrency check).
     * If not allowed, sends error and completes the SSE.
     * </p>
     *
     * @param user       用户 / User
     * @param sseUuid    SSE 请求标识 / SSE request identifier
     * @param sseEmitter SseEmitter 实例 / SseEmitter instance
     * @return true=允许 / allowed, false=已拒绝 / rejected
     */
    public boolean checkOrComplete(User user, String sseUuid, SseEmitter sseEmitter) {
        //Check: rate limit
        String requestTimesKey = MessageFormat.format(RedisKeyConstant.USER_REQUEST_TEXT_TIMES, user.getId());
        if (!rateLimitHelper.checkRequestTimes(requestTimesKey, LocalCache.TEXT_RATE_LIMIT_CONFIG)) {
            doSendErrorAndComplete(user.getId(), sseUuid, sseEmitter, SpringUtil.getMessage(ErrorEnum.A_REQUEST_TOO_MUCH.getInfo()));
            return false;
        }

        //Check: concurrent SSE count
        String activeKey = MessageFormat.format(RedisKeyConstant.USER_ACTIVE_SSE_COUNT, user.getId());
        stringRedisTemplate.opsForSet().add(activeKey, sseUuid);
        //每次添加都刷新 TTL = 6min（略大于 SseEmitter 5min timeout），防止 crash 导致集合永不清理
        stringRedisTemplate.expire(activeKey, 6, TimeUnit.MINUTES);
        Long size = stringRedisTemplate.opsForSet().size(activeKey);
        boolean isApi = ThreadContext.isExtApiRequest();
        int maxConcurrent = isApi ? MAX_API_CONCURRENT : MAX_WEB_CONCURRENT;
        if (size != null && size > maxConcurrent) {
            stringRedisTemplate.opsForSet().remove(activeKey, sseUuid);
            doSendErrorAndComplete(user.getId(), sseUuid, sseEmitter, SpringUtil.getMessage("SSE_RESPONDING"));
            return false;
        }
        return true;
    }

    /**
     * 启动 SSE 流，发送 START 事件（从管理器查找 emitter）
     * <p>
     * Start SSE stream, send START event (look up emitter from manager).
     * </p>
     */
    public void startSse(User user, String sseUuid) {

        startSse(user, sseUuid, get(sseUuid), null);
    }

    /**
     * 启动 SSE 流，发送 START 事件（从管理器查找 emitter）
     * <p>
     * Start SSE stream, send START event (look up emitter from manager).
     * </p>
     */
    public void startSse(User user, String sseUuid, String data) {

        startSse(user, sseUuid, get(sseUuid), data);
    }

    public void startSse(User user, String sseUuid, SseEmitter sseEmitter, String data) {
        if (sseEmitter == null) {
            log.info("startSse skipped because the client connection is already closed,sseUuid:{}", sseUuid);
            return;
        }
        register(sseUuid, sseEmitter, user.getId()); // 登记进Map

        String requestTimesKey = MessageFormat.format(RedisKeyConstant.USER_REQUEST_TEXT_TIMES, user.getId());
        rateLimitHelper.increaseRequestTimes(requestTimesKey, LocalCache.TEXT_RATE_LIMIT_CONFIG); // 速率+1
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(ZhiMeshConstant.SSEEventName.START); // event:[START]
            if (StringUtils.isNotBlank(data)) {
                builder.data(data); // 如果传了data，在这里附带上
            }
            sseEmitter.send(builder); // 告诉前端开始了
        } catch (Exception e) {
            log.error("startSse error", e);
            sseEmitter.completeWithError(e); // 标记连接异常结束
            unregister(sseUuid); // 从Map和Redis清理
        }
    }

    // ==================== 流式调用入口 ====================

    /**
     * event_stream 请求，完成后关闭 sse 并执行回调
     * <p>
     * event_stream request: close SSE and execute callback after completion.
     * </p>
     *
     * @param llmService      已完成路由的 LLM 服务 / Pre-resolved LLM service
     * @param sseAskParam     请求参数（必须包含 sseUuid）/ Request parameters (must include sseUuid)
     * @param completeCallback 请求结束后的回调 / Callback after request completion
     */
    public void call(AbstractLLMService llmService, SseAskParam sseAskParam,
                     TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> completeCallback) {
        call(llmService, sseAskParam, Runnable::run, completeCallback);
    }

    /**
     * Dispatches completion-side persistence to a bounded executor. Streaming HTTP
     * client callback threads only enqueue the work; the SSE is unregistered after
     * the application callback has committed and sent its terminal event.
     */
    public void call(AbstractLLMService llmService, SseAskParam sseAskParam,
                     Executor completionExecutor,
                     TriConsumer<LLMResponseContent, PromptMeta, AnswerMeta> completeCallback) {
        AbstractLLMService resolvedLlmService = Objects.requireNonNull(llmService, "llmService");
        String sseUuid = sseAskParam.getSseUuid();
        registerEventStreamListener(sseAskParam); // 给SseEmitter绑定生命周期回调（超时，异常该怎么办）
        // 新一次请求开始前清掉该 uuid 可能残留的累计记录：重新生成复用同一
        // questionUuid，若不清空会把上一轮尝试的中间轮也计入本次计费
        // Clear any stale accumulation for this uuid before the new request
        // starts: regenerate reuses the same questionUuid, and leftover
        // intermediate rounds from the previous attempt would be billed again
        LLMTokenUtil.resetTokenUsage(stringRedisTemplate, sseAskParam.getUuid());
        // 模型路由由上层业务完成，避免同一请求在此处按字符串二次解析后选中不同服务。
        resolvedLlmService.streamingChat(sseAskParam, (response, promptMeta, answerMeta) -> {
            try {
                completionExecutor.execute(() -> {
                    try {
                        completeCallback.accept(response, promptMeta, answerMeta);// LLM结束后执行业务回调（写DB，扣配额）
                    } catch (Exception e) {
                        log.error("commonProcess error", e);
                        errorAndShutdown(e, sseUuid);
                    } finally {
                        unregister(sseUuid); // 清理SSE
                    }
                });
            } catch (RuntimeException submissionError) {
                log.warn("SSE completion task rejected, sseUuid:{}", sseUuid, submissionError);
                errorAndShutdown(new IllegalStateException(SpringUtil.getMessage("A_SYSTEM_BUSY"), submissionError),
                        sseUuid);
            }
        });
    }

    /**
     * 注册 event stream 的生命周期事件（onCompletion/onTimeout/onError）
     * <p>
     * Register lifecycle events for the event stream.
     * </p>
     *
     * @param sseAskParam 参数（必须包含 sseUuid）/ Parameters (must include sseUuid)
     */
    public void registerEventStreamListener(SseAskParam sseAskParam) {
        registerEventStreamListener(sseAskParam.getUser(), sseAskParam.getSseUuid());
    }

    /**
     * Register connection-only lifecycle callbacks. Workflow execution uses this overload because
     * disconnecting the browser must release the emitter without deciding the business result.
     */
    public void registerEventStreamListener(User user, String sseUuid) {
        SseEmitter sseEmitter = get(sseUuid);
        if (sseEmitter == null) {
            log.error("registerEventStreamListener: SseEmitter not found for sseUuid:{}", sseUuid);
            throw new IllegalStateException("SSE connection is no longer active");
        }
        sseEmitter.onCompletion(() -> {
            log.info("response complete,uid:{}", user.getId());
            unregister(sseUuid);
        }); // 正常结束清理
        sseEmitter.onTimeout(() -> {
            log.warn("sseEmitter timeout,uid:{},on timeout:{}", user.getId(), sseEmitter.getTimeout());
            try {
                sseEmitter.complete();
            } finally {
                unregister(sseUuid);
            }
        });// emitter timeout
        sseEmitter.onError(
                throwable -> {
                    try {
                        log.error("sseEmitter error,uid:{},on error", user.getId(), throwable);
                        sseEmitter.send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.ERROR).data(throwable.getMessage()));
                    } catch (IOException e) {
                        log.error("error", e);
                    } finally {
                        unregister(sseUuid);
                    }
                }
        ); // 发生错误调用
    }

    // ==================== 实例方法（接受 uuid，内部查找 emitter） ====================

    public void sendComplete(long userId, String sseUuid, String msg) {
        SseEntry entry = entries.get(sseUuid);
        if (entry == null) {
            log.warn("sseEmitter already completed or not found,userId:{}", userId);
            return;
        }
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.DONE).data(msg));
            entry.emitter().complete();
        } catch (Exception e) {
            log.warn("sendComplete error,userId:{}", userId, e);
        } finally {
            unregister(sseUuid);
        }
    }

    public void sendComplete(long userId, String sseUuid, PromptMeta questionMeta, AnswerMeta answerMeta, AudioInfo audioInfo) {
        sendComplete(userId, sseUuid, questionMeta, answerMeta, audioInfo, null);
    }

    public void sendComplete(long userId, String sseUuid, PromptMeta questionMeta, AnswerMeta answerMeta,
                             AudioInfo audioInfo, String conversationUuid) {
        ChatMeta chatMeta = new ChatMeta(questionMeta, answerMeta, audioInfo, conversationUuid);
        String meta = JsonUtil.toJson(chatMeta).replace("\r\n", "");
        this.sendComplete(userId, sseUuid, " " + ZhiMeshConstant.SSEEventName.META + meta);
    }

    /**
     * 关闭 sse
     * <p>
     * Close SSE.
     * </p>
     *
     * @param userId  用户id / User ID
     * @param sseUuid SSE 请求标识 / SSE request identifier
     */
    public void sendComplete(long userId, String sseUuid) {
        SseEntry entry = entries.get(sseUuid);
        if (entry == null) {
            log.warn("sseEmitter already completed or not found,userId:{}", userId);
            return;
        }
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.DONE));
            entry.emitter().complete();
        } catch (Exception e) {
            log.warn("sendComplete error", e);
        } finally {
            unregister(sseUuid);
        }
    }

    public void sendStartAndComplete(long userId, String sseUuid, String msg) {
        SseEntry entry = entries.get(sseUuid);
        if (entry == null) {
            log.warn("sendStartAndComplete: SseEmitter not found for sseUuid:{}", sseUuid);
            return;
        }
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.START));
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.DONE).data(msg));
            entry.emitter().complete();
        } catch (Exception e) {
            log.warn("sendStartAndComplete error,userId:{}", userId, e);
        } finally {
            unregister(sseUuid);
        }
    }

    public void sendErrorAndComplete(long userId, String sseUuid, String errorMsg) {
        SseEmitter sseEmitter = get(sseUuid);
        doSendErrorAndComplete(userId, sseUuid, sseEmitter, errorMsg);
    }

    // ==================== 静态方法（接受 uuid，通过 SpringUtil 获取 manager） ====================

    public static void parseAndSendPartialMsg(String uuid, String content) {
        parseAndSendPartialMsg(uuid, "", content);
    }
    // 推送TTS音频
    public static void sendAudio(String uuid, Object content) {
        SseEntry entry = getEntry(uuid);
        if (entry == null) return;
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.AUDIO).data(content)); // 推送给前端
        } catch (IOException e) {
            log.error("stream onNext error", e);
            throw new RuntimeException(e);
        }
    }
    // 推送思考过程
    public static void sendThinking(String uuid, String content) {
        SseEntry entry = getEntry(uuid);
        if (entry == null) return;
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.THINKING).data(content));
        } catch (IOException e) {
            log.error("stream onNext error", e);
            throw new RuntimeException(e);
        }
    }
    // 推送工具调用状态
    public static void sendToolCall(String uuid, String toolName, long durationMs, boolean success) {
        sendToolCall(uuid, toolName, durationMs, success, null, null);
    }

    /**
     * 推送工具调用状态（扩展载荷：args / resultSummary 两个可空新字段，旧三字段名不变）。
     * <p>
     * uuid 为 null 时（blocking 路径无 SSE）直接返回：ConcurrentHashMap.get(null) 会抛 NPE。
     * resultSummary 只下发摘要（截断至 {@link #TOOL_CALL_RESULT_SUMMARY_MAX_CHARS} 字符），
     * 防止完整工具结果撑爆 SSE 载荷。
     * <p>
     * Push tool-call status (extended payload: two nullable new fields
     * args / resultSummary; the legacy three field names are unchanged).
     * A null uuid (blocking path, no SSE) returns immediately because
     * ConcurrentHashMap.get(null) would throw an NPE. resultSummary carries a
     * summary only (truncated to {@link #TOOL_CALL_RESULT_SUMMARY_MAX_CHARS}
     * chars) to keep the SSE payload small.
     */
    public static void sendToolCall(String uuid, String toolName, long durationMs, boolean success,
                                    String args, String resultSummary) {
        if (uuid == null) {
            // blocking 路径没有已注册的 emitter，且 entries.get(null) 本身会抛 NPE
            // The blocking path has no registered emitter, and entries.get(null) itself throws an NPE
            return;
        }
        SseEntry entry = getEntry(uuid);
        if (entry == null) {
            return;
        }
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.TOOL_CALL)
                    .data(JsonUtil.toJson(buildToolCallPayload(toolName, durationMs, success, args, resultSummary))));
        } catch (Exception e) {
            log.error("sendToolCall error", e);
            SpringUtil.getBean(SseManager.class).unregister(uuid);
        }
    }

    /**
     * 组装 TOOL_CALL 事件载荷：旧三字段名与语义不变（向后兼容红线），args /
     * resultSummary 为可空新增字段，仅在非 null 时出现；resultSummary 截断至
     * {@link #TOOL_CALL_RESULT_SUMMARY_MAX_CHARS} 字符
     * <p>
     * Build the TOOL_CALL event payload: the legacy three fields keep their
     * names and semantics (backward-compatibility red line); args /
     * resultSummary are nullable additions present only when non-null, and
     * resultSummary is truncated to {@link #TOOL_CALL_RESULT_SUMMARY_MAX_CHARS}
     * chars.
     */
    static Map<String, Object> buildToolCallPayload(String toolName, long durationMs, boolean success,
                                                    String args, String resultSummary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName != null ? toolName : "unknown");
        payload.put("durationMs", durationMs);
        payload.put("success", success);
        if (args != null) {
            payload.put("args", args);
        }
        if (resultSummary != null) {
            payload.put("resultSummary",
                    StringUtils.substring(resultSummary, 0, TOOL_CALL_RESULT_SUMMARY_MAX_CHARS));
        }
        return payload;
    }

    /**
     * 推送工具开始执行事件（TOOL_STARTED）：工具真正执行前实时点亮前端的执行中步骤，
     * 与 {@link #sendToolCall} 完成事件配对（完成事件回填时长/结果摘要）。载荷精简版：
     * toolName（必填，null 兜底 "unknown"）+ args（仅非 null 时放入，截断至
     * {@link #TOOL_CALL_RESULT_SUMMARY_MAX_CHARS} 字符）。null uuid（blocking 路径无
     * SSE）与未注册 uuid 的短路口径、发送异常注销连接的处理与 {@link #sendToolCall} 一致。
     * <p>
     * Push the tool-started event (TOOL_STARTED): lights up the running tool
     * step on the frontend in real time right before a tool executes, paired
     * with the {@link #sendToolCall} completion event (which backfills
     * duration/result summary). Slim payload: toolName (required, null falls
     * back to "unknown") plus args (present only when non-null, truncated to
     * {@link #TOOL_CALL_RESULT_SUMMARY_MAX_CHARS} chars). A null uuid (blocking
     * path, no SSE), an unregistered uuid short-circuit, and unregister-on-
     * send-failure all match {@link #sendToolCall}.
     */
    public static void sendToolStarted(String uuid, String toolName, String args) {
        if (uuid == null) {
            // blocking 路径没有已注册的 emitter，且 entries.get(null) 本身会抛 NPE
            // The blocking path has no registered emitter, and entries.get(null) itself throws an NPE
            return;
        }
        SseEntry entry = getEntry(uuid);
        if (entry == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", toolName != null ? toolName : "unknown");
            if (args != null) {
                payload.put("args", StringUtils.substring(args, 0, TOOL_CALL_RESULT_SUMMARY_MAX_CHARS));
            }
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.TOOL_STARTED)
                    .data(JsonUtil.toJson(payload)));
        } catch (Exception e) {
            log.error("sendToolStarted error", e);
            SpringUtil.getBean(SseManager.class).unregister(uuid);
        }
    }

    /**
     * 推送 Agent 协作提问事件（agent_question）：ask_user 挂起时的问题卡片载荷
     * （kind/toolName/question/options，options 可空省略）。null uuid（blocking 路径无
     * SSE）与未注册 uuid 的短路口径与 {@link #sendToolCall} 一致。
     * <p>
     * Push the agent collaborative-question event (agent_question): the
     * question-card payload (kind/toolName/question/options; options omitted
     * when null) emitted when ask_user suspends the loop. A null uuid (blocking
     * path, no SSE) and an unregistered uuid short-circuit exactly like
     * {@link #sendToolCall}.
     */
    public static void sendAgentQuestion(String uuid, Map<String, Object> payload) {
        sendSuspensionEvent(uuid, ZhiMeshConstant.SSEEventName.AGENT_QUESTION, payload, "sendAgentQuestion");
    }

    /**
     * 推送 Agent 协作审批事件（approval_request）：request_human_approval 显式审批与
     * MCP 需审批工具拦截挂起时的审批卡片载荷（kind/toolName/question/action/summary/
     * riskLevel，riskLevel 可空省略；kind=APPROVAL/MCP_APPROVAL 供前端区分两种审批形态）。
     * null uuid 与未注册 uuid 的短路口径与 {@link #sendAgentQuestion} 一致
     * <p>
     * Push the agent collaborative-approval event (approval_request): the
     * approval-card payload (kind/toolName/question/action/summary/riskLevel;
     * riskLevel omitted when null; kind=APPROVAL/MCP_APPROVAL lets the
     * frontend tell the two approval shapes apart) emitted when
     * request_human_approval suspends explicitly or an approval-required MCP
     * tool is intercepted. A null uuid and an unregistered uuid short-circuit
     * exactly like {@link #sendAgentQuestion}.
     */
    public static void sendApprovalRequest(String uuid, Map<String, Object> payload) {
        sendSuspensionEvent(uuid, ZhiMeshConstant.SSEEventName.APPROVAL_REQUEST, payload, "sendApprovalRequest");
    }

    /**
     * 协作挂起事件的公共发送实现（agent_question / approval_request 同构，仅事件名不同）：
     * null uuid 短路、未注册 uuid 短路、发送异常注销连接，口径与 {@link #sendToolCall} 一致
     * <p>
     * Common send implementation for collaborative-suspension events
     * (agent_question / approval_request share the shape, differing only in
     * the event name): null-uuid short-circuit, unregistered-uuid
     * short-circuit, and unregister-on-send-failure, matching {@link #sendToolCall}.
     */
    private static void sendSuspensionEvent(String uuid, String eventName, Map<String, Object> payload,
                                            String caller) {
        if (uuid == null) {
            return;
        }
        SseEntry entry = getEntry(uuid);
        if (entry == null) {
            return;
        }
        try {
            entry.emitter().send(SseEmitter.event().name(eventName)
                    .data(JsonUtil.toJson(payload)));
        } catch (Exception e) {
            log.error("{} error", caller, e);
            SpringUtil.getBean(SseManager.class).unregister(uuid);
        }
    }

    public static void parseAndSendPartialMsg(String uuid, String name, String content) {
        SseEntry entry = getEntry(uuid);
        if (entry == null) return;
        String[] lines = content.split("[\\r\\n]", -1);
        if (lines.length > 1) {
            sendPartial(uuid, name, entry, " " + lines[0]);
            for (int i = 1; i < lines.length; i++) {
                sendPartial(uuid, name, entry, "-_wrap_-");
                sendPartial(uuid, name, entry, " " + lines[i]);
            }
        } else {
            sendPartial(uuid, name, entry, " " + content);
        }
    }

    public static void sendPartial(String uuid, String name, String msg) {
        SseEntry entry = getEntry(uuid);
        if (entry == null) return;
        sendPartial(uuid, name, entry, msg);
    }

    /** 内部 sendPartial：复用上层已获取的 entry */
    private static void sendPartial(String uuid, String name, SseEntry entry, String msg) {
        try {
            if (StringUtils.isNotBlank(name)) {
                entry.emitter().send(SseEmitter.event().name(name).data(msg));
            } else {
                entry.emitter().send(msg);
            }
        } catch (IOException ioException) {
            log.error("stream onNext error", ioException);
            SpringUtil.getBean(SseManager.class).unregister(uuid);
            throw new RuntimeException(ioException);
        }
    }

    public static void errorAndShutdown(Throwable error, String uuid) {
        SpringUtil.getBean(SseManager.class).handleStreamError(error, uuid);
    }

    void handleStreamError(Throwable error, String uuid) {
        SseEntry entry = entries.get(uuid);
        if (entry == null) return;
        log.error("stream error", error);
        doSendErrorAndComplete(entry.userId(), uuid, entry.emitter(), error.getMessage());
    }

    // ==================== 不涉及 SseEmitter 的工具方法 ====================

    /**
     * 计算 llm 返回消费的 token
     * <p>
     * Calculate tokens consumed by LLM response.
     * </p>
     */
    public static Pair<PromptMeta, AnswerMeta> calculateToken(ChatResponse response, String uuid) {
        log.info("Streaming response completed, uuid:{},hasMetadata:{}", uuid, response.metadata() != null);
        int inputTokenCount = 0;
        int outputTokenCount = 0;
        if (response.metadata() != null && response.metadata().tokenUsage() != null) {
            Integer input = response.metadata().tokenUsage().inputTokenCount();
            Integer output = response.metadata().tokenUsage().outputTokenCount();
            inputTokenCount = input != null ? input : 0;
            outputTokenCount = output != null ? output : 0;
        }
        log.info("StreamingChatModel token cost,uuid:{},inputTokenCount:{},outputTokenCount:{}", uuid, inputTokenCount, outputTokenCount);
        //只在 tokenUsage 不为 null 时才缓存，避免 NPE | Only cache when tokenUsage is non-null to avoid NPE
        if (response.metadata() != null && response.metadata().tokenUsage() != null) {
            LLMTokenUtil.cacheTokenUsage(SpringUtil.getBean(StringRedisTemplate.class), uuid, response.metadata().tokenUsage());
        }

        // 工具循环的中间轮 token 已按轮缓存在同一 uuid 的 List 中（见 innerStreamingChat），
        // 计费与展示都应取全部轮次的累计值，而非仅最终轮；缓存不可用时回退最终轮数值。
        // 单轮请求 List 仅含最终轮，累计值与原值相等，行为不变。
        // <p>
        // Intermediate tool-loop rounds were cached under the same uuid List (see
        // innerStreamingChat); billing and display should use the accumulated total
        // across all rounds instead of the final round alone. Falls back to the
        // final-round numbers when the cache is unavailable. For single-round
        // requests the List holds only the final round, so nothing changes.
        try {
            Pair<Integer, Integer> accumulated = LLMTokenUtil.calAllTokenCostByUuid(
                    SpringUtil.getBean(StringRedisTemplate.class), uuid);
            if (null != accumulated && (accumulated.getLeft() > 0 || accumulated.getRight() > 0)) {
                inputTokenCount = accumulated.getLeft();
                outputTokenCount = accumulated.getRight();
            }
        } catch (Exception e) {
            log.warn("calculateToken failed to read accumulated usage, falling back to final round, uuid:{}", uuid, e);
        }

        PromptMeta questionMeta = new PromptMeta(inputTokenCount, uuid);
        AnswerMeta answerMeta = AnswerMeta.builder()
                .inputTokens(inputTokenCount)
                .outputTokens(outputTokenCount)
                .uuid(UuidUtil.createShort())
                .build();
        return Pair.of(questionMeta, answerMeta);
    }

    public void deleteCache(String cache) {
        stringRedisTemplate.delete(cache);
    }

    // ==================== 内部方法 ====================

    /**
     * 通过 uuid 从管理器获取 SseEntry（单次查找，避免 TOCTOU）
     * <p>
     * Look up SseEntry by uuid from the manager (single lookup, avoids TOCTOU).
     * </p>
     */
    private static SseEntry getEntry(String uuid) {
        return SpringUtil.getBean(SseManager.class).entries.get(uuid);
    }

    /**
     * 发送错误并完成（内部实现，允许 sseEmitter 为 null）
     * <p>
     * Send error and complete (internal impl, allows null sseEmitter).
     * </p>
     */
    private void doSendErrorAndComplete(long userId, String sseUuid, SseEmitter sseEmitter, String errorMsg) {
        if (sseEmitter == null) {
            decActiveSseCount(userId, sseUuid);
            return;
        }
        SseEntry entry = entries.get(sseUuid);
        if (entry == null) {
            // Emitter not yet registered (e.g. rejected by checkOrComplete before startSse).
            // Send error directly so the frontend still gets a response.
            try {
                sseEmitter.send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.ERROR).data(Objects.toString(errorMsg, "")));
            } catch (IOException e) {
                log.warn("sendErrorAndComplete userId:{},errorMsg:{}", userId, errorMsg);
            } finally {
                completeQuietly(sseEmitter, sseUuid);
            }
            decActiveSseCount(userId, sseUuid);
            return;
        }
        try {
            entry.emitter().send(SseEmitter.event().name(ZhiMeshConstant.SSEEventName.ERROR).data(Objects.toString(errorMsg, "")));
        } catch (Exception e) {
            log.warn("sendErrorAndComplete userId:{},errorMsg:{}", userId, errorMsg, e);
        } finally {
            completeQuietly(entry.emitter(), sseUuid);
            unregister(sseUuid);
        }
    }

    /**
     * Close a stream after a non-DONE terminal event (for example workflow cancellation).
     * The caller must send the explicit terminal event before invoking this method.
     */
    public void close(long userId, String sseUuid) {
        SseEntry entry = entries.get(sseUuid);
        if (entry == null) {
            log.debug("sseEmitter already completed or not found,userId:{}", userId);
            return;
        }
        try {
            entry.emitter().complete();
        } catch (Exception e) {
            log.debug("close SSE error,userId:{}", userId, e);
        } finally {
            unregister(sseUuid);
        }
    }

    private void completeQuietly(SseEmitter sseEmitter, String sseUuid) {
        try {
            sseEmitter.complete();
        } catch (Exception e) {
            log.debug("SseEmitter completion ignored for sseUuid:{}", sseUuid, e);
        }
    }

    /**
     * 递减用户活跃 SSE 并发计数
     * <p>
     * Decrement user active SSE concurrency count (Redis SET SREM, idempotent).
     * </p>
     */
    private void decActiveSseCount(long userId, String sseUuid) {
        String activeKey = MessageFormat.format(RedisKeyConstant.USER_ACTIVE_SSE_COUNT, userId);
        stringRedisTemplate.opsForSet().remove(activeKey, sseUuid);
    }
}
