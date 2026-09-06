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
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
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
                // 解密用户设置的MCP参数
                decryptParams(userMcp.getMcpCustomizedParams(), mcp);

                McpTransport transport = createTransport(mcp, userMcp);
                McpClient mcpClient = new DefaultMcpClient.Builder()
                        .transport(transport)
                        .build();
                result.add(mcpClient);
            } catch (Exception exception) {
                log.warn("Skipping MCP client that failed to initialize, userId:{}, mcpId:{}, errorType:{}",
                        userId, mcp.getId(), exception.getClass().getSimpleName());
            }
        }

        return result;
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
    }

    public void softDelete(String uuid) {
        UserMcp userMcp = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), A_USER_MCP_SERVER_NOT_FOUND);
        baseMapper.deleteById(userMcp.getId());
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
