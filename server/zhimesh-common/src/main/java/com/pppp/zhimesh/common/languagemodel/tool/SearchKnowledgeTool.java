package com.pppp.zhimesh.common.languagemodel.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.util.CharacterChatHelper;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 search_knowledge：让 Agentic 角色在工具循环中自主检索当前角色绑定的知识库
 * <p>
 * 非 Spring bean，由聊天入口按请求构造（纯 ToolContext 驱动）：检索依赖全部从
 * {@link ToolContext#getRagContext()} 获取。鉴权以 ragContext.filteredKb 为准——
 * kbHint 只能按标题模糊缩小该范围，绝不扩大（模型无法点名越权库）；命中片段回流
 * {@link ToolContext#getRefCollector()} 供调用方合并进引用弹窗。
 * <p>
 * Builtin search_knowledge tool: lets an agentic character autonomously retrieve
 * the character-bound knowledge bases from within the tool-calling loop.
 * <p>
 * Not a Spring bean; constructed per request by the chat entry and driven purely
 * by {@link ToolContext}: all retrieval dependencies come from
 * {@link ToolContext#getRagContext()}. Authorization is anchored at
 * ragContext.filteredKb — kbHint may only narrow that scope by fuzzy title
 * matching, never widen it (the model cannot name its way into an unauthorized
 * KB); hit wrappers flow back into {@link ToolContext#getRefCollector()} for the
 * caller to merge into the citation popup.
 */
public class SearchKnowledgeTool implements ToolExecutor {

    /** 工具名（按名匹配执行的唯一标识）/ Tool name (unique key for by-name resolution) */
    public static final String NAME = "search_knowledge";

    /** 空结果文案：模型可据此决定换 query 重试 / Empty-result text: the model may retry with a different query */
    static final String EMPTY_RESULT_TEXT = "未检索到相关内容";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("检索当前角色绑定的知识库，返回与查询最相关的片段及其来源标题。"
                    + "一次调用只检索一个 query；需要对比多个主题时应分别调用，未检索到相关内容时可换一种问法重试。"
                    + "kbHint 为可选的知识库标题模糊提示，仅用于缩小检索范围。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("query", "检索问题，一次调用一个 query")
                    .addStringProperty("kbHint", "可选：知识库标题的模糊过滤提示，仅用于缩小检索范围")
                    .required("query")
                    .build())
            .build();

    @Override
    public ToolSpecification spec() {
        return SPEC;
    }

    @Override
    public String execute(ToolExecutionRequest request, ToolContext context) throws Exception {
        JsonNode arguments = parseArguments(request);
        String query = textArgument(arguments, "query");
        String kbHint = textArgument(arguments, "kbHint");
        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("search_knowledge requires a non-blank 'query' argument");
        }
        ToolRagContext ragContext = null == context ? null : context.getRagContext();
        if (null == ragContext) {
            // 无检索接线（未构造 ToolRagContext）即无可检索对象，直接返回空结果文案
            // Without retrieval wiring (no ToolRagContext built) there is nothing to search
            return EMPTY_RESULT_TEXT;
        }
        List<KbInfoResp> filteredKb = null == ragContext.getFilteredKb()
                ? List.of() : ragContext.getFilteredKb();
        // kbHint 只缩小鉴权范围、不扩大：过滤结果恒为 filteredKb 的子集
        // kbHint only narrows the authorized scope, never widens it: the result is always a subset of filteredKb
        List<KbInfoResp> scopedKb = filterByKbHint(filteredKb, kbHint);
        List<RetrieverWrapper> hits = doRetrieve(null == context ? null : context.getCharacterId(),
                scopedKb, ragContext.getLlmService(), ragContext.getEmbeddingModel(),
                query, null == context ? null : context.getMemoryId());
        List<RetrieverWrapper> hitWrappers = hits == null ? List.of() : hits.stream()
                .filter(wrapper -> null != wrapper.getResponse() && !wrapper.getResponse().isEmpty())
                .toList();
        if (null != context && null != context.getRefCollector()) {
            context.getRefCollector().addAll(hitWrappers);
        }
        if (hitWrappers.isEmpty()) {
            return EMPTY_RESULT_TEXT;
        }
        return formatHits(hitWrappers, filteredKb);
    }

    /**
     * 按知识库标题模糊过滤（忽略大小写，contains 语义）；kbHint 为空时原样返回。
     * null 标题无法命中任何提示，被剔除
     * <p>
     * Fuzzy-filter KBs by title (case-insensitive contains); returns the list
     * unchanged when kbHint is blank. A null title can never match a hint and is
     * dropped.
     */
    static List<KbInfoResp> filterByKbHint(List<KbInfoResp> filteredKb, String kbHint) {
        if (StringUtils.isBlank(kbHint)) {
            return filteredKb;
        }
        String hint = kbHint.toLowerCase();
        return filteredKb.stream()
                .filter(kb -> null != kb.getTitle() && kb.getTitle().toLowerCase().contains(hint))
                .toList();
    }

    /**
     * 把命中片段拼装为紧凑文本：每片段一行 {@code [来源: {标题}] {内容}}，按检索返回顺序
     * （已相关度排序）；知识库片段经 kb_uuid 元数据映射来源标题，记忆通道使用固定标签
     * <p>
     * Render hits as compact text: one line per fragment in the shape
     * {@code [来源: {title}] {text}}, in retrieval order (already relevance
     * ranked); KB fragments resolve their source title from the kb_uuid metadata,
     * memory channels use fixed labels.
     */
    static String formatHits(List<RetrieverWrapper> hitWrappers, List<KbInfoResp> filteredKb) {
        Map<String, String> titlesByUuid = new LinkedHashMap<>();
        for (KbInfoResp kb : filteredKb) {
            if (null != kb && StringUtils.isNotBlank(kb.getUuid()) && StringUtils.isNotBlank(kb.getTitle())) {
                titlesByUuid.put(kb.getUuid(), kb.getTitle());
            }
        }
        StringBuilder rendered = new StringBuilder();
        for (RetrieverWrapper wrapper : hitWrappers) {
            for (Content content : wrapper.getResponse()) {
                if (!rendered.isEmpty()) {
                    rendered.append('\n');
                }
                rendered.append("[来源: ").append(sourceLabel(wrapper, content, titlesByUuid))
                        .append("] ").append(content.textSegment().text());
            }
        }
        return rendered.toString();
    }

    /** 解析单个片段的来源标签：知识库→标题，记忆通道→固定标签 / Resolve the source label of one fragment */
    private static String sourceLabel(RetrieverWrapper wrapper, Content content,
                                      Map<String, String> titlesByUuid) {
        String retrieveType = wrapper.getContentFrom();
        if (ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY.equals(retrieveType)) {
            return "角色记忆";
        }
        if (ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC.equals(retrieveType)) {
            return "情景记忆";
        }
        String kbUuid = content.textSegment().metadata().getString(ZhiMeshConstant.MetadataKey.KB_UUID);
        String title = StringUtils.isBlank(kbUuid) ? null : titlesByUuid.get(kbUuid);
        return StringUtils.isNotBlank(title) ? title : "知识库";
    }

    private static JsonNode parseArguments(ToolExecutionRequest request) {
        String arguments = null == request ? null : request.arguments();
        if (StringUtils.isBlank(arguments)) {
            throw new IllegalArgumentException("search_knowledge requires JSON arguments with a 'query' field");
        }
        JsonNode parsed = JsonUtil.toJsonNode(arguments);
        if (null == parsed || !parsed.isObject()) {
            throw new IllegalArgumentException("search_knowledge arguments must be a JSON object");
        }
        return parsed;
    }

    private static String textArgument(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return null == node || node.isNull() ? null : node.asText();
    }

    /**
     * 包级可覆写的检索入口：生产路径委托 {@link CharacterChatHelper#retrieve}，
     * 单测覆写以捕获传入参数并回放固定片段，生产路径保持不变
     * <p>
     * Package-overridable retrieval entry: production delegates to
     * {@link CharacterChatHelper#retrieve}; unit tests override it to capture
     * the arguments and replay canned fragments, keeping the production path intact.
     */
    List<RetrieverWrapper> doRetrieve(Long characterId, List<KbInfoResp> filteredKb,
                                      AbstractLLMService llmService, EmbeddingModel embeddingModel,
                                      String queryText, String memoryId) {
        // query 由 Agentic 工具循环中的模型带全上下文自拟：跳过 ContextualQueryRewriter
        // 改写（重复 LLM 调用），见 CharacterChatHelper.resolveRetrievalQuery
        // The query is authored by the agentic loop's model with full context:
        // skip the contextual rewrite (a redundant LLM call), see
        // CharacterChatHelper.resolveRetrievalQuery
        return CharacterChatHelper.retrieve(characterId, filteredKb, llmService, embeddingModel,
                queryText, memoryId, true);
    }
}
