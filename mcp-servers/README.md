# mcp-servers — ZhiMesh 领域 MCP 演示服务与种子数据

本目录包含三个自研 mock MCP server（stdio 传输）与一套演示种子数据，用于在 ZhiMesh 中搭建「领域数字员工」演示：

- **zhimesh-hr**：智聘人事系统（mock）——员工花名册、组织架构、假期余额、考勤记录查询与请假申请提交；
- **zhimesh-helpdesk**：智聘IT服务台（mock）——工单查询与创建、账号/VPN/邮箱状态查询、可申请软件目录；
- **zhimesh-finance**：智聘财务系统（mock）——报销单查询与提交、部门预算余额、个人年度报销汇总。

## 1. 简介与架构定位

ZhiMesh 角色通过三张表绑定 MCP：`adi_mcp`（管理员目录）→ `adi_user_mcp`（用户启用行）→ `adi_character.mcp_ids`（角色绑定，逗号分隔的 `adi_mcp.id`；系统预设模板 `adi_character_preset.mcp_ids` 不复制到实例，而是经预设关系在运行时解析合并）。**预设配套 MCP 需用户在 MCP 页自行启用后生效（2026-09-23 起不再自动启用，用户 MCP 目录只含用户自选）。**一个完整的领域角色由「三件套」组成：

```
┌──────────────────────────────────────────────────────────────────┐
│                     ZhiMesh 领域角色「三件套」                       │
│                                                                  │
│  领域系统提示词 ── 角色行为准则（怎么做事）                           │
│  （adi_character.ai_system_message）                              │
│                                                                  │
│  领域 MCP ────── 外部能力层（能做什么：查询/写入业务系统）            │
│  （adi_mcp → adi_user_mcp → adi_character.mcp_ids）               │
│    zhimesh-hr  ｜  zhimesh-helpdesk  ｜  zhimesh-finance         │
│                                                                  │
│  领域知识库 KB ── 信息层（知道什么：制度文档检索）                    │
│  （adi_character.kb_ids → 激活 search_knowledge 工具）             │
│    员工手册.md  ｜  IT服务手册.md  ｜  财务报销制度.md           │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼  编排层（多步骤任务）
              工作流「ZhiMesh简历筛选」← 人事助手额外联动
```

分层分工：**MCP = 外部能力层**，把外部系统封装成角色可调用的工具；**工作流 = 编排层**，把多步骤任务串成可复用流程；**知识库 = 信息层**，提供可检索的制度与事实依据。

「人事助手（小智）」是三件套 + 工作流的完整示例：提示词管行为准则、人事 MCP 管真实（mock）数据、员工手册知识库管制度依据、简历筛选工作流管多步编排；「IT 服务台助手」则是提示词 + IT 服务台 MCP + IT 服务手册知识库的标准三件套；「财务报销助手」同为标准三件套：财务系统 MCP 管报销单与预算数据、《财务报销制度》知识库管制度依据。

## 2. 目录结构

```
mcp-servers/
├── demo/                     # 演示种子数据与知识库文档（本目录）
│   ├── seed.sql              # MCP 注册 + 用户启用 + 角色创建（幂等，共 9 条语句）
│   ├── JdbcSeedRunner.java   # 种子执行器（Java 17 单文件源码启动，连接参数全来自命令行）
│   ├── 员工手册.md            # 人事领域知识库文档（通过 UI 上传）
│   ├── IT服务手册.md          # IT 领域知识库文档（通过 UI 上传）
│   └── 财务报销制度.md        # 财务领域知识库文档（通过 UI 上传）
├── zhimesh-hr/               # 人事系统 mock MCP server（stdio）
├── zhimesh-helpdesk/         # IT 服务台 mock MCP server（stdio）
├── zhimesh-finance/          # 财务系统 mock MCP server（stdio）
├── smoke.js                  # MCP 冒烟测试脚本
├── package.json              # 依赖声明（@modelcontextprotocol/sdk、zod）
└── node_modules/
```

## 3. 安装

在 `mcp-servers/` 目录下执行：

```bash
npm install
```

网络受限环境使用国内镜像：

```bash
npm install --registry=https://registry.npmmirror.com
```

