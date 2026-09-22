package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.workflow.WorkflowStarter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 内置工具 run_workflow：让 Agentic 角色在工具循环中调用当前用户可见的工作流并取回输出。
 * <p>
 * 非 Spring bean，由聊天入口按请求构造（构造注入）：可见工作流清单（title+uuid+起始节点
 * 文本输入定义）由 ask() 按 mine+public 口径一次查询生成，工具内只做标题精确匹配——
 * 模型无法点名越权调用不可见工作流。执行走 {@link WorkflowStarter#blocking}，被提交到
 * 本工具自建的守护线程上，等待 internalTimeoutMs：超时不取消工作流线程，返回"仍在执行中"
 * 状态摘要而非失败（设计要求）。递归防护：静态 {@link #IN_RUN_WORKFLOW} 标记在 execute
 * 置位（finally 清除）并传播进工作流线程，工作流内再次触发 run_workflow 直接拒绝。
 * 门卫前置：目录携带输入参数清单；必填输入无法通过对话传递（文件/额外文本）或唯一可
 * 投递的必填文本为空时，启动前即拦截并返回可行动指引（手动运行/向用户索取）。
 * <p>
 * Builtin run_workflow tool: lets an agentic character invoke a workflow visible
 * to the current user from within the tool-calling loop and return its output.
 * <p>
 * Not a Spring bean; constructed per request by the chat entry (constructor
 * injection). The visible-workflow catalog (title+uuid+start-node text input
 * definition) is resolved once by ask() under the mine+public scope, and the
 * tool only exact-matches titles — the model cannot name its way into an
 * invisible workflow. Execution delegates to {@link WorkflowStarter#blocking}
 * on a dedicated daemon thread owned by this tool, waiting at most
 * internalTimeoutMs: on timeout the workflow thread is NOT cancelled; the tool
 * returns a "still running" status summary instead of failing (per design).
 * Recursion guard: the static {@link #IN_RUN_WORKFLOW} flag is set in execute
 * (cleared in finally) and propagated onto the workflow thread, so a nested
 * run_workflow triggered from inside the workflow is rejected outright.
 */
public class RunWorkflowTool implements ToolExecutor {

    /** 工具名（按名匹配执行的唯一标识）/ Tool name (unique key for by-name resolution) */
    public static final String NAME = "run_workflow";

    /** 嵌套调用拒绝文案（固定文案，便于识别与测试）/ Fixed rejection text for nested invocation */
    static final String RECURSION_REJECT_TEXT =
            "嵌套工作流调用已被拒绝：工作流内的 Agent 不能再触发 run_workflow，请直接完成任务";

    /**
     * 输出软截断上限：外层 guardrail 的 tool-result-max-chars 默认同值，工具内先行截断，
     * 保证超时状态行等短结果永远完整可见
     * <p>
     * Soft cap on the rendered output; the outer guardrail's
     * tool-result-max-chars defaults to the same value, and the tool truncates
     * first so short status lines (e.g. the timeout summary) always survive.
     */
    static final int OUTPUT_SOFT_MAX_CHARS = 4000;

    /** description 注入的可见标题数上限（防提示词膨胀）/ Cap on catalog titles in the description */
    static final int DESCRIPTION_MAX_TITLES = 20;

    /**
     * 递归防护标记：execute 入口检查、try 中置位、finally 清除；同时传播进本工具启动的
     * 工作流线程（守护线程 runnable 内同样置位/清除），使工作流调用栈上同线程直执行的
     * 再次 run_workflow 被直接拒绝
     * <p>
     * Recursion guard: checked at the execute entry, set in try, cleared in
     * finally; also propagated onto the daemon workflow thread spawned by this
     * tool (set/cleared inside its runnable), so a nested run_workflow executed
     * synchronously on the workflow call stack is rejected outright.
     */
    public static final ThreadLocal<Boolean> IN_RUN_WORKFLOW = new ThreadLocal<>();

    private final WorkflowStarter workflowStarter;
    private final List<WorkflowOption> visibleWorkflows;
    private final long internalTimeoutMs;

    public RunWorkflowTool(WorkflowStarter workflowStarter, List<WorkflowOption> visibleWorkflows,
                           long internalTimeoutMs) {
        this.workflowStarter = workflowStarter;
        this.visibleWorkflows = null == visibleWorkflows ? List.of() : visibleWorkflows;
        this.internalTimeoutMs = internalTimeoutMs;
    }

    /**
     * 可见工作流选项：title+uuid 对、起始节点首个文本输入参数定义，以及完整输入参数清单
     * （目录展示与门卫前置判定依据）；inputParamName 为 null 表示该工作流无文本输入参数
     * （input 无处投递，按空输入运行）
     * <p>
     * One visible-workflow option: a title+uuid pair, the start node's first
     * TEXT input definition, and the full input-param manifest (the catalog
     * display and preflight basis); a null inputParamName means the workflow
     * declares no text input (input has nowhere to go; it runs with no input).
     */
    public record WorkflowOption(String title, String uuid, String inputParamName, Integer inputMaxLength,
                                 String remark, List<WorkflowParamInfo> params) {

        public WorkflowOption {
            params = null == params ? List.of() : List.copyOf(params);
        }

        /**
         * 旧 4 参构造（无备注与参数清单）：门卫前置按"无清单"处理不设防，行为与旧版
         * 一致，存量调用方与测试无需改动
         * <p>
         * Legacy 4-arg constructor (no remark or manifest): preflight treats a
         * missing manifest as "no defense" and behaves exactly like the old
         * version, so existing callers and tests need no change.
         */
        public WorkflowOption(String title, String uuid, String inputParamName, Integer inputMaxLength) {
            this(title, uuid, inputParamName, inputMaxLength, null, List.of());
        }
    }

    /**
     * 起始节点输入参数的清单条目：name 为参数变量名（投递对齐用），title 为展示名
     * （目录与拒绝文案用），type 为 {@link WfIODataTypeEnum} 值，required 为是否必填
     * <p>
     * One start-node input param manifest entry: name is the variable name
     * (delivery alignment), title the display name (catalog and refusal
     * texts), type a {@link WfIODataTypeEnum} value, required its requiredness.
     */
    public record WorkflowParamInfo(String name, String title, Integer type, boolean required) {
    }

    @Override
    public ToolSpecification spec() {
        return ToolSpecification.builder()
                .name(NAME)
                .description(buildDescription())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("workflowTitle", "要调用的工作流标题，必须与可用标题清单中的标题完全一致")
                        .addStringProperty("input", "传给工作流起始节点的文本输入，把用户的完整需求或素材整理进去")
                        .required("workflowTitle")
                        .build())
                .build();
    }

    /**
     * 描述注入当前用户可见工作流目录（最多 {@value #DESCRIPTION_MAX_TITLES} 个，含备注
     * 摘要与输入参数清单），并明确调用纪律：目录让模型在调用前知道每个工作流需要什么
     * 材料、哪些必填输入对话给不了（标注手动运行）；纪律要求必填文本缺失时先向用户索取
     * <p>
     * The description injects the visible-workflow catalog (capped at
     * {@value #DESCRIPTION_MAX_TITLES}, each entry with a remark digest and the
     * input-param manifest) plus the calling discipline: the catalog lets the
     * model know, before calling, what material each workflow needs and which
     * required inputs chat cannot deliver (annotated for manual run); the
     * discipline requires asking the user first when a required text input is
     * missing.
     */
    private String buildDescription() {
        String catalog = visibleWorkflows.stream()
                .map(RunWorkflowTool::formatCatalogEntry)
                .limit(DESCRIPTION_MAX_TITLES)
                .collect(Collectors.joining("\n"));
        return "调用当前用户可见的工作流并返回其执行输出。可用工作流清单（含输入说明）：\n"
                + catalog
                + "\nworkflowTitle 必须与清单中的某个标题完全一致（忽略首尾空格的精确匹配）；"
                + "input 是传给工作流起始节点首个文本输入参数的内容，调用前把用户的完整需求或素材整理进 input，"
                + "清单中标注必填的文本输入内容缺失时应先向用户索取；"
                + "工作流执行可能较慢（包含多步处理），确认确实需要时才调用，不要对同一工作流重复调用。";
    }

    /** 目录条目中备注摘要的最大长度（防提示词膨胀）/ Max length of the remark digest in a catalog entry */
    static final int REMARK_MAX_CHARS = 40;

    /**
     * 目录条目中展示的参数条数上限：超出部分折叠为"等N个输入"（病态工作流不得撑爆
     * 描述）；标题不截断——模型需按原样精确匹配标题
     * <p>
     * Cap on params shown per catalog entry: the rest collapse into "等N个
     * 输入" so a pathological workflow cannot bloat the description; titles
     * are never truncated — the model must exact-match them verbatim.
     */
    static final int MANIFEST_MAX_PARAMS = 6;

    /**
     * 一个工作流 → 一行目录条目：标题、备注摘要（截到 {@value #REMARK_MAX_CHARS} 字符）、
     * 输入参数清单（展示名[类型·必填]，至多 {@value #MANIFEST_MAX_PARAMS} 条）；含对话
     * 无法传递的必填输入时标注手动运行
     * <p>
     * One workflow → one catalog line: title, remark digest (capped at
     * {@value #REMARK_MAX_CHARS}), and the param manifest (display name
     * [type·required], at most {@value #MANIFEST_MAX_PARAMS} entries); workflows
     * with undeliverable required inputs are annotated for manual run.
     */
    static String formatCatalogEntry(WorkflowOption option) {
        StringBuilder line = new StringBuilder("- ").append(option.title()).append("：");
        if (StringUtils.isNotBlank(option.remark())) {
            line.append(StringUtils.abbreviate(option.remark().trim(), REMARK_MAX_CHARS)).append("；");
        }
        if (option.params().isEmpty()) {
            line.append("无输入参数");
            return line.toString();
        }
        line.append("输入：").append(option.params().stream()
                .limit(MANIFEST_MAX_PARAMS)
                .map(param -> param.title() + "[" + paramTypeLabel(param.type())
                        + (param.required() ? "·必填" : "") + "]")
                .collect(Collectors.joining("、")));
        if (option.params().size() > MANIFEST_MAX_PARAMS) {
            line.append("，等").append(option.params().size()).append("个输入");
        }
        if (null != preflightUndeliverableInputs(option)) {
            line.append("（含对话无法传递的必填输入，需在工作流页面手动运行）");
        }
        return line.toString();
    }

    /**
     * 参数类型的目录展示标签 / Catalog display label for a param type
     */
    static String paramTypeLabel(Integer type) {
        if (WfIODataTypeEnum.TEXT.getValue().equals(type)) {
            return "文本";
        }
        if (WfIODataTypeEnum.NUMBER.getValue().equals(type)) {
            return "数字";
        }
        if (WfIODataTypeEnum.OPTIONS.getValue().equals(type)) {
            return "下拉";
        }
        if (WfIODataTypeEnum.FILES.getValue().equals(type)) {
            return "文件";
        }
        if (WfIODataTypeEnum.BOOL.getValue().equals(type)) {
            return "布尔";
        }
        return "引用";
    }

    /**
     * 门卫前置（不可投递项）：必填输入中凡非"首个文本参数"者——文件、数字、下拉、布尔，
     * 或首个文本参数之外的额外必填文本——都无法通过对话工具传递（一次只能投递一个文本）。
     * 命中即拒绝启动并返回可行动指引（引导用户去工作流页面手动运行），而不是放行后在
     * 引擎处撞上泛化的必填缺失错误（模型无从知道缺什么，自纠错回路断裂）。参数清单为空
     * （旧构造）时不设防，行为与旧版一致。返回 null 表示放行
     * <p>
     * Input preflight (undeliverable items): any required input that is not
     * the first TEXT param — FILE, NUMBER, OPTIONS, BOOL, or an extra required
     * TEXT beyond the first — cannot be delivered through chat (only one text
     * is deliverable per call). On a hit the tool refuses to start and returns
     * actionable guidance (lead the user to run it manually on the workflow
     * page) instead of failing later at the engine gate with a generic
     * missing-input error the model cannot act on (a broken self-correction
     * loop). An empty manifest (legacy constructor) disables the check,
     * preserving the old behavior. Returns null to allow the call through.
     */
    static String preflightUndeliverableInputs(WorkflowOption option) {
        List<String> undeliverable = option.params().stream()
                .filter(param -> param.required() && !isDeliverableParam(param, option.inputParamName()))
                .map(param -> param.title() + "[" + paramTypeLabel(param.type()) + "·必填]")
                .toList();
        if (undeliverable.isEmpty()) {
            return null;
        }
        return "工作流《" + option.title() + "》存在无法通过对话传递的必填输入："
                + String.join("、", undeliverable)
                + "（对话工具一次只能传递一个文本输入，不支持文件等其他类型）。"
                + "请向用户说明并引导其在工作流页面手动运行该工作流，不要再次尝试调用。";
    }

    /**
     * 门卫前置（可补全项）：唯一可投递的必填文本参数存在但 input 为空——这是模型能补全
     * 的缺失，返回"向用户索取"指引（模型可转成对用户的追问）。返回 null 表示放行
     * <p>
     * Input preflight (recoverable): the single deliverable required TEXT
     * param exists but the input is blank — a miss the model can recover from,
     * so return the ask-the-user guidance (relayed as a clarifying question).
     * Returns null to allow the call through.
     */
    static String preflightBlankRequiredInput(WorkflowOption option, String input) {
        if (StringUtils.isNotBlank(input)) {
            return null;
        }
        return option.params().stream()
                .filter(param -> param.required() && isDeliverableParam(param, option.inputParamName()))
                .findFirst()
                .map(param -> "工作流《" + option.title() + "》的必填输入「" + param.title()
                        + "」内容为空。请先向用户索取该输入的完整内容后再调用。")
                .orElse(null);
    }

    /**
     * 可投递判定：参数为文本类型且变量名与 inputParamName 对齐（即 input 的唯一去向）
     * <p>
     * Deliverability: the param is TEXT-typed and its variable name matches
     * inputParamName (the single destination of input).
     */
    private static boolean isDeliverableParam(WorkflowParamInfo param, String inputParamName) {
        return WfIODataTypeEnum.TEXT.getValue().equals(param.type())
                && null != param.name() && param.name().equals(inputParamName);
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        // 嵌套防护在一切参数处理之前：置位状态下直接拒绝，绝不触碰 WorkflowStarter
        // The nested-invocation guard precedes all argument handling: reject
        // outright while the flag is set, never touching WorkflowStarter
        if (Boolean.TRUE.equals(IN_RUN_WORKFLOW.get())) {
            return RECURSION_REJECT_TEXT;
        }
        JsonNode arguments = parseArguments(request);
        String workflowTitle = textArgument(arguments, "workflowTitle");
        if (StringUtils.isBlank(workflowTitle)) {
            throw new IllegalArgumentException("run_workflow requires a non-blank 'workflowTitle' argument");
        }
        WorkflowOption option = resolveByTitle(workflowTitle.trim());
        User user = null == context ? null : context.getUser();
        if (null == user) {
            throw new IllegalArgumentException("run_workflow requires the current user in the tool context");
        }
        String input = StringUtils.trimToEmpty(textArgument(arguments, "input"));
        // 门卫前置先于一切启动动作：不可投递的必填输入（文件/额外文本等）与可补全的空
        // 必填文本都在此拦截，返回可行动指引（手动运行/向用户索取），而不是放行后在
        // 引擎处撞上泛化的必填缺失错误
        // Preflight precedes any start action: undeliverable required inputs
        // (files / extra texts) and the recoverable blank required text are
        // intercepted here with actionable guidance (manual run / ask the
        // user) instead of failing later at the engine gate with a generic
        // missing-input error
        String refusal = preflightUndeliverableInputs(option);
        if (null == refusal) {
            refusal = preflightBlankRequiredInput(option, input);
        }
        if (null != refusal) {
            return refusal;
        }
        List<ObjectNode> userInputs = buildUserInputs(option, input);

        IN_RUN_WORKFLOW.set(Boolean.TRUE);
        try {
            return runWorkflow(option, user, userInputs);
        } finally {
            IN_RUN_WORKFLOW.remove();
        }
    }

    /**
     * 在独立守护线程上执行工作流并做有界等待：完成 → 返回输出摘要；超时 → 不取消、
     * 不中断工作流线程，返回"仍在执行中"状态摘要（设计要求超时不按失败处理）；
     * internalTimeoutMs 由调用方取 tool-timeout-ms - 5000（下限 1s），必须始终先于
     * 外层 guardrail 超时返回，避免池线程等待被外层 cancel(true) 打断
     * <p>
     * Run the workflow on a dedicated daemon thread with bounded waiting:
     * completion → output summary; timeout → the workflow thread is neither
     * cancelled nor interrupted and a "still running" summary is returned
     * (timeouts are not failures by design); internalTimeoutMs is derived by
     * the caller as tool-timeout-ms - 5000 (floor 1s) and must ALWAYS fire
     * before the outer guardrail timeout, keeping the pool thread's wait safe
     * from the outer cancel(true).
     */
    private String runWorkflow(WorkflowOption option, User user, List<ObjectNode> userInputs)
            throws InterruptedException {
        AtomicReference<Map<String, Object>> resultHolder = new AtomicReference<>();
        AtomicReference<Throwable> errorHolder = new AtomicReference<>();
        Thread workflowThread = new Thread(() -> {
            // 递归标记传播到工作流线程：其调用栈上的同步工具执行同样会被拒绝
            // Propagate the recursion flag onto the workflow thread so a
            // synchronous tool execution on its call stack is rejected too
            IN_RUN_WORKFLOW.set(Boolean.TRUE);
            try {
                resultHolder.set(workflowStarter.blocking(user, option.uuid(), userInputs));
            } catch (Throwable t) {
                errorHolder.set(t);
            } finally {
                IN_RUN_WORKFLOW.remove();
            }
        }, "run-workflow-" + option.uuid());
        workflowThread.setDaemon(true);
        workflowThread.start();
        workflowThread.join(Math.max(1L, internalTimeoutMs));

        Map<String, Object> result = resultHolder.get();
        if (null != result) {
            return renderResult(option, result);
        }
        Throwable error = errorHolder.get();
        if (null != error) {
            throw new IllegalArgumentException(
                    "工作流《" + option.title() + "》执行失败：" + error.getMessage(), error);
        }
        long waitedSeconds = Math.max(1L, internalTimeoutMs / 1000L);
        return "工作流《" + option.title() + "》仍在执行中（已等待 " + waitedSeconds
                + "s），本次未拿到最终输出，可稍后在工作流页面查看执行记录";
    }

    /**
     * 把工具入参 input 包装成 /workflow/run 前端发送的 ObjectNode 形状：
     * {@code [{"name": <起始节点文本参数名>, "content": {"type": 1, "value": <文本>}}]}；
     * 超出参数定义 maxLength 时先行硬截断（起始节点校验超长直接判非法）；无文本输入
     * 定义时返回空列表
     * <p>
     * Wrap the input argument into the ObjectNode shape the /workflow/run
     * frontend sends: {@code [{"name": <start-node text param>,
     * "content": {"type": 1, "value": <text>}}]}; values exceeding the
     * definition's maxLength are hard-truncated first (the start node rejects
     * oversized values); returns an empty list when no TEXT input is defined.
     */
    static List<ObjectNode> buildUserInputs(WorkflowOption option, String input) {
        if (StringUtils.isBlank(option.inputParamName())) {
            return List.of();
        }
        String value = null == input ? "" : input;
        if (null != option.inputMaxLength() && value.length() > option.inputMaxLength()) {
            value = value.substring(0, option.inputMaxLength());
        }
        ObjectNode userInput = JsonUtil.createObjectNode();
        userInput.put("name", option.inputParamName());
        ObjectNode content = userInput.putObject("content");
        content.put("type", WfIODataTypeEnum.TEXT.getValue());
        content.put("value", value);
        return List.of(userInput);
    }

    /**
     * 渲染 blocking 结果：成功形态 {task_id, status:"completed", outputs} → 输出文本 +
     * 状态行；失败形态（无 status、仅 message）转 IllegalArgumentException 交由循环层
     * 记失败轨迹
     * <p>
     * Render the blocking result: the success shape {task_id,
     * status:"completed", outputs} becomes output text plus a status line; the
     * failure shape (no status, only message) is rethrown as an
     * IllegalArgumentException so the loop records a failed trace.
     */
    private String renderResult(WorkflowOption option, Map<String, Object> result) {
        Object status = result.get("status");
        if (null == status) {
            Object message = result.get("message");
            throw new IllegalArgumentException("工作流《" + option.title() + "》执行失败："
                    + (null != message ? message : "unknown error"));
        }
        String output = renderOutputs(result.get("outputs"));
        return "工作流《" + option.title() + "》执行完成，输出如下：\n" + output;
    }

    /**
     * outputs 为 {@code {<参数名>: {type, title, value}}} 的 ObjectNode：按参数顺序拼接
     * 各 value（数组以换页连接，与前端 getFinalOutputText 口径一致），整体软截断到
     * {@value #OUTPUT_SOFT_MAX_CHARS} 字符并附 [truncated] 尾注
     * <p>
     * outputs is an ObjectNode of {@code {<param>: {type, title, value}}}:
     * join the values in order (array values newline-joined, mirroring the
     * frontend's getFinalOutputText), then soft-truncate the whole text to
     * {@value #OUTPUT_SOFT_MAX_CHARS} chars with a [truncated] marker.
     */
    static String renderOutputs(Object outputs) {
        List<String> parts = new ArrayList<>();
        if (outputs instanceof JsonNode outputsNode && outputsNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = outputsNode.fields(); it.hasNext(); ) {
                JsonNode content = it.next().getValue();
                JsonNode value = null != content && content.isObject() ? content.get("value") : null;
                if (null == value || value.isNull()) {
                    continue;
                }
                if (value.isArray()) {
                    List<String> elements = new ArrayList<>();
                    value.forEach(element -> elements.add(element.asText()));
                    if (!elements.isEmpty()) {
                        parts.add(String.join("\n", elements));
                    }
                } else if (value.isObject()) {
                    parts.add(value.toString());
                } else {
                    parts.add(value.asText());
                }
            }
        } else if (null != outputs) {
            parts.add(String.valueOf(outputs));
        }
        String output = parts.isEmpty() ? "(无输出)" : String.join("\n\n", parts);
        return softTruncate(output);
    }

    /** 软截断到 maxChars 并追加 [truncated] 尾注 / Soft-truncate with a [truncated] marker */
    static String softTruncate(String text) {
        if (null == text || text.length() <= OUTPUT_SOFT_MAX_CHARS) {
            return text;
        }
        return text.substring(0, OUTPUT_SOFT_MAX_CHARS) + "\n...[truncated]";
    }

    /**
     * 按标题精确匹配可见清单（忽略首尾空白）；匹配不到时抛出含相近标题与可见清单提示的
     * IllegalArgumentException，由循环层转为失败结果消息
     * <p>
     * Exact-match the title against the visible catalog (trimming surrounding
     * whitespace); a miss throws an IllegalArgumentException carrying
     * near-title hints and the visible catalog, converted into a failure
     * result message by the loop.
     */
    WorkflowOption resolveByTitle(String title) {
        for (WorkflowOption option : visibleWorkflows) {
            if (null != option && title.equals(StringUtils.trimToEmpty(option.title()))) {
                return option;
            }
        }
        List<String> nearTitles = visibleWorkflows.stream()
                .map(WorkflowOption::title)
                .filter(visible -> StringUtils.isNotBlank(visible)
                        && (visible.toLowerCase().contains(title.toLowerCase())
                        || title.toLowerCase().contains(visible.toLowerCase())))
                .limit(3)
                .toList();
        String catalog = visibleWorkflows.stream()
                .map(WorkflowOption::title)
                .filter(StringUtils::isNotBlank)
                .limit(DESCRIPTION_MAX_TITLES)
                .collect(Collectors.joining("、"));
        String hint = nearTitles.isEmpty()
                ? ""
                : "相近标题：" + String.join("、", nearTitles) + "。";
        throw new IllegalArgumentException("未找到标题为「" + title + "」的可调用工作流，只能调用当前用户可见的工作流。"
                + hint + "可用工作流标题：" + catalog + "。");
    }

    private static JsonNode parseArguments(ToolExecutionRequest request) {
        String arguments = null == request ? null : request.arguments();
        if (StringUtils.isBlank(arguments)) {
            throw new IllegalArgumentException("run_workflow requires JSON arguments with a 'workflowTitle' field");
        }
        JsonNode parsed = JsonUtil.toJsonNode(arguments);
        if (null == parsed || !parsed.isObject()) {
            throw new IllegalArgumentException("run_workflow arguments must be a JSON object");
        }
        return parsed;
    }

    private static String textArgument(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return null == node || node.isNull() ? null : node.asText();
    }
}
