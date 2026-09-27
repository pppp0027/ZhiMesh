package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import com.pppp.zhimesh.common.util.JsonUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置协作工具 ask_user：让 Agentic 角色在缺少必要信息时向当前对话的用户提出澄清问题，
 * 并把工具循环挂起等待用户下一轮回复（挂起-恢复内核见 AbstractLLMService 的挂起分支）。
 * <p>
 * 非 Spring bean，由聊天入口按请求构造（纯 ToolContext 驱动）。execute 校验 question
 * 非空后把挂起信号（question/options 载荷）写入 ToolContext 的挂起结果槽并返回占位文本
 * ——占位文本不会被使用：循环检测到挂起信号即走挂起路径（不递归调模型），挂起请求的
 * ToolExecutionResultMessage 由恢复轮以用户答复原文注入。工具 description 同时引导
 * 「能默认的维度自行假设并说明、只问影响任务方向的关键缺口（带建议默认值）」与
 * 「需要用户输入时单独调用，不要与其他工具同轮」，从源头减少追问与同轮多协作请求。
 * <p>
 * Builtin collaborative tool ask_user: lets an agentic character ask the user a
 * clarifying question and suspend the tool loop until the user's next message
 * (the suspend/resume kernel lives in AbstractLLMService's suspension branch).
 * <p>
 * Not a Spring bean; constructed per request by the chat entry and driven purely
 * by ToolContext. After validating a non-blank question, execute writes the
 * suspension signal (question/options payload) into the ToolContext suspension
 * slot and returns a placeholder text — the placeholder is never used: the loop
 * takes the suspension path as soon as it sees the signal (no further model
 * call), and the suspended request's ToolExecutionResultMessage is injected on
 * resume with the user's raw answer. The tool description also nudges the model
 * to default the dimensions it can reasonably assume (stating the assumptions)
 * and only ask about direction-changing gaps with a suggested default, calling
 * it alone rather than alongside other tools in the same round.
 */
public class AskUserTool implements ToolExecutor {

    /** 工具名（按名匹配执行的唯一标识）/ Tool name (unique key for by-name resolution) */
    public static final String NAME = "ask_user";

    /**
     * 挂起占位文本：挂起路径不会把它当工具结果使用（循环见信号即短路）；仅当某个
     * 非标准调用方绕过挂起内核直接执行本工具时作为兜底返回
     * <p>
     * Suspension placeholder: the suspension path never treats it as the tool
     * result (the loop short-circuits on the signal); it is a fallback only for
     * a non-standard caller executing this tool outside the suspension kernel.
     */
    static final String SUSPENDED_PLACEHOLDER_TEXT = "[ask_user 已挂起，等待用户回复]";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("需要向用户澄清或补充信息时，向用户提出一个问题并暂停等待其回复。"
                    + "仅在缺少会改变任务方向、无法自行合理默认的关键信息时调用本工具；每次只提出当前最关键的一个问题（确需确认时至多两个），并在问题中给出你建议的默认值供用户确认或修改。"
                    + "其余缺失维度不要追问：自行采用合理默认假设，并在最终回答开头简要列出所做假设。"
                    + "需要用户输入时应单独调用本工具，不要与其他工具在同一轮并行调用。"
                    + "options 为可选的候选项列表，用户点选即视为回复该项文本。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("question", "要向用户提出的一个关键澄清问题，一句话说清需要用户确认什么，并附上你的建议默认值")
                    .addProperty("options", JsonArraySchema.builder()
                            .items(JsonStringSchema.builder().build())
                            .build())
                    .required("question")
                    .build())
            .build();

    @Override
    public ToolSpecification spec() {
        return SPEC;
    }

    /**
     * 协作类工具标记：execute 产出挂起信号而非普通文本结果，循环据此走挂起分支
     * <p>
     * Collaborative-tool marker: execute yields a suspension signal instead of a
     * plain text result, which routes the loop into the suspension branch.
     */
    @Override
    public boolean isCollaborative() {
        return true;
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        JsonNode arguments = parseArguments(request);
        String question = textArgument(arguments, "question");
        if (StringUtils.isBlank(question)) {
            throw new IllegalArgumentException("ask_user requires a non-blank 'question' argument");
        }
        if (null == context) {
            // 无请求级上下文即无处写挂起信号：明确失败，绝不假装提问成功
            // Without a request-scoped context there is nowhere to put the
            // suspension signal: fail explicitly, never fake a successful ask
            throw new IllegalStateException("ask_user requires a tool context to suspend the loop");
        }
        context.setSuspensionSignal(SuspensionSignal.builder()
                .kind(PendingCheckpointKind.ASK_USER)
                .toolName(NAME)
                .requestId(null == request ? null : request.id())
                .question(question)
                .options(stringListArgument(arguments, "options"))
                .build());
        return SUSPENDED_PLACEHOLDER_TEXT;
    }

    private static JsonNode parseArguments(ToolExecutionRequest request) {
        String arguments = null == request ? null : request.arguments();
        if (StringUtils.isBlank(arguments)) {
            throw new IllegalArgumentException("ask_user requires JSON arguments with a 'question' field");
        }
        JsonNode parsed = JsonUtil.toJsonNode(arguments);
        if (null == parsed || !parsed.isObject()) {
            throw new IllegalArgumentException("ask_user arguments must be a JSON object");
        }
        return parsed;
    }

    private static String textArgument(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return null == node || node.isNull() ? null : node.asText();
    }

    /**
     * 读取可选的字符串数组参数：null / 非数组 / 空数组统一返回 null（载荷与 agent_question
     * 事件都不携带空选项）；非文本元素跳过
     * <p>
     * Read an optional string-array argument: null / non-array / empty all map
     * to null (neither the payload nor the agent_question event carries empty
     * options); non-textual elements are skipped.
     */
    private static List<String> stringListArgument(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (null == node || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (null != item && item.isTextual()) {
                values.add(item.asText());
            }
        }
        return CollectionUtils.isEmpty(values) ? null : values;
    }
}
