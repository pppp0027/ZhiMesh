# Execution Plan: 财务报销助手三件套

## Summary

第三个领域 mock MCP server（zhimesh-finance，4 工具）+《财务报销制度》手册 + seed.sql 追加注册（MCP/用户启用行/角色）+ README/smoke 增量更新。Java/前端零改动，完全复用 2026-09-13 模式。

## Tasks

- [x] T1: zhimesh-finance server + smoke 增 finance 段
  - Files: `mcp-servers/zhimesh-finance/{index.js,data.json}`、`mcp-servers/smoke.js`（追加 testFinance）
  - Change: 按钉死契约实现 4 工具；数据锚点对齐；错误「错误：…」文本哲学一致
  - Verify: `node smoke.js` 全绿（主线程复跑）
- [x] T2: 手册 + seed 追加 + README 更新
  - Files: `mcp-servers/demo/财务报销制度.md`、`mcp-servers/demo/seed.sql`（追加第 4 节，3 个新固定 uuid）、`mcp-servers/README.md`
  - Change: 手册口径=类别上限表+审批流+驳回原因；角色提示词钉死文本；README 三 server 叙事+剧本 e
  - Verify: SQL 幂等自查 + 手册口径逐项核对（不连库）
- [x] T3: 主线程审查 → smoke 复跑 → seed 入库 → MCP postgres 复核（幂等重跑）

## Verification

- `node smoke.js`；JdbcSeedRunner 执行 seed.sql；MCP postgres 复核 adi_mcp 17/adi_user_mcp 22/adi_character 80

## Task Relationships

- Weakly related: T1 + T2（契约/锚点已钉死，文件集不相交，两子代理并行）
- Independent: T3
- Conflict risks: none

## WorkerSync

- Need worker-sync: no

## Risks

- seed.sql 为共享文件：T2 仅追加第 4 节，不得改动既有 6 条语句
