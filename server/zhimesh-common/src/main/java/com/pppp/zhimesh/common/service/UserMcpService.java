package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.UserMcpDto;
import com.pppp.zhimesh.common.dto.mcp.McpCommonParam;
import com.pppp.zhimesh.common.dto.mcp.McpCustomizedParamDefinition;
import com.pppp.zhimesh.common.dto.mcp.UserMcpCustomizedParam;
import com.pppp.zhimesh.common.dto.mcp.UserMcpUpdateReq;
import com.pppp.zhimesh.common.entity.Mcp;
import com.pppp.zhimesh.common.entity.UserMcp;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.UserMcpMapper;
import com.pppp.zhimesh.common.util.AesUtil;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.PrivilegeUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.vo.McpValidationResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpException;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceTemplate;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Locale;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_PARAMS_ERROR;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_MCP_SERVER_NOT_FOUND;
import static java.util.stream.Collectors.toMap;

@Slf4j
@Service
public class UserMcpService extends ServiceImpl<UserMcpMapper, UserMcp> {

    @Resource
    private McpService mcpService;

    @Resource
    private McpRuntimeConfigValidator mcpRuntimeConfigValidator;

    public List<UserMcp> searchEnableByUserId(Long userId) {
        return this.lambdaQuery()
                .eq(UserMcp::getUserId, userId)
                .eq(UserMcp::getIsEnable, true)
                
                .list();
    }

    /**
     * 获取用户已启用的MCP列表（用于External API）
     * 返回用户已启用的MCP服务信息，不包含敏感配置
     */
    public List<UserMcpDto> searchByUserIdForExtApi(Long userId) {
        List<UserMcp> userMcpList = this.lambdaQuery()
                .eq(UserMcp::getUserId, userId)
                .eq(UserMcp::getIsEnable, true)
                
                .list();

        List<UserMcpDto> dtoList = new ArrayList<>();

        List<Mcp> mcpList = new ArrayList<>();
        if (!userMcpList.isEmpty()) {
            mcpList = mcpService.listByIds(userMcpList.stream()
                    .map(UserMcp::getMcpId)
                    .distinct()
                    .toList());
        }
        for (UserMcp userMcp : userMcpList) {
            UserMcpDto dto = new UserMcpDto();
            BeanUtils.copyProperties(userMcp, dto);

            Mcp mcp = mcpList.stream()
                    .filter(item -> item.getId().equals(dto.getMcpId()))
                    .findFirst()
                    .orElse(null);
            setMcpInfo(dto, mcp);
            dtoList.add(dto);
        }
        return dtoList;
    }

    public Page<UserMcpDto> searchByUserId(Long userId, Integer currentPage, Integer pageSize) {
        Page<UserMcp> page = this.lambdaQuery()
                .eq(UserMcp::getUserId, userId)
                
                .orderByDesc(UserMcp::getUpdateTime)
                .page(new Page<>(currentPage, pageSize));

        List<UserMcpDto> dtoList = new ArrayList<>();

        List<Mcp> mcpList = new ArrayList<>();
        if (!page.getRecords().isEmpty()) {
            mcpList = mcpService.listByIds(page.getRecords().stream()
                    .map(UserMcp::getMcpId)
                    .distinct()
                    .toList());
        }
        for (UserMcp userMcp : page.getRecords()) {
            UserMcpDto dto = new UserMcpDto();
            BeanUtils.copyProperties(userMcp, dto);

            Mcp mcp = mcpList.stream()
                    .filter(item -> item.getId().equals(dto.getMcpId()))
                    .findFirst()
                    .orElse(null);
            setMcpInfo(dto, mcp);
            dtoList.add(dto);
        }
        Page<UserMcpDto> result = MPPageUtil.convertToPage(page, UserMcpDto.class);
        result.setRecords(dtoList);
        return result;
    }

