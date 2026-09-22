package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.service.UserDayCostService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.GraphIngestParam;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 质量自愈循环验证（B1+B3）：extractSegment 内"抽取 → 质检 → 修复"循环必须在
 * 轮数预算内自愈可修复输出，轮数耗尽仍不合格则保持 fail-closed；供应商缺
 * token usage 时按 0 记费而非 NPE。
 * <p>
 * Quality self-healing loop verification (B1+B3): the extraction → quality-gate
 * → repair loop inside extractSegment must heal repairable output within the
 * attempt budget, stay fail-closed when the budget is exhausted, and record a
 * zero cost instead of throwing an NPE when the provider omits token usage.
 */
class GraphRagSelfHealingTest {

    private static final String INPUT_TEXT = "曜穹机器人是一家工业机器人公司，发布了赤脊七型巡检机器人。";
    /** Passes the deterministic quality gate for INPUT_TEXT (prompt built-in example shape). */
    private static final String GOOD_JSON = """
            {"entities":[
              {"name":"曜穹机器人","canonical_name":"曜穹机器人","aliases":[],"type":"ORGANIZATION","description":"一家发布工业巡检机器人的公司","properties":{},"salience":9},
              {"name":"赤脊七型","canonical_name":"赤脊七型","aliases":[],"type":"PRODUCT","description":"曜穹机器人发布的工业巡检机器人产品","properties":{},"salience":8}],
             "relationships":[{"source":"曜穹机器人","target":"赤脊七型","type":"PRODUCES","polarity":true,"status":"ASSERTED","description":"曜穹机器人发布了赤脊七型","properties":{},"weight":8}]}
            """;
    /** Parseable but the empty description trips the quality gate. */
    private static final String EMPTY_DESCRIPTION_JSON = """
            {"entities":[{"name":"曜穹机器人","type":"ORGANIZATION","description":""}],
            "relationships":[]}
            """;
    /** No JSON object at all: the parse failure must become a repairable issue. */
    private static final String NOT_JSON = "抱歉，无法完成实体抽取。";

