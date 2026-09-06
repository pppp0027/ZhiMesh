package com.pppp.zhimesh.chat.controller.externalapi.v1;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.UserMcpDto;
import com.pppp.zhimesh.common.dto.mcp.McpPublicInfo;
import com.pppp.zhimesh.common.entity.Mcp;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.service.McpService;
import com.pppp.zhimesh.common.service.UserMcpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "External API - MCP")
@RestController
@RequestMapping("/ext/v1/mcp")
public class ExtMcpController {

    @Resource
    private UserMcpService userMcpService;

    @Resource
    private McpService mcpService;

    @Operation(summary = "List all supported MCP services in the system | 获取系统支持的全部 MCP 服务列表")
    @GetMapping("/supported")
    public List<McpPublicInfo> supported() {
        List<Mcp> mcpList = mcpService.listAllEnabled();

        List<McpPublicInfo> result = new ArrayList<>();
        for (Mcp mcp : mcpList) {
            result.add(toPublicInfo(mcp));
        }
        return result;
    }

    @Operation(summary = "List MCP services activated by current API Key | 获取当前 Key 已激活的 MCP 服务列表")
    @GetMapping("/active")
    public List<McpPublicInfo> active() {
        User user = ThreadContext.getCurrentUser();
        List<UserMcpDto> userMcpList = userMcpService.searchByUserIdForExtApi(user.getId());

        List<McpPublicInfo> result = new ArrayList<>();
        for (UserMcpDto dto : userMcpList) {
            if (dto.getMcpInfo() != null) {
                result.add(toPublicInfo(dto));
            }
        }
        return result;
    }

    private McpPublicInfo toPublicInfo(Mcp mcp) {
        McpPublicInfo info = new McpPublicInfo();
        info.setUuid(mcp.getUuid());
        info.setTitle(mcp.getTitle());
        info.setTransportType(mcp.getTransportType());
        info.setSseUrl(mcp.getSseUrl());
        info.setStreamableHttpUrl(mcp.getStreamableHttpUrl());
        info.setSseTimeout(mcp.getSseTimeout());
        info.setStdioCommand(mcp.getStdioCommand());
        info.setRemark(mcp.getRemark());
        info.setInstallType(mcp.getInstallType());
        return info;
    }

    private McpPublicInfo toPublicInfo(UserMcpDto dto) {
        Mcp mcp = dto.getMcpInfo();
        McpPublicInfo info = toPublicInfo(mcp);
        info.setIsEnable(dto.getIsEnable());
        return info;
    }
}
