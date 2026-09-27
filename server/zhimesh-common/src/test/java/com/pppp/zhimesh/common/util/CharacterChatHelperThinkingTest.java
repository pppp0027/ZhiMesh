package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.AiModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * checkIfReturnThinking 纯模型能力判定的矩阵单测：非推理模型不表态（null）、推理模型
 * 默认思考（true）、DeepSeek 平台在工具/联网活跃时强制关闭思考（langchain4j #3461，
 * 工具优先）、DeepSeek 无工具/联网时照常思考、非 DeepSeek 平台不受工具/联网影响。
 * 角色列 is_enable_thinking 不再参与判定（2026-09-24 产品决策，开关按钮已下线）。
 * <p>
 * Matrix tests for the pure model-capability decision behind
 * checkIfReturnThinking: non-reasoner models give no opinion (null), reasoner
 * models think by default (true), the DeepSeek platform force-disables
 * thinking while tools/web search are active (langchain4j #3461; tools take
 * priority), DeepSeek without tools/web search still thinks, and non-DeepSeek
 * platforms are unaffected by tools/web search. The character's
 * is_enable_thinking column no longer participates (2026-09-24 product
 * decision; the toggle button is gone).
 */
class CharacterChatHelperThinkingTest {

    /** 构造指定平台与 reasoner 能力的模型 / Build a model with the given platform name and reasoner flag. */
    private static AiModel model(Boolean isReasoner) {
        AiModel aiModel = new AiModel();
        aiModel.setIsReasoner(isReasoner);
        return aiModel;
    }

    @Test
    void nonReasonerReturnsNull() {
        // 非推理模型不表态：前端不渲染思考流，请求也不显式开关
        // Non-reasoner gives no opinion: no thinking stream rendered, no
        // explicit flag sent either way
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(false), ZhiMeshConstant.ModelPlatform.DEEPSEEK, true))
                .isNull();
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(null), ZhiMeshConstant.ModelPlatform.DEEPSEEK, false))
                .isNull();
    }

    @Test
    void reasonerWithoutToolsReturnsTrue() {
        // 推理模型默认思考：无工具/联网时 DeepSeek 也照常开启
        // Reasoner models think by default: DeepSeek with no tools/web search
        // still enables thinking
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(true), ZhiMeshConstant.ModelPlatform.DEEPSEEK, false))
                .isTrue();
    }

    @Test
    void deepSeekWithToolsOrWebSearchReturnsFalse() {
        // DeepSeek 思考流与工具调用不兼容（langchain4j #3461）：工具优先，强制关闭思考
        // DeepSeek's thinking stream is incompatible with tool calls
        // (langchain4j #3461): tools take priority, thinking is force-disabled
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(true), ZhiMeshConstant.ModelPlatform.DEEPSEEK, true))
                .isFalse();
    }

    @Test
    void nonDeepSeekPlatformIgnoresToolsFlag() {
        // 非 DeepSeek 平台（如 openrouter）不受工具/联网影响：推理模型照常思考
        // Non-DeepSeek platforms (e.g. openrouter) ignore tools/web search:
        // reasoner models keep thinking
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(true), "openrouter", true))
                .isTrue();
    }

    @Test
    void platformNameMatchIsExactNotSubstring() {
        // 平台判定按常量精确匹配：形如 "deepseek-reasoner" 的平台名不触发强制关闭
        // Platform matching is exact against the constant: a platform name like
        // "deepseek-reasoner" must not trigger the force-off
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(true), "deepseek-reasoner", true))
                .isTrue();
        // 平台名为 null（理论上不发生）不触发强制关闭，按普通推理模型处理
        // A null platform name (should not happen) does not trigger force-off;
        // treated as an ordinary reasoner
        assertThat(CharacterChatHelper.checkIfReturnThinking(model(true), null, true))
                .isTrue();
    }
}