    private ApplicationContext context;
    private ApplicationContext previousContext;
    private UserDayCostService userDayCostService;
    private ChatModel chatModel;
    private ZhiMeshProperties properties;
    private User user;
    private GraphRag graphRag;
    /** Prompts actually sent to the model, recorded by the stubbing answer. */
    private final List<String> prompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        context = mock(ApplicationContext.class);
        // Real executor with one HTTP attempt and zero backoff: the loop under
        // test is the quality layer, and the HTTP retry layer has its own tests.
        when(context.getBean(GraphExtractionRequestExecutor.class))
                .thenReturn(new GraphExtractionRequestExecutor(2, 1, 0L, 0L));
        userDayCostService = mock(UserDayCostService.class);
        when(context.getBean(UserDayCostService.class)).thenReturn(userDayCostService);
        properties = new ZhiMeshProperties();
        when(context.getBean(ZhiMeshProperties.class)).thenReturn(properties);
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(
                SpringUtil.class, "applicationContext");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);

        chatModel = mock(ChatModel.class);
        user = new User();
        user.setId(21L);
        user.setName("graph-self-healing-user");
        graphRag = new GraphRag("self-healing-test", mock(GraphStore.class));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }

    @Test
    void badJsonOnFirstRoundIsRepairedOnSecondRoundAndBothRoundsAreCharged() {
        stubResponses(chatResponse(NOT_JSON, new TokenUsage(300, 100)),
                chatResponse(GOOD_JSON, new TokenUsage(200, 100)));

        Triple<TextSegment, String, String> result = extract();

        assertTrue(result.getRight().contains("PRODUCES"));

        // Round 1 uses the extraction prompt; round 2 is a repair carrying the
        // converted parse issue plus the broken payload itself.
        verify(chatModel, times(2)).chat(any(UserMessage.class));
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).startsWith("Extract entities and relationships"));
        assertTrue(prompts.get(1).startsWith("Repair the graph extraction JSON"));
        assertTrue(prompts.get(1).contains("response is not valid JSON"));
        assertTrue(prompts.get(1).contains(NOT_JSON));

        // Every round is charged, including the one that produced garbage.
        verify(userDayCostService).appendCostToUser(user, 400, true);
        verify(userDayCostService).appendCostToUser(user, 300, true);
        verify(userDayCostService, times(2)).appendCostToUser(any(), anyInt(), anyBoolean());
    }

    @Test
    void qualityIssuesOnFirstRoundAreRepairedOnSecondRound() {
        stubResponses(chatResponse(EMPTY_DESCRIPTION_JSON, new TokenUsage(300, 100)),
                chatResponse(GOOD_JSON, new TokenUsage(150, 50)));

        Triple<TextSegment, String, String> result = extract();

        assertTrue(result.getRight().contains("PRODUCES"));

        // The detected quality issue is spelled out for the repairing model.
        verify(chatModel, times(2)).chat(any(UserMessage.class));
        assertTrue(prompts.get(1).startsWith("Repair the graph extraction JSON"));
        assertTrue(prompts.get(1).contains("entity description is empty: 曜穹机器人"));

        verify(userDayCostService, times(2)).appendCostToUser(any(), anyInt(), anyBoolean());
    }

    @Test
    void attemptsExhaustedFailsClosedWithAccumulatedIssues() {
        when(chatModel.chat(any(UserMessage.class)))
                .thenAnswer(invocation -> chatResponse(EMPTY_DESCRIPTION_JSON, new TokenUsage(100, 50)));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> extract());

        // Default budget of 3: one extraction plus two repairs, then fail closed.
        assertTrue(failure.getMessage().contains("after 3 attempts"));
        assertTrue(failure.getMessage().contains("entity description is empty: 曜穹机器人"));
        verify(chatModel, times(3)).chat(any(UserMessage.class));
        verify(userDayCostService, times(3)).appendCostToUser(user, 150, true);
    }

    @Test
    void configuredAttemptBudgetIsHonored() {
        properties.getIndexing().setGraphExtractionQualityMaxAttempts(2);
        when(chatModel.chat(any(UserMessage.class)))
                .thenAnswer(invocation -> chatResponse(NOT_JSON, new TokenUsage(100, 50)));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> extract());

        assertTrue(failure.getMessage().contains("after 2 attempts"));
        verify(chatModel, times(2)).chat(any(UserMessage.class));
    }

    @Test
    void missingTokenUsageRecordsZeroCostInsteadOfFailing() {
        when(chatModel.chat(any(UserMessage.class)))
                .thenReturn(chatResponseWithoutUsage(GOOD_JSON));

        Triple<TextSegment, String, String> result = extract();

        assertTrue(result.getRight().contains("PRODUCES"));
        verify(chatModel, times(1)).chat(any(UserMessage.class));
        verify(userDayCostService).appendCostToUser(user, 0, true);
    }

    @Test
    void blankSegmentSkipsTheLoopEntirely() {
        // TextSegment.from rejects blank text, so the guard is exercised via a
        // mock carrying a blank body.
        TextSegment blankSegment = mock(TextSegment.class);
        when(blankSegment.text()).thenReturn("   ");
        Triple<TextSegment, String, String> result = graphRag.extractSegment(ingestParam(), user,
                new GraphRag.SegmentExtractionTask(blankSegment, "seg-blank"));

        assertEquals("", result.getRight());
        verifyNoInteractions(chatModel, userDayCostService);
    }

    /** Scripts the model replies per round while recording every prompt sent. */
    private void stubResponses(ChatResponse... responses) {
        Deque<ChatResponse> script = new ArrayDeque<>(List.of(responses));
        when(chatModel.chat(any(UserMessage.class))).thenAnswer(invocation -> {
            UserMessage message = invocation.getArgument(0);
            prompts.add(message.singleText());
            return script.poll();
        });
    }

    private Triple<TextSegment, String, String> extract() {
        return graphRag.extractSegment(ingestParam(), user,
                new GraphRag.SegmentExtractionTask(TextSegment.from(INPUT_TEXT), "seg-1"));
    }

    private GraphIngestParam ingestParam() {
        return GraphIngestParam.builder()
                .user(user)
                .ChatModel(chatModel)
                // Free token skips the quota check so the test only exercises
                // the self-healing loop and cost recording.
                .isFreeToken(true)
                .build();
    }

    private static ChatResponse chatResponse(String text, TokenUsage tokenUsage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(text))
                .metadata(ChatResponseMetadata.builder().tokenUsage(tokenUsage).build())
                .build();
    }

    private static ChatResponse chatResponseWithoutUsage(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(text))
                .build();
    }
}