    public UserMcpDto saveOrUpdate(UserMcpUpdateReq editReq) {
        if (null == editReq.getMcpCustomizedParams() && null == editReq.getIsEnable()) {
            log.warn("UserMcp edit request is empty, editReq: {}", editReq);
            return null;
        }
        Mcp mcp = mcpService.getOrThrow(editReq.getMcpId(), false);
        UserMcp userMcp = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(UserMcp::getMcpId, editReq.getMcpId())
                .eq(UserMcp::getUserId, ThreadContext.getCurrentUserId())
                
                .one();
        List<UserMcpCustomizedParam> paramSettings = copyParams(editReq.getMcpCustomizedParams());
        if (paramSettings != null) {
            McpValidationResult validation = mcpRuntimeConfigValidator.validateForStorage(mcp, paramSettings);
            if (!validation.valid()) {
                log.warn("Invalid MCP parameters, mcpId:{}, missing:{}, unknown:{}, invalidEncryption:{}, conflicts:{}",
                        mcp.getId(), validation.missingParameters(), validation.unknownParameters(),
                        validation.invalidEncryptionParameters(), validation.conflictingPresetParameters());
                throw new BaseException(A_PARAMS_ERROR);
            }
        }
        if (null == userMcp) {
            userMcp = new UserMcp();
            userMcp.setUuid(UuidUtil.createShort());
            userMcp.setMcpId(editReq.getMcpId());
            userMcp.setUserId(ThreadContext.getCurrentUserId());
            if (null != paramSettings) {
                encryptParams(paramSettings, mcp);
                userMcp.setMcpCustomizedParams(paramSettings);
            }
            if (null != editReq.getIsEnable()) {
                userMcp.setIsEnable(editReq.getIsEnable());
            }
            baseMapper.insert(userMcp);
        } else {
            UserMcp updateObj = new UserMcp();
            updateObj.setId(userMcp.getId());
            if (null != paramSettings) {
                encryptParams(paramSettings, mcp);
                updateObj.setMcpCustomizedParams(paramSettings);
            }
            if (null != editReq.getIsEnable()) {
                updateObj.setIsEnable(editReq.getIsEnable());
            }
            baseMapper.updateById(updateObj);
        }
        // 配置已变更：逐出缓存的共享客户端，下条消息按新配置重建
        evictMcpClientCache(userMcp.getUserId(), userMcp.getMcpId());
        UserMcp saved = this.getById(userMcp.getId());
        UserMcpDto dto = new UserMcpDto();
        BeanUtils.copyProperties(saved, dto);
        setMcpInfo(dto, mcp);
        return dto;
    }

    public List<McpClient> createMcpClients(Long userId, List<Long> mcpIds) {
        List<McpClient> result = new ArrayList<>();
        if (mcpIds == null || mcpIds.isEmpty()) {
            log.warn("No MCP IDs provided for creating MCP clients.");
            return result;
        }

        //过滤出用户已启用的MCP
        Map<Long, UserMcp> mcpIdToUserMcp = this.lambdaQuery()
                .in(UserMcp::getMcpId, mcpIds)
                .eq(UserMcp::getUserId, userId)
                
                .eq(UserMcp::getIsEnable, true)
                .list()
                .stream()
                .collect(toMap(UserMcp::getMcpId, Function.identity(), (a, b) -> a));

        // 查询MCP信息
        List<Mcp> mcpInfos = mcpService.listEnabledByIds(mcpIdToUserMcp.keySet().stream().toList(), true);
        for (Mcp mcp : mcpInfos) {
            UserMcp userMcp = mcpIdToUserMcp.get(mcp.getId());
            if (userMcp == null) {
                log.warn("No user MCP params found for MCP ID: {}", mcp.getId());
                continue;
            }

            McpValidationResult validation = mcpRuntimeConfigValidator.validateForRuntime(mcp, userMcp);
            if (!validation.valid()) {
                log.warn("Skipping unavailable MCP configuration, userId:{}, mcpId:{}, missing:{}, unknown:{}, invalidEncryption:{}, conflicts:{}",
                        userId, mcp.getId(), validation.missingParameters(), validation.unknownParameters(),
                        validation.invalidEncryptionParameters(), validation.conflictingPresetParameters());
                continue;
            }

            try {
                // 检出缓存的共享客户端（首次创建时完成参数解密与握手，详见下方缓存实现）
                McpClient shared = checkoutCachedClient(userId, mcp, userMcp);
                if (null == shared) {
                    // 初始化熔断窗口内：本条消息跳过该 MCP，避免每条消息重复秒级握手
                    continue;
                }
                result.add(shared);
            } catch (Exception exception) {
                log.warn("Skipping MCP client that failed to initialize, userId:{}, mcpId:{}, errorType:{}",
                        userId, mcp.getId(), exception.getClass().getSimpleName());
            }
        }

        return result;
    }

