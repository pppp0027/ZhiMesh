# Design: 财务报销助手三件套（第三个领域 Agent）

## Goal

按 2026-09-13 领域 MCP 模式新增第三个领域角色：财务报销助手 = 财务系统 mock MCP +
《财务报销制度》知识库文档 + 领域提示词。演示"制度进知识库、数据进工具"的分流原则。

## Scope

- In: `mcp-servers/zhimesh-finance/`（4 工具）、smoke.js 增 finance 段、`demo/财务报销制度.md`、
  seed.sql 追加第 4 节（adi_mcp ×1 + adi_user_mcp ×1 + adi_character ×1，幂等追加，不影响既有 6 条）、README 更新
- Out: Java/前端零改动；不做 EXECUTIVE 权限演示（用户未选）；不动 hr/helpdesk server

## 工具契约（钉死）

`zhimesh-finance`：`search_expense_reports(status?, employee_name?)` / `submit_expense_report(employee_name, category, amount, expense_date, description, invoice_count)`（写）/ `get_budget_balance(department?)` / `get_reimbursement_summary(employee_name)`

- 报销单号 EX-YYYYMMDD-NNN（进程内自增）；data.today=2026-09-14；新单写回列表同轮可查
- 类别与单笔上限（与手册一致）：差旅住宿 500 / 差旅交通 2000 / 出差餐补 100 / 办公用品 1000 / 培训费 5000
- 校验顺序：员工在册在职 → 类别合法 → 日期格式且 2026-01-01 ≤ 日期 ≤ 2026-09-14 → 金额 >0 且 ≤上限 → 发票 ≥1；失败返回「错误：…」文本不抛异常
- 员工名册与 HR server 对齐（同工号同状态）；离职员工（曹阳/谢鹏）提交被拒

## 数据锚点（钉死，手册/剧本/断言统一引用）

- 张三 EX-20260910-001 差旅住宿 ¥480 待审批（北京出差，发票 1 张）
- 张三本年度 3 单共 ¥2380（1200 交通已报销 + 700 办公用品已报销 + 480 待审批）
- 部门 Q3 预算：技术部 50000/已用 32400/剩 17600；产品部 20000/8600/11400；人事部 12000/4100/7900；财务部 10000/3300/6700；市场部 30000/21500/8500
- 报销单数据约 9 条覆盖三状态，含 1 条已驳回（超标准未附特批）

## Risks

- seed.sql 追加须保持幂等（uuid 键）且不破坏既有 6 条；README/smoke 为增量编辑

## Test Strategy

- smoke 新增 finance 段：listTools 集合断言 + 每工具 happy path + 写校验失败（超上限 600 住宿、离职员工、零发票）+ 建单后立即可查
- seed 重跑幂等 + MCP postgres 复核

## WorkerHelper Impact

- Need worker-sync: no
