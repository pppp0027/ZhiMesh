# Design: 领域专属 MCP 工具 + Agent 三件套（人事助手 / IT 服务台助手）

## Goal

让 ZhiMesh 的 Agent 演示从"通用工具的人手组合"升级为"领域 Agent 三件套"：每个旗舰角色拥有
**专属领域 MCP 工具 + 领域系统提示词 + 领域知识库**，其中人事助手额外联动已有
「ZhiMesh简历筛选」工作流，一个角色集齐 KB（信息层）/ MCP（外部能力层）/ 工作流（编排层）/ 记忆 四层能力。

用户已确认（2026-09-13）：做 **人事助手 + IT 服务台** 两个方向，2 个自研 MCP server。

## Scope

- In:
  - `mcp-servers/zhimesh-hr/`、`mcp-servers/zhimesh-helpdesk/`：Node stdio MCP server（官方 `@modelcontextprotocol/sdk`，内置中文 mock 数据，无密钥无网络）
  - `mcp-servers/package.json`（根级统一依赖，一次 npm install）+ `mcp-servers/smoke.js`（SDK client 连 server，listTools + 每工具 callTool 断言）
  - `mcp-servers/demo/seed.sql`（幂等：adi_mcp ×2、adi_user_mcp ×2(user 1)、adi_character ×2 含完整领域系统提示词）
  - `mcp-servers/demo/JdbcSeedRunner.java`（单文件 runner，连接参数全部来自命令行，不落密钥）
  - `mcp-servers/demo/员工手册.md`、`IT服务手册.md`（mock 知识库文档，走 UI 上传出 embedding——KB 数据不能 SQL 硬插）
  - `mcp-servers/README.md`（安装、注册、演示剧本）
- Out:
  - Java 侧零改动（MCP 注册/绑定/角色工具循环体系全部现成）
  - 不进 `server/db_migration/`（那是 schema 迁移，演示种子数据不进升级链路）
  - 不做 GitHub 现成领域 MCP 引入（Jira/Slack 类需真实凭据或内网服务，本地演示不可控；通用层目录已有 10 个）
  - 不做 CharacterPreset（预设模板）注册
  - mock server 写操作仅内存态，重启重置（README 注明）

## 关键机制（已核实）

- 角色拿 MCP 工具的链路：`adi_character.mcp_ids` → `UserMcpService.createMcpClients(userId, mcpIds)`
  → 要求存在 `(user_id, mcp_id, is_enable=true)` 的 `adi_user_mcp` 行 → `McpToolRegistry.listTools()` 进全局工具
- stdio 拉起：`stdio_command='node'` + `stdio_arg='D:/.../index.js'`（`splitArguments` 空格切分，路径无空格；env 继承宿主 + 注入自定义参数——现有 npx/uvx 条目跑通即证明）
- `adi_mcp.preset_params/customized_param_definitions` 为 jsonb 默认 `'[]'`；mock server 无参数，取默认
- 三表 NOT NULL 列全部有默认值，seed 只需显式写业务字段；幂等以 uuid 为键（32 位小写 hex，同现有风格）
- 「ZhiMesh简历筛选」工作流 user_id=1 且 public → 人事助手（user 1 的角色）可直接经 run_workflow 联动

## 工具契约（钉死，server 与角色提示词/文档统一引用）

zhimesh-hr：`search_employee` / `get_department_tree` / `get_leave_balance` / `list_attendance_anomalies` / `submit_leave_request`（写）
zhimesh-helpdesk：`search_tickets` / `create_ticket`（写）/ `get_account_status` / `get_software_catalog`

数据口径钉死（server mock 数据与员工手册.md 保持一致）：
- 年假规则：工龄 <5 年 5 天；5–10 年 10 天；>10 年 15 天（按入职日期推算）
- 写操作校验：请假天数超余额、工单类别/紧急度非法 → 返回错误文本供模型转述，不抛协议错误

## Risks

- npm 网络拉包慢/失败 → 回退 `--registry=https://registry.npmmirror.com`
- Node SDK 高层 API 版本差异 → 以 smoke 实测为准（客户端连上、列出工具、调用成功才算数）
- Windows 控制台中文显示乱码不影响 stdio JSON-RPC（UTF-8 通道）

## Test Strategy

- `node smoke.js`：对两个 server 各做 listTools 名称断言 + 每工具至少一条 happy-path callTool 断言（写工具断言返回单号/工单号）+ 至少一条校验失败路径断言
- seed 执行后 MCP postgres 工具复核三表行数与字段
- 端到端：用户重启 :9999，用 pppp 账号与两个角色对话，观察工具调用轨迹（后端不重启不加载新角色）

## WorkerHelper Impact

- Need worker-sync: no（零 Java/前端改动，不新增路由；mcp-servers/ 为运行时旁路目录）
