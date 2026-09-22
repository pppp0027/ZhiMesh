package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.rag.intent.ContextualQueryRewriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索查询解析的分流验证：常规路径经 ContextualQueryRewriter 改写；Agentic 工具路径
 * 的 query 由循环中的模型带全上下文自拟，必须跳过改写（改写器本身是一次 LLM 调用，
 * 对模型自拟 query 属于重复消费，且该调用不计费——评审 M-3）。
 * <p>
 * Routing of retrieval-query resolution: the regular path rewrites through
 * ContextualQueryRewriter; the agentic tool path receives a query authored by
 * the loop's model with full context and must skip the rewrite (the rewriter is
 * itself an LLM call, redundant for a model-authored query, and unbilled —
 * review finding M-3).
 */
class CharacterChatHelperQueryResolutionTest {

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private ContextualQueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        context = mock(ApplicationContext.class);
        rewriter = mock(ContextualQueryRewriter.class);
        when(context.getBean(ContextualQueryRewriter.class)).thenReturn(rewriter);
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void modelAuthoredQuerySkipsRewriterEntirely() {
        // 模型自拟 query：改写器零接触，query 原样透传
        // A model-authored query: the rewriter is never touched and the query
        // passes through unchanged
        ContextualQueryRewriter.Result result = CharacterChatHelper.resolveRetrievalQuery(
                "模型自拟的检索词", List.of(new KbInfoResp()), "mem-1",
                mock(AbstractLLMService.class), true);

        assertThat(result.retrievalQuery()).isEqualTo("模型自拟的检索词");
        assertThat(result.originalQuery()).isEqualTo("模型自拟的检索词");
        assertThat(result.rewritten()).isFalse();
        verifyNoInteractions(rewriter);
    }

    @Test
    void legacyQueryStillRoutesThroughRewriter() {
        // 常规路径（用户原话）行为不变：照常交给改写器并采用其结果
        // The regular path (raw user wording) is unchanged: it still delegates
        // to the rewriter and adopts its result
        AbstractLLMService llmService = mock(AbstractLLMService.class);
        ContextualQueryRewriter.Result rewritten = new ContextualQueryRewriter.Result(
                "用户原话", "结合上下文改写后的完整检索式", true, true, "context-aware rewrite");
        when(rewriter.resolve("用户原话", "mem-1", llmService)).thenReturn(rewritten);

        ContextualQueryRewriter.Result result = CharacterChatHelper.resolveRetrievalQuery(
                "用户原话", List.of(new KbInfoResp()), "mem-1", llmService, false);

        assertThat(result).isSameAs(rewritten);
        verify(rewriter).resolve("用户原话", "mem-1", llmService);
    }

    @Test
    void emptyKnowledgeBaseSkipsRewriterRegardlessOfAuthorship() {
        // 无可检索知识库时语义不变：两种来源都不触碰改写器
        // With no retrievable KB the semantics are unchanged: neither source
        // touches the rewriter
        ContextualQueryRewriter.Result result = CharacterChatHelper.resolveRetrievalQuery(
                "任意 query", List.of(), null, mock(AbstractLLMService.class), false);

        assertThat(result.retrievalQuery()).isEqualTo("任意 query");
        verifyNoInteractions(rewriter);
    }
}
