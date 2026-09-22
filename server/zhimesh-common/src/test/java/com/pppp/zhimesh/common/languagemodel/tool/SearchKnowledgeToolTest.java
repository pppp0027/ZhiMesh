package com.pppp.zhimesh.common.languagemodel.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.entity.TeamMember;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.TeamRoleEnum;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.mapper.TeamMemberMapper;
import com.pppp.zhimesh.common.service.KnowledgeBaseAccessService;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * search_knowledge 的行为验证：鉴权矩阵（resolver 过滤后的 filteredKb 是唯一检索范围，
 * kbHint 只能缩小、绝不提权）、kbHint 标题模糊过滤、空结果文案、refCollector 回流、
 * 来源标题前缀格式与 spec 形态
 * <p>
 * Behavioral verification for search_knowledge: the authorization matrix
 * (resolver-filtered filteredKb is the only retrieval scope; kbHint may only
 * narrow it, never escalate), kbHint fuzzy title filtering, the empty-result
 * text, refCollector flow-back, source-title prefix formatting and the spec shape.
 */
class SearchKnowledgeToolTest {

    private static final Long USER_ID = 1L;
    private static final String USER_UUID = "uuid-me";
    private static final Long TEAM_IN_ID = 10L;
    private static final Long TEAM_OUT_ID = 20L;

    private KnowledgeBaseAccessService accessService;
    private TeamMemberMapper teamMemberMapper;
    private User normalUser;