安装完成后 `zhimesh-hr/index.js`、`zhimesh-helpdesk/index.js` 与 `zhimesh-finance/index.js` 才能运行（seed 中 `adi_mcp.stdio_arg` 记录的就是这些文件的绝对路径）。

安装后可运行冒烟测试自检（应输出 36 条 PASS）：

```bash
node smoke.js
```

## 4. 注册到系统（执行 seed）

在 `mcp-servers/` 目录下执行（**jdbc 连接地址、用户名、密码均自备**，不会写入本仓库任何文件）：

```bash
java -cp "<postgresql-42.6.1.jar 路径>" demo/JdbcSeedRunner.java \
  jdbc:postgresql://<host>:5432/<db> <user> <pwd> demo/seed.sql
```

说明：

- 驱动 jar 路径自备；本机 maven 仓库实际路径为
  `C:/Users/p'p'p'p'/.m2/repository/org/postgresql/postgresql/42.6.1/postgresql-42.6.1.jar`（路径含单引号，在 Git Bash 中请用双引号包裹整个 -cp 参数）。
- `seed.sql` 幂等（以固定 uuid / (user_id, mcp_id) 为键的 `INSERT ... SELECT ... WHERE NOT EXISTS`），可重复执行；共 9 条语句，逐条打印「OK 第N条」，结束打印成功条数汇总。
- seed 做了三件事：注册三个 MCP 到 `adi_mcp`；为 user_id=1（用户 pppp）启用这三个 MCP（`adi_user_mcp`）；创建「人事助手（小智）」「IT 服务台助手」「财务报销助手」三个**系统预设角色**（`adi_character_preset`，`is_system=true`，已绑定对应 MCP），在用户端「添加角色 → 预设角色」中对所有用户可见，首次使用时自动实例化到用户名下。预设角色绑定的 MCP 不会自动启用（2026-09-23 起，用户 MCP 目录只含用户自选）：seed 里的启用仅针对 user_id=1，其他用户须在 MCP 页自行启用后预设工具才生效。
- 执行成功后**重启 ZhiMesh 后端**，MCP 目录与预设角色才会生效。
- 老版 Windows cmd 控制台若中文输出乱码，先执行 `chcp 65001`（只影响控制台显示）。
- **Windows 本机跑 ZhiMesh 后端连 stdio MCP 时，JVM 必须加 `-Dfile.encoding=UTF-8`**：langchain4j 的 stdio 传输（JsonRpcIoHandler）用平台默认字符集构造流，Java 17 在 Windows 默认 GBK，中文工具参数与结果会双向乱码、按参数匹配的工具直接失配（T8 集成剧本 2026-09-23 实测）。`chcp 65001` 救不了这个——乱码发生在 JVM 与 MCP 子进程的管道里，与控制台代码页无关。生产 Linux 默认 UTF-8 不受影响。

## 5. 知识库装配

seed 只建了预设角色与 MCP 绑定，知识库（`system_kb_ids` 留空）需要后续装配：

1. 管理员在知识库管理中创建**系统知识库**（如「员工手册」），上传 `demo/员工手册.md`；
2. 再新建系统知识库（如「IT 服务手册」），上传 `demo/IT服务手册.md`；
3. 再新建系统知识库（如「财务报销制度」），上传 `demo/财务报销制度.md`；
4. 由管理员把系统知识库回填到对应预设的 `system_kb_ids`（或在管理端编辑预设绑定）：「人事助手（小智）」←「员工手册」；「IT 服务台助手」←「IT 服务手册」；「财务报销助手」←「财务报销制度」；
5. 注意：**绑定系统知识库后，经预设实例化的角色才会激活 `search_knowledge` 工具**；不绑定则角色只能调 MCP 工具、无法检索制度文档。个人用户也可在自己的角色编辑里另绑私有知识库。

## 6. 演示剧本

以下 5 组话术可直接逐字输入演示（日期以 9 月为例；mock 数据为内存态，写操作与「重启重置」特性见第 7 节）。

### a. 查假期余额（人事助手，工具查询）

> 「张三还有几天年假？」

预期：调 `get_leave_balance` 查询，回答张三的年假**剩余 8 天**（并注明数据来源为人事系统），不凭记忆作答。

