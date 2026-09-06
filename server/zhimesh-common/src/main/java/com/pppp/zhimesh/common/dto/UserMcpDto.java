package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.dto.mcp.UserMcpCustomizedParam;
import com.pppp.zhimesh.common.entity.Mcp;
import lombok.Data;

import java.util.List;

@Data
public class UserMcpDto {
    private Long id;

    private String uuid;

    private Long userId;

    private Long mcpId;

    private List<UserMcpCustomizedParam> mcpCustomizedParams;

    private Boolean isEnable;

    private Mcp mcpInfo;
}
