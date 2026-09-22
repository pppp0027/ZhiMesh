package com.pppp.zhimesh.common.vo;

import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.languagemodel.tool.ToolContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SseAskParam {

    private User user;
    //请求标识,如:知识库的记录uuid,搜索记录uuid
    private String uuid;
    private String modelPlatform;
    private String modelName;
    private String regenerateQuestionUuid;
    /**
     * 2:text,3:audio
     */
    private Integer answerContentType;
    private String voice;
    /**
     * SSE 请求标识，用于从注册中心获取 SseEmitter
     * <p>
     * SSE request identifier, used to look up SseEmitter from the registry.
     * </p>
     */
    private String sseUuid;
    /**
     * 创建LLM时用到的属性，非必填
     */
    private ChatModelBuilderProperties modelProperties;

    /**
     * 进行http请求时最终提交给LLM的信息，必填
     */
    private ChatModelRequest httpRequestParams;

    /**
     * 请求级工具执行上下文（可空）：非空时 streamingChat 优先使用它而不是自建，
     * 让聊天入口构造的检索上下文/轨迹收集器贯通整个工具循环；为空时保持原有自建行为
     * （存量调用方零变化）。
     * <p>
     * Request-scoped tool execution context (nullable): when non-null,
     * streamingChat uses it instead of building its own, so the retrieval
     * context and trace collectors built by the chat entry flow through the
     * whole tool loop; when null the original self-built behavior is kept
     * (zero change for existing callers).
     */
    private ToolContext toolContext;
}