### b. 提交请假申请（人事助手，写操作先确认）

> 「帮我给张三请 3 天年假，从 9 月 22 日开始，事由回老家探亲」

预期：先复述确认关键信息（为谁申请、类型=年假、开始日期、天数、事由），用户确认后调 `submit_leave_request`，成功后回报 **LV- 开头的申请单号**。

### c. 制度咨询（人事助手，知识库检索）

> 「公司年假有几天？」

预期：检索「员工手册」知识库，按工龄规则作答（**不满 5 年 5 天、满 5 年 10 天、满 10 年 15 天**）并注明依据为《员工手册》，不编造条款。

### d. 工单查询（IT 服务台助手，工具查询）

> 「帮我查下我的 VPN 工单处理得怎么样了，我是张三」

预期：调 `search_tickets` 查询，报告张三 **9 月 8 日提交的 VPN 工单当前状态为处理中**，并给出后续跟进建议。

### e. 财务报销（财务报销助手：查询 / 写操作确认 / 制度拦截）

**e1. 查报销进度（工具查询）**

> 「张三上周的住宿报销到账了吗？」

预期：调 `search_expense_reports` 查询（报销人=张三、状态=待审批），报告 **EX-20260910-001（差旅住宿 ¥480，9 月 10 日提交）当前状态为待审批**，并提醒按《财务报销制度》审批通过后 3 个工作日内到账。

**e2. 提交报销单（写操作先确认）**

> 「帮我报销上周北京出差的住宿，480 元，发票 1 张，费用日期 9 月 10 日」

预期：先逐项确认报销人、类别=差旅住宿、金额 ¥480、费用日期、事由说明、发票张数，用户确认后调 `submit_expense_report`，成功后回报 **EX-20260914-001** 报销单号。

**e3. 超上限拦截（写操作被制度拦下）**

> 「帮我报销住宿 680」

预期：提示 ¥680 超出差旅住宿单笔上限 ¥500（一线城市 500 元/晚标准），需调整金额、拆分提交或走财务负责人线下特批，不调用 `submit_expense_report`。

## 7. 注意事项

- **mock 数据为内存态**：三个 server 的写操作（请假申请、创建工单、提交报销单）只存在内存中，server 进程重启后数据重置回内置演示数据。
- **stdio_arg 为绝对路径**：`adi_mcp.stdio_arg` 记录了 `index.js` 的绝对路径（`D:/My-Study/iedaProjects/langchain4j-zhimesh/mcp-servers/...`）。仓库移动位置后需同步修改 `adi_mcp`（管理后台编辑或手工 UPDATE），否则 MCP 启动失败。
- **MCP server 需先安装依赖**：后端拉起 MCP 进程前，请确认已在 `mcp-servers/` 下执行过 `npm install`（见第 3 节）。
- **演示数据口径**：员工姓名（如张三）、假期余额、工单编号、报销单号等 mock 口径以 server 内置数据为准，知识库文档（员工手册、IT 服务手册、财务报销制度）已与其对齐。

### 工具清单速览

| server | 工具 | 说明 |
| --- | --- | --- |
| zhimesh-hr | search_employee | 按姓名/部门/工号查询员工花名册 |
| zhimesh-hr | get_department_tree | 查询组织架构树 |
| zhimesh-hr | get_leave_balance | 查询假期余额 |
| zhimesh-hr | list_attendance_anomalies | 查询考勤异常记录 |
| zhimesh-hr | submit_leave_request | 提交请假申请（写操作） |
| zhimesh-helpdesk | search_tickets | 按报告人/类别/状态查询工单 |
| zhimesh-helpdesk | get_account_status | 查询账号/邮箱/VPN 状态 |
| zhimesh-helpdesk | get_software_catalog | 查询可申请软件目录 |
| zhimesh-helpdesk | create_ticket | 创建工单（写操作） |
| zhimesh-finance | search_expense_reports | 按报销人/类别/状态查询报销单 |
| zhimesh-finance | get_budget_balance | 查询部门预算余额 |
| zhimesh-finance | get_reimbursement_summary | 查询个人年度报销汇总 |
| zhimesh-finance | submit_expense_report | 提交报销单（写操作） |