    // ==================== MCP 客户端缓存 ====================
    // 此前每条聊天消息都为每个启用的 MCP 重新走一遍初始化握手（远程 SSE
    // 服务器秒级，跨境更久），且失败的服务器每条消息都会重试一遍。现按
    // (userId, mcpId) 缓存已初始化客户端：请求检出共享装饰器，close() 只
    // 归还不关闭（AbstractLLMService.closeMcpClients 无需改动）；空闲超
    // 过 TTL 由定时任务关闭底层连接并逐出（STDIO 进程不留驻）；listTools/
    // executeTool 失败时自愈重建一次（底层连接闲置断开的场景）；初始化失
    // 败的 MCP 打开 60s 熔断窗口，窗口内直接跳过。用户配置变更（参数/启
    // 用/删除）时主动逐出；管理端 Mcp 定义变更靠 TTL 兜底（最多 5 分钟）。
    // Cached per-(userId, mcpId) initialized MCP clients: chat requests check
    // out a sharing decorator whose close() only releases the reference (the
    // existing per-request closeMcpClients keeps working untouched), idle
    // entries are closed and evicted by a scheduled sweep, tool calls
    // self-heal with one rebuild after the underlying connection dropped, and
    // MCPs whose initialization fails open a 60s circuit instead of retrying
    // every message. User-side config changes evict eagerly; admin-side Mcp
    // definition changes are picked up within the TTL at most.

    /** 底层客户端空闲多久后逐出关闭 / Idle time before the sweep closes the underlying client */
    private static final long MCP_CLIENT_IDLE_TTL_MS = 5 * 60 * 1000L;

    /** 初始化失败后的熔断窗口 / Circuit window opened by an initialization failure */
    private static final long MCP_INIT_CIRCUIT_MS = 60 * 1000L;

    private final ConcurrentHashMap<String, CachedMcpClient> mcpClientCache = new ConcurrentHashMap<>();

    /** 一个 (userId, mcpId) 的缓存条目：底层客户端 + 引用计数 + 重建工厂 */
    private static final class CachedMcpClient {
        private final long userId;
        private final long mcpId;
        private final AtomicInteger inUse = new AtomicInteger();
        private volatile Supplier<McpClient> rebuildFactory;
        private volatile McpClient client;
        private volatile long lastReleaseAt = System.currentTimeMillis();
        private volatile long initFailedAt = 0L;

        private CachedMcpClient(long userId, long mcpId) {
            this.userId = userId;
            this.mcpId = mcpId;
        }
    }

    /**
     * 检出缓存的共享客户端；无缓存则初始化一个并缓存（初始化失败记录熔断时间后原样抛出，
     * 由调用方按“跳过该 MCP”处理）。
     */
    private McpClient checkoutCachedClient(Long userId, Mcp mcp, UserMcp userMcp) {
        String cacheKey = userId + ":" + mcp.getId();
        CachedMcpClient entry = mcpClientCache.computeIfAbsent(cacheKey,
                ignored -> new CachedMcpClient(userId, mcp.getId()));
        synchronized (entry) {
            if (null == entry.client) {
                if (System.currentTimeMillis() - entry.initFailedAt < MCP_INIT_CIRCUIT_MS) {
                    log.debug("MCP init circuit open, skip for this message, userId:{}, mcpId:{}", userId, mcp.getId());
                    return null;
                }
                // 解密用户参数（原 createMcpClients 中的时机）。工厂持有解密后的引用，
                // 自愈重建时无需重复解密。
                decryptParams(userMcp.getMcpCustomizedParams(), mcp);
                entry.rebuildFactory = () -> new DefaultMcpClient.Builder()
                        .transport(createTransport(mcp, userMcp))
                        .build();
                try {
                    entry.client = entry.rebuildFactory.get();
                    entry.initFailedAt = 0L;
                } catch (RuntimeException buildFailure) {
                    entry.rebuildFactory = null;
                    entry.initFailedAt = System.currentTimeMillis();
                    throw buildFailure;
                }
            }
            entry.inUse.incrementAndGet();
            return new SharedMcpClient(entry);
        }
    }

