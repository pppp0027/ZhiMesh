package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.Character;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.vo.ChatModelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M2 身份行注入的装配验证（design 契约：仅 agentic 生效路径）：调用方适用标记 + 开关
 * + 用户可解析三者齐备时，system message 在 dateContext 之后追加「当前对话用户：
 * {name}…」身份行（顺序锚点：日期在前身份在后，ai_system_message 存在与否两态同
 * 追加）；适用标记 false（非 agentic / AgentNode 口径）、开关关闭、用户为 null 或姓名
 * 空白时 system message 与注入前逐字节一致（以 null 用户跑出的基线串做等值断言，不
 * 重算日期）——default-agentic-enabled=false 时无论 identity 开关两态全路径回到
 * 改造前口径。
 * <p>
 * Assembly verification of the M2 identity-line injection (design contract:
 * agentic-effective path only): with the caller's applicability flag, the
 * switch, and a resolvable user all holding, the system message appends the
 * "Current chat user: {name}…" identity line after dateContext (order anchor:
 * date first, identity after; appended in both the with- and
 * without-ai_system_message shapes); with the flag false (the non-agentic /
 * AgentNode convention), the switch off, a null user, or a blank name, the
 * system message stays byte-for-byte identical to pre-injection (asserted by
 * equality against a baseline string produced with a null user, without
 * recomputing the date) — with default-agentic-enabled=false every path
 * returns to the pre-change system message regardless of the identity switch.
 */
class CharacterChatHelperIdentityLineTest {

    private static final String SYSTEM_MESSAGE = "你是人事助手";
    private static final String IDENTITY_LINE =
            "当前对话用户：李四。以该身份理解\"我/本人\"的指代；为其办理业务时以其姓名填写员工姓名类参数。";

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private ZhiMeshProperties properties;
    private AbstractLLMService llmService;
    private Character character;
    private User user;

    @BeforeEach
    void setUp() {
        context = mock(ApplicationContext.class);
        properties = new ZhiMeshProperties();
        when(context.getBean(ZhiMeshProperties.class)).thenReturn(properties);
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);

        llmService = mock(AbstractLLMService.class);
        AiModel aiModel = new AiModel();
        when(llmService.getAiModel()).thenReturn(aiModel);

        character = new Character();
        character.setAiSystemMessage(SYSTEM_MESSAGE);

        user = new User();
        user.setName("李四");
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void identityLineAppendedAfterDateContextWhenApplicableSwitchOnAndUserResolvable() {
        // 适用标记 true + 开关默认开 + 用户可解析：ai_system_message + "\n\n" +
        // dateContext + "\n" + 身份行；顺序锚点——身份行在末尾且晚于日期行
        // Applicability flag true + switch on by default + a resolvable user:
        // ai_system_message + "\n\n" + dateContext + "\n" + the identity line;
        // order anchor — the identity line comes last, after the date line
        String systemMessage = buildSystemMessage(user, true);

        assertThat(systemMessage).startsWith(SYSTEM_MESSAGE + "\n\n当前日期：");
        assertThat(systemMessage).endsWith(IDENTITY_LINE);
        assertThat(systemMessage.indexOf("当前日期：")).isLessThan(systemMessage.indexOf("当前对话用户：李四"));
        assertThat(systemMessage).contains("\n" + IDENTITY_LINE);
    }

    @Test
    void identityLineAlsoAppendedWithoutSystemMessage() {
        // ai_system_message 空白的角色：身份行照样追加（不依赖人设存在），system
        // message 直接以日期行开头
        // A character with a blank ai_system_message: the identity line is
        // appended just the same (not tied to a persona); the system message
        // starts with the date line directly
        character.setAiSystemMessage(null);

        String systemMessage = buildSystemMessage(user, true);

        assertThat(systemMessage).startsWith("当前日期：");
        assertThat(systemMessage).endsWith(IDENTITY_LINE);
        assertThat(systemMessage.indexOf("当前日期：")).isLessThan(systemMessage.indexOf("当前对话用户：李四"));
    }

    @Test
    void switchOffOrUnresolvableUserKeepsSystemMessageByteIdentical() {
        // 回退等价三态——开关关 / 用户 null / 姓名空白（适用标记恒 true，单独验证后三
        // 个条件）：均不追加任何字符。以「null 用户 + 开关开」跑出的串为基线，另两态
        // 与基线逐字节相等且不含身份行标记
        // The three rollback-equivalent shapes — switch off / null user / blank
        // name (the applicability flag stays true so these three conditions are
        // verified on their own): nothing is appended in any of them. The
        // string produced with a null user and the switch on is the baseline;
        // the other two shapes equal it byte-for-byte and carry no
        // identity-line marker
        String baseline = buildSystemMessage(null, true);

        properties.getAgent().setCallerIdentityInjectionEnabled(false);
        assertThat(buildSystemMessage(user, true)).isEqualTo(baseline);

        User blankName = new User();
        blankName.setName("  ");
        // 恢复开关默认开，姓名空白单独成立（姓名空白=身份不可解析）
        // Back to the default-on switch; the blank name alone must hold
        // (a blank name = an unresolvable identity)
        properties.getAgent().setCallerIdentityInjectionEnabled(true);
        assertThat(buildSystemMessage(blankName, true)).isEqualTo(baseline);

        assertThat(baseline).startsWith(SYSTEM_MESSAGE + "\n\n当前日期：");
        assertThat(baseline).doesNotContain("当前对话用户");
    }

    @Test
    void inapplicableFlagKeepsSystemMessageByteIdenticalEvenWithSwitchOnAndUserResolvable() {
        // 适用标记 false（非 agentic 聊天回退 / AgentNode 口径）：即使开关开且用户
        // 可解析也不注入——与「null 用户 + 标记 true」基线逐字节相等；这是
        // default-agentic-enabled=false 全路径回退口径的接线前提
        // Applicability flag false (the non-agentic chat rollback / the
        // AgentNode convention): no injection even with the switch on and a
        // resolvable user — byte-for-byte equal to the null-user,
        // flag-true baseline; this is the wiring premise behind
        // default-agentic-enabled=false rolling every path back
        String baseline = buildSystemMessage(null, true);

        assertThat(buildSystemMessage(user, false)).isEqualTo(baseline);
        assertThat(buildSystemMessage(user, false)).doesNotContain("当前对话用户");
    }

    /**
     * 走真实 buildChatRequestParams 装配取 systemMessage（enableMcp=false 避开 MCP
     * 客户端装配；非推理模型让思考开关保持 null 不影响断言；callerIdentityApplicable
     * 模拟调用方接线——聊天侧 agenticEffective / AgentNode 侧 false）
     * <p>
     * Build the systemMessage through the real buildChatRequestParams assembly
     * (enableMcp=false skips MCP client assembly; a non-reasoner model keeps
     * the thinking switch null and out of the assertions' way;
     * callerIdentityApplicable simulates the caller wiring — agenticEffective
     * on the chat side, false on the AgentNode side)
     */
    private String buildSystemMessage(User requestUser, boolean callerIdentityApplicable) {
        ChatModelRequest request = CharacterChatHelper.buildChatRequestParams(
                character, "mem-identity", "帮我查假期余额", requestUser, llmService, false, false, null,
                callerIdentityApplicable);
        return request.getSystemMessage();
    }
}
