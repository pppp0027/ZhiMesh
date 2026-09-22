# Execution Plan: 领域专属 MCP 工具 + Agent 三件套

## Summary

自研 2 个 Node stdio MCP server（zhimesh-hr / zhimesh-helpdesk，内置中文 mock 数据）+ smoke 验证脚本，
配幂等 seed.sql（adi_mcp ×2 + adi_user_mcp ×2 + adi_character ×2 含领域系统提示词）、JDBC seed runner、
2 份 mock 知识库文档与 README。Java/前端零改动。

## Tasks

- [x] T1: `mcp-servers/` 两个 MCP server + 根级依赖 + smoke 脚本，npm install 跑通 smoke
  - Files: `mcp-servers/package.json`、`mcp-servers/zhimesh-hr/{index.js,data.json}`、`mcp-servers/zhimesh-helpdesk/{index.js,data.json}`、`mcp-servers/smoke.js`
  - Change: 按钉死的工具契约实现 9 个工具；mock 数据符合"年假 5/10/15"口径；写操作校验返回错误文本
  - Verify: `npm install`（失败回退 npmmirror）→ `node smoke.js` 全部断言 PASS（主线程复跑确认）
- [x] T2: seed.sql + JDBC runner + mock 知识库文档 + README
  - Files: `mcp-servers/demo/{seed.sql,JdbcSeedRunner.java,员工手册.md,IT服务手册.md}`、`mcp-servers/README.md`
  - Change: 幂等 seed（uuid 键，三表全默认值兜底）；runner 连接参数走命令行不落密钥；两份手册与 mock 数据口径一致；README 含安装/注册/演示剧本
  - Verify: 人工审查 SQL 与现有行风格一致；手册口径核对（年假规则、工单 SLA 与工具校验一致）
- [x] T3: 主线程执行 seed 入库 + 复核
  - Files: 无新增（执行 `mcp-servers/demo/seed.sql`）
  - Verify: JDBC runner 执行成功 + MCP postgres 复核三表（adi_mcp 2 行 is_enable=true、adi_user_mcp user 1 各 1 行、adi_character 2 行 mcp_ids 正确）；重复执行验证幂等

## Verification

- Commands: `node mcp-servers/smoke.js`；`java -cp <pg-driver> demo/JdbcSeedRunner.java <url> <user> <pwd> demo/seed.sql`；MCP postgres 复核查询
- Manual checks: 用户重启 :9999 → pppp 账号 → 人事助手问"张三还有几天年假"（应走 search/get_leave_balance 工具）→ "帮我请 3 天年假"（写操作先确认再 submit_leave_request，回报单号）→ "筛一下这份简历"（run_workflow 目录出现 ZhiMesh简历筛选）；IT 服务台同理

## Task Relationships

- Strongly related: none
- Weakly related: T1 + T2（工具名/数据口径已由主线程钉死，可并行；互不触碰对方文件）
- Independent: T3（依赖 T1/T2 完成）
- Conflict risks: none（文件集不相交；T1/T2 由两个并行子代理执行，主线程审查整合）

## WorkerSync

- Need worker-sync: no（零 Java/前端路由改动）

## Risks

- npm 网络问题 → npmmirror 回退（T1 内处理）
- seed 需要用户重启后端才能生效于对话（交付说明中明确）