    /** 自愈重建：关闭失效底层客户端并重走一次握手；并发自愈时复用他人重建结果 */
    private McpClient rebuildCachedClient(CachedMcpClient entry, McpClient failed,
            String operation, RuntimeException failure) {
        log.warn("MCP call failed, rebuilding shared client once, userId:{}, mcpId:{}, operation:{}, reason:{}",
                entry.userId, entry.mcpId, operation, String.valueOf(failure.getMessage()));
        synchronized (entry) {
            McpClient current = entry.client;
            if (null != failed && current != failed) {
                // 其他请求已重建完成，直接复用新客户端
                return current;
            }
            entry.client = null;
            closeQuietly(current);
            Supplier<McpClient> factory = entry.rebuildFactory;
            if (null == factory) {
                // 条目已被逐出且无工厂可用，原始异常抛回调用方
                throw failure;
            }
            McpClient rebuilt = factory.get();
            entry.client = rebuilt;
            return rebuilt;
        }
    }

    /** 归还（请求 close()）：减引用计数；条目已不在缓存且无人持有 → 关闭底层 */
    private void releaseCachedClient(CachedMcpClient entry) {
        entry.lastReleaseAt = System.currentTimeMillis();
        if (entry.inUse.decrementAndGet() == 0
                && mcpClientCache.get(entry.userId + ":" + entry.mcpId) != entry) {
            closeEntryIfIdle(entry);
        }
    }

    /** 配置变更/删除时逐出缓存条目；仍被请求持有时由最后一次 release 负责关闭 */
    private void evictMcpClientCache(Long userId, Long mcpId) {
        CachedMcpClient entry = mcpClientCache.remove(userId + ":" + mcpId);
        if (null != entry) {
            closeEntryIfIdle(entry);
        }
    }

    private void closeEntryIfIdle(CachedMcpClient entry) {
        synchronized (entry) {
            McpClient client = entry.client;
            if (null != client && 0 == entry.inUse.get()) {
                entry.client = null;
                entry.rebuildFactory = null;
                closeQuietly(client);
            }
        }
    }

    /** 定时逐出空闲客户端（远程长连接与 STDIO 进程不留驻） */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweepIdleMcpClients() {
        long now = System.currentTimeMillis();
        mcpClientCache.forEach((cacheKey, entry) -> {
            if (0 == entry.inUse.get() && now - entry.lastReleaseAt >= MCP_CLIENT_IDLE_TTL_MS) {
                mcpClientCache.remove(cacheKey, entry);
                closeEntryIfIdle(entry);
            }
        });
    }

    @PreDestroy
    public void closeAllMcpClients() {
        mcpClientCache.values().forEach(entry -> {
            synchronized (entry) {
                McpClient client = entry.client;
                entry.client = null;
                closeQuietly(client);
            }
        });
    }

    private static void closeQuietly(McpClient client) {
        if (null == client) {
            return;
        }
        try {
            client.close();
        } catch (Exception closeFailure) {
            log.debug("Failed to close MCP client", closeFailure);
        }
    }

    /**
     * 共享装饰器：对外是普通 McpClient，close() 只归还引用计数不关底层；
     * listTools/executeTool 失败时自愈重建一次再重试（闲时连接被对端断开
     * 的场景）；其余方法直接委托，聊天链路未使用。
     */
    private final class SharedMcpClient implements McpClient {
        private final CachedMcpClient entry;

        private SharedMcpClient(CachedMcpClient entry) {
            this.entry = entry;
        }

        @Override
        public void close() {
            releaseCachedClient(entry);
        }

        @Override
        public String key() {
            return delegate().key();
        }

        @Override
        public List<ToolSpecification> listTools() {
            McpClient client = entry.client;
            try {
                return client.listTools();
            } catch (McpException serverError) {
                throw serverError;
            } catch (RuntimeException failure) {
                if (!isTransportFailure(failure)) {
                    throw failure;
                }
                return selfHeal("listTools", client, failure).listTools();
            }
        }

