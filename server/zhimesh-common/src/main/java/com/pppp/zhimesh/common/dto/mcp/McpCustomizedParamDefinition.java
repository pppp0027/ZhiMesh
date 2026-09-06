package com.pppp.zhimesh.common.dto.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 待用户设置的MCP参数定义(个性化配置),用户设置后与mcp.common_params合并做为mcp的启动参数
 */
@Data
public class McpCustomizedParamDefinition {
    private String name;
    private String title;
    @JsonProperty("require_encrypt")
    private Boolean requireEncrypt;

    /**
     * 是否作为命令行参数传给 stdio MCP（true 时不再作为环境变量注入）。
     * 用于文件系统目录、SQLite 数据库路径、Git 仓库路径等无法通过环境变量传入的参数。
     */
    @JsonProperty("cli_arg")
    private Boolean cliArg;

    /**
     * 命令行参数前缀，例如 sqlite 使用 --db-path，git 使用 --repository。
     * 为空时仅追加用户填写的值（例如 filesystem 允许目录直接作为位置参数）。
     */
    @JsonProperty("cli_prefix")
    private String cliPrefix;
}