    @BeforeEach
    void setUp() {
        accessService = new KnowledgeBaseAccessService();
        teamMemberMapper = mock(TeamMemberMapper.class);
        ReflectionTestUtils.setField(accessService, "teamMemberMapper", teamMemberMapper);

        normalUser = new User();
        normalUser.setId(USER_ID);
        normalUser.setUuid(USER_UUID);
        normalUser.setIsAdmin(false);

        // 普通用户：仅 TEAM_IN 团队的 READER，其余团队非成员。selectOne 按调用顺序依次
        // 应答：第 1 次（团队 10 成员查询）返回 READER 成员行，其后（团队 20 非成员）返回 null。
        // 顺序依赖：mixedBoundKnowledgeBases() 里成员团队库排在非成员团队库之前（流式按序求值）；
        // selectOne 的 LambdaQueryWrapper 参数值（MPGENVAL）在 mock 下不渲染 SQL、不可解码，
        // 故不按参数区分（同 KnowledgeBaseAccessServiceTest 的打桩惯例）
        // <p>
        // Normal user: READER of TEAM_IN only. selectOne is stubbed sequentially:
        // the 1st call (team-10 membership) returns a READER row, later calls
        // (team-20 non-membership) return null. Ordering relies on the member
        // team KB preceding the non-member one in mixedBoundKnowledgeBases();
        // the wrapper's MPGENVAL parameter values stay unrendered under a mock
        // and cannot be decoded, so args are not differentiated (same stubbing
        // convention as KnowledgeBaseAccessServiceTest).
        TeamMember memberOfTeamIn = new TeamMember();
        memberOfTeamIn.setTeamId(TEAM_IN_ID);
        memberOfTeamIn.setUserId(USER_ID);
        memberOfTeamIn.setRole(TeamRoleEnum.READER.getValue());
        when(teamMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(memberOfTeamIn, (TeamMember) null);
    }

    // ========== fixture ==========

    private KbInfoResp kb(String uuid, String title, String ownerType, String ownerUuid,
                          Long teamId, String companyScope, boolean isSystem) {
        KbInfoResp kb = new KbInfoResp();
        kb.setId((long) Math.abs(Objects.hashCode(uuid)));
        kb.setUuid(uuid);
        kb.setTitle(title);
        kb.setOwnerType(ownerType);
        kb.setOwnerUuid(ownerUuid);
        kb.setTeamId(teamId);
        kb.setCompanyScope(companyScope);
        kb.setIsSystem(isSystem);
        kb.setIsEnabled(true);
        return kb;
    }

    /** 混合归属 fixture：本人个人库/他人个人库/成员团队库/非成员团队库/STAFF/EXECUTIVE/系统库 */
    private List<KbInfoResp> mixedBoundKnowledgeBases() {
        return List.of(
                kb("uuid-mine", "我的产品手册", "PERSONAL", USER_UUID, 0L, null, false),
                kb("uuid-others", "他人私人笔记", "PERSONAL", "uuid-someone-else", 0L, null, false),
                kb("uuid-team-in", "我参与的团队库", "TEAM", null, TEAM_IN_ID, null, false),
                kb("uuid-team-out", "非成员团队库", "TEAM", null, TEAM_OUT_ID, null, false),
                kb("uuid-staff", "全员企业库", "COMPANY", null, 0L, "STAFF", false),
                kb("uuid-executive", "高管企业库", "COMPANY", null, 0L, "EXECUTIVE", false),
                kb("uuid-system", "系统库", "PERSONAL", USER_UUID, 0L, null, true));
    }

    /** 复刻 CharacterService.filterEnableKb 的鉴权语义：仅保留 canRead 的库 */
    private List<KbInfoResp> resolverFilter(List<KbInfoResp> bound) {
        return bound.stream()
                .filter(item -> accessService.canRead(normalUser, item))
                .toList();
    }

    // ========== 可覆写检索入口的捕获子类 ==========

    /**
     * 覆写包级 doRetrieve：捕获传参（断言点）并回放固定片段；hitsEcho=true 时
     * 对捕获到的每个 KB 回放一个带 kb_uuid 元数据的片段——若任何不可见库混入检索范围，
     * 其内容必然出现在输出里（不可见内容绝不出现的反证桩）
     * <p>
     * Overrides the package-level doRetrieve: captures the arguments (the
     * assertion point) and replays canned fragments; with hitsEcho=true it echoes
     * one kb_uuid-tagged fragment per captured KB — if any invisible KB leaked
     * into the scope, its content would necessarily surface in the output.
     */
    private static final class CapturingSearchTool extends SearchKnowledgeTool {
        private Long capturedCharacterId;
        private List<KbInfoResp> capturedFilteredKb;
        private String capturedQuery;
        private String capturedMemoryId;
        private List<RetrieverWrapper> hits = List.of();
        private boolean echoCapturedScope;
        private int retrieveInvocations;

        @Override
        List<RetrieverWrapper> doRetrieve(Long characterId, List<KbInfoResp> filteredKb,
                                          AbstractLLMService llmService, EmbeddingModel embeddingModel,
                                          String queryText, String memoryId) {
            retrieveInvocations++;
            capturedCharacterId = characterId;
            capturedFilteredKb = filteredKb;
            capturedQuery = queryText;
            capturedMemoryId = memoryId;
            if (!echoCapturedScope) {
                return hits;
            }
            return filteredKb.stream()
                    .map(kb -> RetrieverWrapper.builder()
                            .contentFrom(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE)
                            .response(List.of(Content.from(TextSegment.from(
                                    "片段:" + kb.getTitle(),
                                    new Metadata().put(ZhiMeshConstant.MetadataKey.KB_UUID, kb.getUuid())))))
                            .build())
                    .toList();
        }
    }

    private ToolContext contextWith(User user, List<KbInfoResp> filteredKb, List<RetrieverWrapper> refCollector) {
        return ToolContext.builder()
                .user(user)
                .characterId(7L)
                .memoryId("memory-1")
                .toolTraces(new ArrayList<>())
                .refCollector(refCollector)
                .ragContext(ToolRagContext.builder()
                        .filteredKb(filteredKb)
                        .llmService(mock(AbstractLLMService.class))
                        .embeddingModel(mock(EmbeddingModel.class))
                        .build())
                .build();
    }

    private ToolExecutionRequest request(String arguments) {
        return ToolExecutionRequest.builder()
                .id("req-1")
                .name(SearchKnowledgeTool.NAME)
                .arguments(arguments)
                .build();
    }

    private static List<String> titles(List<KbInfoResp> knowledgeBases) {
        return knowledgeBases.stream().map(KbInfoResp::getTitle).collect(Collectors.toList());
    }

    private static RetrieverWrapper wrapper(String contentFrom, Content... contents) {
        return RetrieverWrapper.builder()
                .contentFrom(contentFrom)
                .response(List.of(contents))
                .build();
    }

    private static Content kbContent(String kbUuid, String text) {
        return Content.from(TextSegment.from(text, new Metadata()
                .put(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid)));
    }

    // ========== resolver 矩阵：鉴权不提权 ==========

    @Test
    void resolverMatrixFeedsOnlyVisibleKnowledgeBasesToRetrieval() throws Exception {
        List<KbInfoResp> visible = resolverFilter(mixedBoundKnowledgeBases());

        // 普通用户的可见集：本人个人库 + 成员团队库 + STAFF 企业库
        assertThat(titles(visible)).containsExactly("我的产品手册", "我参与的团队库", "全员企业库");

        CapturingSearchTool tool = new CapturingSearchTool();
        tool.echoCapturedScope = true;
        String result = tool.execute(request("{\"query\":\"产品保修政策\"}"),
                contextWith(normalUser, visible, new ArrayList<>()));

        // 喂给检索的 filteredKb 只含可见库
        assertThat(titles(tool.capturedFilteredKb))
                .containsExactly("我的产品手册", "我参与的团队库", "全员企业库");
        // 回放桩对范围内每个库各回一片段：可见内容出现、不可见内容绝不出现
        assertThat(result)
                .contains("片段:我的产品手册")
                .contains("片段:我参与的团队库")
                .contains("片段:全员企业库")
                .doesNotContain("他人私人笔记")
                .doesNotContain("非成员团队库")
                .doesNotContain("高管企业库")
                .doesNotContain("系统库");
    }

    @Test
    void resolverMatrixGrantsNoTeamOrExecutiveOrSystemVisibility() {
        List<KbInfoResp> visible = resolverFilter(mixedBoundKnowledgeBases());
        List<String> allTitles = titles(mixedBoundKnowledgeBases());
        // 全集去掉可见集，逐库断言不可见（防止 containsExactly 掩盖单库回归）
        List<String> invisibleTitles = allTitles.stream()
                .filter(title -> !titles(visible).contains(title)).toList();
        assertThat(invisibleTitles)
                .containsExactlyInAnyOrder("他人私人笔记", "非成员团队库", "高管企业库", "系统库");
    }

    @Test
    void executiveCompanyScopeStaysInvisibleToNormalUser() {
        // 普通用户对 EXECUTIVE 企业库 canRead=false：即使该库在角色绑定列表里也进不了 filteredKb
        List<KbInfoResp> visible = resolverFilter(mixedBoundKnowledgeBases());
        assertThat(visible).noneMatch(kb -> "EXECUTIVE".equals(kb.getCompanyScope()));
    }

    // ========== kbHint 过滤（只缩小、不提权） ==========

    @Test
    void kbHintNarrowsScopeToMatchingVisibleTitle() throws Exception {
        KbInfoResp manual = kb("uuid-mine", "我的产品手册", "PERSONAL", USER_UUID, 0L, null, false);
        KbInfoResp travel = kb("uuid-travel", "旅行攻略", "PERSONAL", USER_UUID, 0L, null, false);
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.echoCapturedScope = true;

        String result = tool.execute(request("{\"query\":\"保修期\",\"kbHint\":\"产品\"}"),
                contextWith(normalUser, List.of(manual, travel), new ArrayList<>()));

        assertThat(titles(tool.capturedFilteredKb)).containsExactly("我的产品手册");
        assertThat(result).contains("片段:我的产品手册").doesNotContain("旅行攻略");
    }

    @Test
    void kbHintMatchesCaseInsensitively() throws Exception {
        KbInfoResp manual = kb("uuid-mine", "Product Manual", "PERSONAL", USER_UUID, 0L, null, false);
        CapturingSearchTool tool = new CapturingSearchTool();

        tool.execute(request("{\"query\":\"warranty\",\"kbHint\":\"product manual\"}"),
                contextWith(normalUser, List.of(manual), new ArrayList<>()));

        assertThat(titles(tool.capturedFilteredKb)).containsExactly("Product Manual");
    }

    @Test
    void kbHintWithoutMatchLeavesEmptyScopeAndExplicitEmptyText() throws Exception {
        KbInfoResp manual = kb("uuid-mine", "我的产品手册", "PERSONAL", USER_UUID, 0L, null, false);
        CapturingSearchTool tool = new CapturingSearchTool();

        String result = tool.execute(request("{\"query\":\"保修期\",\"kbHint\":\"完全不匹配的标题\"}"),
                contextWith(normalUser, List.of(manual), new ArrayList<>()));

        assertThat(tool.capturedFilteredKb).isEmpty();
        assertThat(result).isEqualTo(SearchKnowledgeTool.EMPTY_RESULT_TEXT);
    }

    @Test
    void kbHintPointingAtInvisibleKnowledgeBaseNeverEscalates() throws Exception {
        // kbHint 点名不可见库：过滤只作用于 filteredKb，点名不可能把库拉进范围（不提权）
        KbInfoResp manual = kb("uuid-mine", "我的产品手册", "PERSONAL", USER_UUID, 0L, null, false);
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.echoCapturedScope = true;

        String result = tool.execute(request("{\"query\":\"高管薪酬\",\"kbHint\":\"高管企业库\"}"),
                contextWith(normalUser, List.of(manual), new ArrayList<>()));

        assertThat(tool.capturedFilteredKb).isEmpty();
        assertThat(result).isEqualTo(SearchKnowledgeTool.EMPTY_RESULT_TEXT);
        assertThat(result).doesNotContain("高管");
    }

    // ========== 空结果与参数校验 ==========

    @Test
    void emptyRetrievalYieldsExplicitEmptyText() throws Exception {
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.hits = List.of();

        String result = tool.execute(request("{\"query\":\"冷门问题\"}"),
                contextWith(normalUser, List.of(), new ArrayList<>()));

        assertThat(result).isEqualTo(SearchKnowledgeTool.EMPTY_RESULT_TEXT);
    }

    @Test
    void missingRagContextShortCircuitsToEmptyTextWithoutRetrieval() throws Exception {
        CapturingSearchTool tool = new CapturingSearchTool();
        ToolContext context = ToolContext.builder().user(normalUser).build();

        assertThat(tool.execute(request("{\"query\":\"任意\"}"), context))
                .isEqualTo(SearchKnowledgeTool.EMPTY_RESULT_TEXT);
        assertThat(tool.retrieveInvocations).isZero();
    }

    @Test
    void blankOrMissingQueryIsRejected() {
        CapturingSearchTool tool = new CapturingSearchTool();
        ToolContext context = contextWith(normalUser, List.of(), new ArrayList<>());

        assertThatThrownBy(() -> tool.execute(request("{\"query\":\"  \"}"), context))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(request("{}"), context))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(request("not-json"), context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== refCollector 回流 ==========

    @Test
    void hitWrappersFlowToRefCollectorInRetrievalOrder() throws Exception {
        RetrieverWrapper first = wrapper(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE,
                kbContent("uuid-mine", "保修期为两年"));
        RetrieverWrapper memory = wrapper(ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY,
                Content.from(TextSegment.from("用户喜欢咖啡")));
        RetrieverWrapper emptyMiss = RetrieverWrapper.builder()
                .contentFrom(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE)
                .response(List.of())
                .build();
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.hits = List.of(first, memory, emptyMiss);
        List<RetrieverWrapper> refCollector = new ArrayList<>();

        tool.execute(request("{\"query\":\"用户偏好\"}"),
                contextWith(normalUser, List.of(), refCollector));

        // 只有命中片段（非空 response）回流，顺序与检索返回一致；空结果不回流
        assertThat(refCollector).containsExactly(first, memory);
    }

    @Test
    void refCollectorStaysUntouchedWhenAbsent() throws Exception {
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.hits = List.of(wrapper(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE,
                kbContent("uuid-mine", "片段")));

        String result = tool.execute(request("{\"query\":\"任意\"}"),
                contextWith(normalUser, List.of(), null));

        assertThat(result).contains("片段");
    }

    // ========== 来源标题前缀格式 ==========

    @Test
    void outputPrefixesKnowledgeFragmentsWithSourceTitle() throws Exception {
        KbInfoResp manual = kb("uuid-mine", "我的产品手册", "PERSONAL", USER_UUID, 0L, null, false);
        KbInfoResp travel = kb("uuid-travel", "旅行攻略", "PERSONAL", USER_UUID, 0L, null, false);
        RetrieverWrapper hit = wrapper(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE,
                kbContent("uuid-mine", "保修期为两年"),
                kbContent("uuid-travel", "签证需提前办理"));
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.hits = List.of(hit);

        String result = tool.execute(request("{\"query\":\"售后与出行\"}"),
                contextWith(normalUser, List.of(manual, travel), new ArrayList<>()));

        assertThat(result).isEqualTo("[来源: 我的产品手册] 保修期为两年\n[来源: 旅行攻略] 签证需提前办理");
    }

    @Test
    void outputLabelsMemoryChannelsAndUnknownProvenance() throws Exception {
        RetrieverWrapper memory = wrapper(ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY,
                Content.from(TextSegment.from("用户喜欢咖啡")));
        RetrieverWrapper episodic = wrapper(ZhiMeshConstant.RetrieveContentFrom.CHARACTER_MEMORY_EPISODIC,
                Content.from(TextSegment.from("上周讨论过出差")));
        RetrieverWrapper unknownUuid = wrapper(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE,
                Content.from(TextSegment.from("缺少来源元数据的片段")));
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.hits = List.of(memory, episodic, unknownUuid);

        String result = tool.execute(request("{\"query\":\"用户情况\"}"),
                contextWith(normalUser, List.of(), new ArrayList<>()));

        assertThat(result).isEqualTo("[来源: 角色记忆] 用户喜欢咖啡\n"
                + "[来源: 情景记忆] 上周讨论过出差\n"
                + "[来源: 知识库] 缺少来源元数据的片段");
    }

    // ========== spec 形态 ==========

    @Test
    void specExposesSearchKnowledgeSchema() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool();
        var spec = tool.spec();

        assertThat(spec.name()).isEqualTo("search_knowledge");
        assertThat(spec.description()).contains("知识库").contains("query");
        assertThat(spec.parameters().properties()).containsOnlyKeys("query", "kbHint");
        assertThat(spec.parameters().required()).containsExactly("query");
        assertThat(tool.isMcpTool()).isFalse();
    }

    @Test
    void executeReadsContextIdentityForRetrieval() throws Exception {
        CapturingSearchTool tool = new CapturingSearchTool();
        tool.execute(request("{\"query\":\"产品保修\"}"), contextWith(normalUser, List.of(), new ArrayList<>()));

        assertThat(tool.capturedCharacterId).isEqualTo(7L);
        assertThat(tool.capturedQuery).isEqualTo("产品保修");
        assertThat(tool.capturedMemoryId).isEqualTo("memory-1");
    }
}