        @Override
        public List<ToolSpecification> listTools(InvocationContext context) {
            McpClient client = entry.client;
            try {
                return client.listTools(context);
            } catch (McpException serverError) {
                throw serverError;
            } catch (RuntimeException failure) {
                if (!isTransportFailure(failure)) {
                    throw failure;
                }
                return selfHeal("listTools", client, failure).listTools(context);
            }
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request) {
            McpClient client = entry.client;
            try {
                return client.executeTool(request);
            } catch (McpException serverError) {
                throw serverError;
            } catch (RuntimeException failure) {
                if (!isTransportFailure(failure)) {
                    throw failure;
                }
                return selfHeal("executeTool", client, failure).executeTool(request);
            }
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request, InvocationContext context) {
            McpClient client = entry.client;
            try {
                return client.executeTool(request, context);
            } catch (McpException serverError) {
                throw serverError;
            } catch (RuntimeException failure) {
                if (!isTransportFailure(failure)) {
                    throw failure;
                }
                return selfHeal("executeTool", client, failure).executeTool(request, context);
            }
        }

        @Override
        public List<McpResource> listResources() {
            return delegate().listResources();
        }

        @Override
        public List<McpResource> listResources(InvocationContext context) {
            return delegate().listResources(context);
        }

        @Override
        public List<McpResourceTemplate> listResourceTemplates() {
            return delegate().listResourceTemplates();
        }

        @Override
        public List<McpResourceTemplate> listResourceTemplates(InvocationContext context) {
            return delegate().listResourceTemplates(context);
        }

        @Override
        public McpReadResourceResult readResource(String uri) {
            return delegate().readResource(uri);
        }

        @Override
        public McpReadResourceResult readResource(String uri, InvocationContext context) {
            return delegate().readResource(uri, context);
        }

        @Override
        public void subscribeToResource(String uri) {
            delegate().subscribeToResource(uri);
        }

        @Override
        public void unsubscribeFromResource(String uri) {
            delegate().unsubscribeFromResource(uri);
        }

        @Override
        public List<McpPrompt> listPrompts() {
            return delegate().listPrompts();
        }

        @Override
        public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) {
            return delegate().getPrompt(name, arguments);
        }

        @Override
        public void checkHealth() {
            delegate().checkHealth();
        }

        @Override
        public void setRoots(List<McpRoot> roots) {
            delegate().setRoots(roots);
        }

        private McpClient delegate() {
            McpClient client = entry.client;
            if (null == client) {
                throw new IllegalStateException("Shared MCP client is not available, mcpId:" + entry.mcpId);
            }
            return client;
        }

