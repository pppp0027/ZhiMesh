package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import com.pppp.zhimesh.common.vo.ToolCallTrace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 请求级工具执行上下文：一次聊天请求构造一次，贯穿所有递归工具调用轮次，
 * 供内置工具获取当前用户/角色等信息并收集执行轨迹
 * <p>
 * Request-scoped tool execution context: constructed once per chat request and
 * shared across all recursive tool-call rounds. It lets builtin tools access the
 * current user/character and collects execution traces.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolContext {

    private User user;

    /** 当前角色ID，由调用方按请求填充 / Current character id, populated by the caller per request */
    private Long characterId;

    private String memoryId;

    /** 本次请求的工具调用轨迹收集器 / Collector of tool-call traces for this request */
    private List<ToolCallTrace> toolTraces;

    /**
     * RAG 检索上下文（鉴权后的知识库范围 + 检索依赖），由聊天入口按请求构造；
     * 内置检索工具（如 search_knowledge）从这里取检索依赖，filteredKb 即合法检索范围
     * <p>
     * RAG retrieval context (authorization-filtered KB scope plus retrieval
     * dependencies), constructed per request by the chat entry; builtin retrieval
     * tools (e.g. search_knowledge) read their dependencies from here, and
     * filteredKb is the legal retrieval scope.
     */
    private ToolRagContext ragContext;

    /**
     * 检索命中片段回流收集器（可空）：内置工具检索命中的 RetrieverWrapper 汇聚至此，
     * 供调用方合并进 adi_character_message_ref_*（引用弹窗语义）
     * <p>
     * Optional collector where builtin tools flow back hit RetrieverWrappers,
     * merged by the caller into adi_character_message_ref_* (citation-popup semantics).
     */
    private List<RetrieverWrapper> refCollector;

    /**
     * 挂起结果槽（请求级，volatile——执行器可能在守护线程池上运行）：协作类工具
     * （ask_user 等）execute 时置位，工具循环检测到即走挂起路径——不把工具的占位
     * 返回值当普通结果，也不再递归调模型。一次循环至多一个活跃信号；挂起完成后随
     * 请求结束自然丢弃，跨请求状态由检查点（adi_agent_pending_checkpoint）承载。
     * <p>
     * Suspension slot (request-scoped, volatile — executors may run on the
     * daemon pool): collaborative tools (ask_user etc.) set it in execute, and
     * the tool loop takes the suspension path on sight — the tool's placeholder
     * return value is never treated as a normal result and no further model
     * call happens. At most one live signal per loop; it dies with the request
     * once the suspension wraps up, and cross-request state lives in the
     * checkpoint (adi_agent_pending_checkpoint) instead.
     */
    private volatile SuspensionSignal suspensionSignal;

    /**
     * 本工具链已消耗的挂起次数（跨挂起继承）：恢复轮从检查点的 suspensionCount 续算，
     * 达到 zhimesh.agent.max-suspensions 后循环不再挂起（ask_user 位置返回引导文本）
     * <p>
     * Suspensions already consumed within this tool chain (inherited across
     * suspensions): a resumed run continues from the checkpoint's
     * suspensionCount; once zhimesh.agent.max-suspensions is reached the loop
     * stops suspending (the ask_user slot returns a guidance text instead).
     */
    private int suspensionCount;

    /**
     * 挂起检查点落库回调（可空）：由聊天入口以闭包注入（内部委托
     * PendingCheckpointService），为空 = 本请求不支持挂起（如 blocking 路径），协作类
     * 工具将得到「不支持挂起」引导文本而非真的挂起
     * <p>
     * Checkpoint-persistence callback (nullable): injected as a closure by the
     * chat entry (delegating to PendingCheckpointService inside). Absent means
     * this request cannot suspend (e.g. the blocking path), and collaborative
     * tools then get a "suspension unsupported" guidance text instead of a real
     * suspension.
     */
    private SuspensionCheckpointSink suspensionSink;

    /**
     * 审批批准凭证（可空，仅挂起恢复轮携带）：由恢复装配从消费检查点的 approval_grant
     * 读回（损坏 JSON fail-safe 归 null = 未批准）。需审批 MCP 装饰器据此放行「同工具同
     * 参数」的真实调用。请求级生命周期 = 凭证仅本恢复链有效：普通请求装配全新上下文无
     * 凭证，需审批工具照常重新走审批。字段在异步提交前一次性写入，经执行器提交的
     * happens-before 保证对循环线程可见（与挂起信号不同，循环内无人写它）
     * <p>
     * Approval grant (nullable, carried only by suspension-resumed rounds):
     * read back by the resume assembly from the consumed checkpoint's
     * approval_grant (corrupt JSON fails safe to null = not approved). The
     * approval-required MCP decorator lets the real invocation of "the same
     * tool with the same arguments" through on its basis. The request-scoped
     * lifetime means the grant is valid only within this resume chain: an
     * ordinary request assembles a fresh context with no grant and the
     * approval-required tool goes through approval again. The field is written
     * once before the async submit; the executor submission's happens-before
     * makes it visible to the loop thread (unlike the suspension signal,
     * nothing writes it mid-loop).
     */
    private ApprovalGrant approvalGrant;
}
