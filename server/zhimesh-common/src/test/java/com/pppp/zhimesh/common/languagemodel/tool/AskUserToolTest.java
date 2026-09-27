package com.pppp.zhimesh.common.languagemodel.tool;

import com.pppp.zhimesh.common.enums.PendingCheckpointKind;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ask_user 协作工具的契约单测：spec 形态（question 必填 / options 可选字符串数组 /
 * description 引导单独调用）、execute 的挂起信号写入与参数校验（空 question/坏 JSON/
 * 无上下文全部显式失败）、options 解析的边界（非文本跳过、空归 null）。
 * <p>
 * Contract tests for the ask_user collaborative tool: the spec shape (question
 * required / options an optional string array / the description nudging solo
 * invocation), execute's suspension-signal write and argument validation (blank
 * question / bad JSON / missing context all fail explicitly), and the options
 * parsing edges (non-textual skipped, empty collapses to null).
 */
class AskUserToolTest {

    @Test
    void specDeclaresQuestionRequiredAndOptionsAsOptionalStringArray() {
        JsonObjectSchema parameters = (JsonObjectSchema) new AskUserTool().spec().parameters();

        assertThat(new AskUserTool().spec().name()).isEqualTo(AskUserTool.NAME);
        assertThat(parameters.required()).containsExactly("question");
        assertThat(parameters.properties()).containsKeys("question", "options");
        assertThat(parameters.properties().get("options")).isInstanceOf(JsonArraySchema.class);
        // description 引导“单独调用”，从源头减少同轮多协作请求
        // The description nudges "call it alone", reducing same-round multiple
        // collaborative requests at the source
        assertThat(new AskUserTool().spec().description()).contains("单独调用本工具");
    }

    @Test
    void executeWritesSuspensionSignalAndReturnsPlaceholder() throws Exception {
        ToolContext context = ToolContext.builder().build();

        String result = new AskUserTool().execute(request("id-ask-1",
                "{\"question\":\"你负责哪个部门？\",\"options\":[\"财务\",\"法务\"]}"), context);

        assertThat(result).isEqualTo("[ask_user 已挂起，等待用户回复]");
        SuspensionSignal signal = context.getSuspensionSignal();
        assertThat(signal).isNotNull();
        assertThat(signal.getKind()).isEqualTo(PendingCheckpointKind.ASK_USER);
        assertThat(signal.getToolName()).isEqualTo(AskUserTool.NAME);
        assertThat(signal.getRequestId()).isEqualTo("id-ask-1");
        assertThat(signal.getQuestion()).isEqualTo("你负责哪个部门？");
        assertThat(signal.getOptions()).containsExactly("财务", "法务");
        assertThat(signal.getCheckpointUuid()).isNull();
    }

    @Test
    void collaborativeMarkerIsTrue() {
        assertThat(new AskUserTool().isCollaborative()).isTrue();
    }

    @Test
    void blankQuestionFailsExplicitlyWithoutTouchingContext() {
        ToolContext context = ToolContext.builder().build();

        assertThatThrownBy(() -> new AskUserTool().execute(request("id-1",
                "{\"question\":\"   \"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
        assertThat(context.getSuspensionSignal()).isNull();
    }

    @Test
    void blankOrNonObjectArgumentsFailExplicitly() {
        assertThatThrownBy(() -> new AskUserTool().execute(request("id-1", ""), ToolContext.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AskUserTool().execute(request("id-1", "\"just a string\""),
                ToolContext.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingContextFailsExplicitlyInsteadOfFakingSuccess() {
        assertThatThrownBy(() -> new AskUserTool().execute(request("id-1", "{\"question\":\"Q?\"}"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool context");
    }

    @Test
    void optionsParsingSkipsNonTextualAndCollapsesEmptyToNull() throws Exception {
        assertThat(executeOptions("{\"question\":\"Q?\",\"options\":[]}")).isNull();
        assertThat(executeOptions("{\"question\":\"Q?\"}")).isNull();
        assertThat(executeOptions("{\"question\":\"Q?\",\"options\":\"not-an-array\"}")).isNull();
        // 非文本元素跳过，其余保留原顺序
        // Non-textual elements are skipped, the rest keep their order
        assertThat(executeOptions("{\"question\":\"Q?\",\"options\":[1,\"B\",true,\"A\"]}"))
                .containsExactly("B", "A");
    }

    private List<String> executeOptions(String arguments) throws Exception {
        ToolContext context = ToolContext.builder().build();
        new AskUserTool().execute(request("id-1", arguments), context);
        return context.getSuspensionSignal().getOptions();
    }

    private static ToolExecutionRequest request(String id, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(AskUserTool.NAME).arguments(arguments).build();
    }
}