        /**
         * DefaultMcpClient.executeTool 会把失败统一包成 ToolExecutionException(cause)：
         * 异常链里出现 McpException = 服务端已正常返回 JSON-RPC 错误响应（MCP 会话
         * 存活，属工具级失败，如 fetch 抓不到目标站），不重建；链中完全没有
         * McpException 才判为传输层断连（McpOperationHandler 取消路径的
         * IllegalStateException 或超时），走自愈重建。
         * A McpException anywhere in the cause chain means the server answered
         * with a JSON-RPC error response (session alive, tool-level failure);
         * only chains without one are transport-level failures worth a rebuild.
         */
        private static boolean isTransportFailure(Throwable failure) {
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 8; depth++) {
                if (cause instanceof McpException) {
                    return false;
                }
                Throwable next = cause.getCause();
                if (next == cause) {
                    break;
                }
                cause = next;
            }
            return true;
        }

        private McpClient selfHeal(String operation, McpClient failed, RuntimeException failure) {
            return rebuildCachedClient(entry, failed, operation, failure);
        }
    }

    McpTransport createTransport(Mcp mcp, UserMcp userMcp) {
        Map<String, String> environment = createEnvironment(mcp, userMcp);
        int timeoutSeconds = mcp.getSseTimeout() != null && mcp.getSseTimeout() > 0
                ? mcp.getSseTimeout()
                : 30;

        if (ZhiMeshConstant.McpConstant.TRANSPORT_TYPE_SSE.equals(mcp.getTransportType())) {
            String url = requireEndpoint(mcp.getSseUrl(), "SSE");
            return new HttpMcpTransport.Builder()
                    .sseUrl(appendQueryParameters(url, environment))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
        if (ZhiMeshConstant.McpConstant.TRANSPORT_TYPE_STREAMABLE_HTTP.equals(mcp.getTransportType())) {
            String url = requireEndpoint(mcp.getStreamableHttpUrl(), "Streamable HTTP");
            return new StreamableHttpMcpTransport.Builder()
                    .url(appendQueryParameters(url, environment))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        }
        if (ZhiMeshConstant.McpConstant.TRANSPORT_TYPE_STDIO.equals(mcp.getTransportType())) {
            if (mcp.getStdioCommand() == null || mcp.getStdioCommand().isBlank()) {
                throw new IllegalArgumentException("Stdio MCP command must not be blank");
            }
            List<String> command = buildStdioCommand(mcp.getStdioCommand(), mcp.getStdioArg());
            command.addAll(buildCliArguments(mcp, userMcp));
            return new StdioMcpTransport.Builder()
                    .command(command)
                    .environment(environment)
                    .build();
        }
        throw new IllegalArgumentException("Unsupported MCP transport type: " + mcp.getTransportType());
    }

    /**
     * Build the process command used by the stdio transport. On Windows, npm
     * exposes npx as a .cmd shim, which cannot be started directly by
     * ProcessBuilder. Route it through cmd /c while keeping Linux/macOS
     * configurations unchanged.
     */
    static List<String> buildStdioCommand(String executable, String arguments) {
        List<String> args = splitArguments(arguments);
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        if (windows && ("npx".equalsIgnoreCase(executable) || "npx.cmd".equalsIgnoreCase(executable))) {
            List<String> command = new ArrayList<>();
            command.add("cmd");
            command.add("/c");
            command.add("npx");
            command.addAll(args);
            return command;
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        return command;
    }

    /**
     * 把定义中标记为命令行参数的用户配置值追加到 stdio 命令后面。
     * ProcessBuilder 按列表元素逐一传参，因此含空格的路径无需手工加引号。
     */
    static List<String> buildCliArguments(Mcp mcp, UserMcp userMcp) {
        List<String> result = new ArrayList<>();
        if (mcp.getCustomizedParamDefinitions() == null || userMcp == null) {
            return result;
        }
        List<UserMcpCustomizedParam> userParams = nullToEmpty(userMcp.getMcpCustomizedParams());
        for (McpCustomizedParamDefinition definition : mcp.getCustomizedParamDefinitions()) {
            if (definition == null || !Boolean.TRUE.equals(definition.getCliArg())) {
                continue;
            }
            userParams.stream()
                    .filter(param -> definition.getName().equals(param.getName()))
                    .findFirst()
                    .ifPresent(param -> {
                        if (param.getValue() != null && !String.valueOf(param.getValue()).isBlank()) {
                            if (StringUtils.isNotBlank(definition.getCliPrefix())) {
                                result.add(definition.getCliPrefix().trim());
                            }
                            result.add(String.valueOf(param.getValue()));
                        }
                    });
        }
        return result;
    }

    /**
     * Split the administrator's stdio argument string into ProcessBuilder
     * arguments. The database stores this value as a command-line string, but
     * ProcessBuilder requires one argument per list item. Quoted values are
     * preserved (for example, {@code --user-agent "My App"}).
     */
    static List<String> splitArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char c : arguments.trim().toCharArray()) {
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static String requireEndpoint(String endpoint, String transportName) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(transportName + " MCP endpoint must not be blank");
        }
        return endpoint;
    }

    public void enable(String uuid) {
        UserMcp userMcp = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), A_USER_MCP_SERVER_NOT_FOUND);
        UserMcp updateObj = new UserMcp();
        updateObj.setId(userMcp.getId());
        updateObj.setIsEnable(true);
        baseMapper.updateById(updateObj);
        evictMcpClientCache(userMcp.getUserId(), userMcp.getMcpId());
    }

    public void softDelete(String uuid) {
        UserMcp userMcp = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), A_USER_MCP_SERVER_NOT_FOUND);
        baseMapper.deleteById(userMcp.getId());
        evictMcpClientCache(userMcp.getUserId(), userMcp.getMcpId());
    }

    /**
     * 加密用户的MCP设置（仅对需要加密的字段进行加密）
     * ps：目前暂时只在数据库层做加密，前后端交互时数据加解密方式待定
     *
     * @param setting mcp设置项
     * @param mcp     mcp对象
     */
    private void encryptParams(List<UserMcpCustomizedParam> setting, Mcp mcp) {
        if (setting == null) {
            return;
        }
        for (UserMcpCustomizedParam userMcpCustomizedParam : setting) {
            mcp.getCustomizedParamDefinitions().stream().filter(item -> item.getName().equals(userMcpCustomizedParam.getName()) && Boolean.TRUE.equals(item.getRequireEncrypt()))
                    .findFirst()
                    .ifPresent(item -> {
                        userMcpCustomizedParam.setValue(AesUtil.encrypt(String.valueOf(userMcpCustomizedParam.getValue())));
                        userMcpCustomizedParam.setEncrypted(true);
                    });
        }
    }

    /**
     * 解密用户设置的MCP参数
     *
     * @param mcpParams 用户设置的已加密的mcp参数
     * @param mcp       mcp对象
     */
    private void decryptParams(List<UserMcpCustomizedParam> mcpParams, Mcp mcp) {
        if (mcpParams == null) {
            return;
        }
        for (UserMcpCustomizedParam userMcpCustomizedParam : mcpParams) {
            mcp.getCustomizedParamDefinitions().stream()
                    .filter(item -> Boolean.TRUE.equals(userMcpCustomizedParam.getEncrypted()) && item.getName().equals(userMcpCustomizedParam.getName()) && Boolean.TRUE.equals(item.getRequireEncrypt()))
                    .findFirst()
                    .ifPresent(item -> {
                        userMcpCustomizedParam.setValue(AesUtil.decrypt(String.valueOf(userMcpCustomizedParam.getValue())));
                        userMcpCustomizedParam.setEncrypted(false);
                    });
        }
    }

    public void setMcpInfo(UserMcpDto dto, Mcp mcp) {
        if (mcp == null) {
            return;
        }
        dto.setMcpInfo(mcp);
        //解密以传到前端
        decryptParams(dto.getMcpCustomizedParams(), mcp);
    }

    /**
     * 创建MCP server中以http方式传输时所需的查询参数
     *
     * @param mcp     MCP对象
     * @param userMcp 用户MCP对象
     * @return 查询参数字符串
     */
    static String appendQueryParameters(String baseUrl, Map<String, String> environment) {
        if (baseUrl == null || environment == null || environment.isEmpty()) {
            return baseUrl;
        }
        StringJoiner query = new StringJoiner("&");
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && value != null && !value.isEmpty()) {
                query.add(URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        if (query.length() == 0) {
            return baseUrl;
        }
        String separator = baseUrl.endsWith("?") || baseUrl.endsWith("&")
                ? ""
                : (baseUrl.contains("?") ? "&" : "?");
        return baseUrl + separator + query;
    }


    /**
     * 创建MCP server中以stdio方式传输时所需的环境变量
     *
     * @param mcp     MCP对象
     * @param userMcp 用户MCP对象
     * @return 环境变量映射
     */
    private Map<String, String> createEnvironment(Mcp mcp, UserMcp userMcp) {
        Map<String, String> environment = new HashMap<>();
        for (McpCommonParam initParams : nullToEmpty(mcp.getPresetParams())) {
            environment.put(initParams.getName(), String.valueOf(initParams.getValue()));
        }
        List<UserMcpCustomizedParam> userParams = nullToEmpty(userMcp.getMcpCustomizedParams());
        for (McpCustomizedParamDefinition uninitParam : nullToEmpty(mcp.getCustomizedParamDefinitions())) {
            if (Boolean.TRUE.equals(uninitParam.getCliArg())) {
                // 命令行参数由 buildCliArguments 注入，不作为环境变量
                continue;
            }
// Uninitialized parameters defined in MCP need to use user-configured values
            // MCP中定义的未初始化参数，需要使用用户设置的值
            UserMcpCustomizedParam userParam = userParams.stream()
                    .filter(param -> param.getName().equals(uninitParam.getName()))
                    .findFirst()
                    .orElse(null);
            if (null == userParam) {
                log.warn("No user MCP param found for uninitialized parameter: {}", uninitParam.getName());
                continue;
            }
            environment.put(uninitParam.getName(), String.valueOf(userParam.getValue()));
        }
        return environment;
    }

    private List<UserMcpCustomizedParam> copyParams(List<UserMcpCustomizedParam> parameters) {
        if (parameters == null) {
            return null;
        }
        return parameters.stream().map(source -> {
            UserMcpCustomizedParam copy = new UserMcpCustomizedParam();
            copy.setName(source.getName());
            copy.setValue(source.getValue());
            copy.setEncrypted(source.getEncrypted());
            return copy;
        }).toList();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
