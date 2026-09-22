package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.UserStatusEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.util.AesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_API_KEY_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_AUTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库 ext key 的三级归属规则：生成/明文查看走 MANAGE（个人库主、团队 OWNER、
 * 管理员）；validateApiKey 保持以创建者身份执行的既有权衡（文档化行为）。
 */
class ExtApiServiceTeamKbTest {

    // AesUtil 的 key 同时充当 CBC IV，必须恰为 16 字节
    private static final String AES_KEY = "0123456789abcdef";

    private ExtApiService service;
    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeBaseAccessService accessService;
    private UserService userService;

    private User teamOwner;
    private User contributor;
    private User admin;
    private KnowledgeBase teamKb;
    private User creator;
    private SpringMessageSourceStub messageSourceStub;

    @BeforeAll
    static void initializeLambdaColumnCache() {
        MybatisTableInfoTestSupport.init(KnowledgeBase.class);
        AesUtil.AES_KEY = AES_KEY;
    }

    @BeforeEach
    void setUp() {
        service = new ExtApiService();
        // 真实 KnowledgeBaseService + mock mapper：getKbOrThrow 的 lambdaQuery 链
        // 经 IRepository.lambdaQuery -> ChainWrappers(getBaseMapper(), getEntityClass())，
        // 预置缓存实体类即可绕开 getMapperClass 的 mapper 代理反射
        knowledgeBaseService = new KnowledgeBaseService();
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        ReflectionTestUtils.setField(knowledgeBaseService, "baseMapper", knowledgeBaseMapper);
        ReflectionTestUtils.setField(knowledgeBaseService, "entityClass", KnowledgeBase.class);
        accessService = mock(KnowledgeBaseAccessService.class);
        userService = mock(UserService.class);
        ReflectionTestUtils.setField(service, "knowledgeBaseService", knowledgeBaseService);
        ReflectionTestUtils.setField(service, "knowledgeBaseAccessService", accessService);
        ReflectionTestUtils.setField(service, "userService", userService);

        teamOwner = user(1L);
        contributor = user(2L);
        admin = user(9L);
        admin.setIsAdmin(true);
        ThreadContext.setCurrentUser(teamOwner);
        messageSourceStub = SpringMessageSourceStub.install();

        teamKb = new KnowledgeBase();
        teamKb.setId(100L);
        teamKb.setUuid("kb-team");
        teamKb.setOwnerType("TEAM");
        teamKb.setTeamId(10L);
        teamKb.setOwnerId(3L);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(teamKb);

        // 团队库由某成员创建（owner_* 记录创建者）
        creator = user(3L);
        creator.setUserStatus(UserStatusEnum.NORMAL);
        when(userService.getById(3L)).thenReturn(creator);
    }

    @AfterEach
    void tearDown() {
        ThreadContext.unload();
        messageSourceStub.close();
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUuid("user-uuid-" + id);
        user.setIsAdmin(false);
        user.setLocale("zh-CN");
        return user;
    }

    // ========== 生成 / 明文查看：MANAGE 门控 ==========

    @Test
    void teamOwnerMayRevealKey() {
        teamKb.setApiKey(AesUtil.encrypt("ext-some-long-key-value"));
        when(accessService.canManage(teamOwner, teamKb)).thenReturn(true);

        var resp = service.revealApiKey("knowledge", "kb-team");

        assertEquals("ext-some-long-key-value", resp.getRawKey());
    }

    @Test
    void contributorCannotRevealTeamKey() {
        ThreadContext.setCurrentUser(contributor);
        teamKb.setApiKey(AesUtil.encrypt("ext-some-long-key-value"));
        when(accessService.canManage(contributor, teamKb)).thenReturn(false);

        BaseException e = assertThrows(BaseException.class,
                () -> service.revealApiKey("knowledge", "kb-team"));
        assertEquals(A_USER_NOT_AUTH.getCode(), e.getCode());
    }

    @Test
    void adminBypassesTierRuleForKeyManagement() {
        ThreadContext.setCurrentUser(admin);
        teamKb.setApiKey(AesUtil.encrypt("ext-some-long-key-value"));

        var resp = service.revealApiKey("knowledge", "kb-team");

        assertEquals("ext-some-long-key-value", resp.getRawKey());
    }

    @Test
    void missingKeyIsReportedAfterPrivilegeCheck() {
        teamKb.setApiKey(null);
        when(accessService.canManage(teamOwner, teamKb)).thenReturn(true);

        BaseException e = assertThrows(BaseException.class,
                () -> service.revealApiKey("knowledge", "kb-team"));
        assertEquals(A_API_KEY_NOT_FOUND.getCode(), e.getCode());
    }

    // ========== validateApiKey：保持创建者身份（文档化权衡） ==========

    @Test
    void validateApiKeyStillExecutesWithCreatorIdentity() {
        // 团队 OWNER 重置 key 之前，key 依旧以创建者（可能是已退出的成员）身份执行。
        // 这是 validateApiKey 的既有语义，此处断言固化该权衡。
        String rawKey = "ext-team-kb-key-value";
        teamKb.setApiKey(AesUtil.encrypt(rawKey));

        var result = service.validateApiKey(rawKey, "knowledge");

        assertEquals("kb-team", result.entityUuid());
        assertEquals(creator.getId(), result.ownerUser().getId());
    }
}
