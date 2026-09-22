-- ============================================================
-- ZhiMesh MCP 演示种子数据（seed.sql）
-- ============================================================
-- 用途：
--   1) 注册三个自研 mock MCP server 到 MCP 目录（adi_mcp）：
--      智聘人事系统（zhimesh-hr）、智聘IT服务台（zhimesh-helpdesk）、智聘财务系统（zhimesh-finance），
--      均为本地 stdio 服务；
--   2) 为 user_id=1（用户 pppp）启用这三个 MCP（adi_user_mcp）；
--   3) 创建三个领域演示角色（adi_character_preset 系统预设，is_system=true）：
--      人事助手（小智）、IT 服务台助手、财务报销助手，各自绑定一个 MCP（mcp_ids），
--      在用户端「添加角色 → 预设角色」中对所有用户可见；system_kb_ids 留空，
--      请随后由管理员建系统知识库并回填（见 README 第 5 节）：
--      财务报销助手对应上传《财务报销制度.md》并绑定。
--
-- 执行方式（在 mcp-servers 目录下执行，连接参数自备；也可用任何 SQL 客户端直接执行本文件）：
--   java -cp "<postgresql-42.6.1.jar 路径>" demo/JdbcSeedRunner.java ^
--     jdbc:postgresql://<host>:5432/<db> <user> <password> demo/seed.sql
--   （Windows cmd 用 ^ 续行，Linux/macOS 用 \；Git Bash 下建议写成一行）
--
-- 幂等说明：
--   全部语句为 INSERT ... SELECT ... WHERE NOT EXISTS，幂等键固定：
--     adi_mcp              以 uuid 为键；
--     adi_user_mcp         以 (user_id, mcp_id) 为键，mcp_id 由 uuid 子查询取得；
--     adi_character_preset 以 uuid 为键。
--   本文件可重复执行：已存在的行不会重复插入，也不会被更新。
--
-- 依赖：
--   - 数据库中已存在 adi_mcp / adi_user_mcp / adi_character_preset 三张表；
--   - zhimesh-hr / zhimesh-helpdesk / zhimesh-finance 三个 server 需先在 mcp-servers/ 下执行
--     npm install，使 index.js 可运行（SQL 本身不依赖 node，仅 adi_mcp.stdio_arg
--     记录了 index.js 的绝对路径）。
--
-- 本文件固定使用以下九个 uuid（32 位小写 hex，重复执行不变化）：
--   人事系统 MCP          1a6790d46b31224de2e4ab79a6857dec
--   IT 服务台 MCP         afd322b35d902f5e010928221aff7374
--   人事系统用户启用行    3f8b21c74d9e45a2b8f0d63c15a7e294
--   IT 服务台用户启用行   7c2d94e6a1f5408db3e7c92a58f10d63
--   人事助手预设          c40ac107cb1fb7e615288a4d119ce7c9
--   IT 服务台预设         0c044fcaf29eda3ef3027919f28b3b18
--   财务系统 MCP          b52f8ce417d94a6f8b2e07c5d3a19f48
--   财务系统用户启用行    9d41c7e2f5a84013b6d0927c4e5f81a2
--   财务报销助手预设      e68a3d15c2f749b0a1e8d4c3f52b7096
--
-- 历史说明：三个助手最初以 user_id=1 自建角色（adi_character）形式创建，
-- 2026-09 起 db_migration/043_seed_domain_agent_presets.sql 已将其转为系统预设，
-- 本文件的角色段同步改为直接写 adi_character_preset（幂等键 uuid 不变，两处可共存）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. MCP 目录（adi_mcp）
-- ------------------------------------------------------------

-- 1.1 智聘人事系统（mock，本地 stdio）
INSERT INTO adi_mcp (uuid, title, transport_type, sse_url, sse_timeout, stdio_command, stdio_arg, install_type, website, remark, is_enable)
SELECT '1a6790d46b31224de2e4ab79a6857dec', '智聘人事系统（mock）', 'stdio', '', 30, 'node',
       'D:/My-Study/iedaProjects/langchain4j-zhimesh/mcp-servers/zhimesh-hr/index.js', 'local', '',
       '智聘科技人事系统演示服务（mock 数据）：员工花名册、组织架构、假期余额、考勤记录查询与请假申请提交。本地 stdio 服务，写操作仅内存态，重启重置。',
       true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE uuid = '1a6790d46b31224de2e4ab79a6857dec');

-- 1.2 智聘IT服务台（mock，本地 stdio）
INSERT INTO adi_mcp (uuid, title, transport_type, sse_url, sse_timeout, stdio_command, stdio_arg, install_type, website, remark, is_enable)
SELECT 'afd322b35d902f5e010928221aff7374', '智聘IT服务台（mock）', 'stdio', '', 30, 'node',
       'D:/My-Study/iedaProjects/langchain4j-zhimesh/mcp-servers/zhimesh-helpdesk/index.js', 'local', '',
       '智聘科技 IT 服务台演示服务（mock 数据）：工单查询与创建、账号/VPN/邮箱状态查询、可申请软件目录。本地 stdio 服务，写操作仅内存态，重启重置。',
       true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE uuid = 'afd322b35d902f5e010928221aff7374');

-- ------------------------------------------------------------
-- 2. 用户启用（adi_user_mcp，user_id=1 启用上面两个 MCP）
--    注：adi_user_mcp.is_enable 默认 false，这里必须显式写 true
-- ------------------------------------------------------------

-- 2.1 启用人事系统 MCP
INSERT INTO adi_user_mcp (uuid, user_id, mcp_id, is_enable)
SELECT '3f8b21c74d9e45a2b8f0d63c15a7e294', 1, (SELECT id FROM adi_mcp WHERE uuid = '1a6790d46b31224de2e4ab79a6857dec'), true
WHERE NOT EXISTS (
    SELECT 1 FROM adi_user_mcp
    WHERE user_id = 1
      AND mcp_id = (SELECT id FROM adi_mcp WHERE uuid = '1a6790d46b31224de2e4ab79a6857dec')
);

-- 2.2 启用 IT 服务台 MCP
INSERT INTO adi_user_mcp (uuid, user_id, mcp_id, is_enable)
SELECT '7c2d94e6a1f5408db3e7c92a58f10d63', 1, (SELECT id FROM adi_mcp WHERE uuid = 'afd322b35d902f5e010928221aff7374'), true
WHERE NOT EXISTS (
    SELECT 1 FROM adi_user_mcp
    WHERE user_id = 1
      AND mcp_id = (SELECT id FROM adi_mcp WHERE uuid = 'afd322b35d902f5e010928221aff7374')
);

-- ------------------------------------------------------------
-- 3. 领域角色（adi_character_preset 系统预设，各绑定一个 MCP）
--    ai_system_message 含中文与换行，使用 dollar-quoting $prompt$ ... $prompt$，
--    提示词内不含 $ 字符；mcp_ids 为逗号分隔的 adi_mcp.id，这里单值直接取 id 文本
-- ------------------------------------------------------------

-- 3.1 人事助手（小智）：人事 MCP + 员工手册知识库 + 简历筛选工作流
INSERT INTO adi_character_preset (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT 'c40ac107cb1fb7e615288a4d119ce7c9', '人事助手（小智）',
       '人事政策咨询与业务办理数字员工（演示三件套：人事 MCP + 员工手册知识库 + 简历筛选工作流）',
       $prompt$你是智聘科技（ZhiMesh）的人事助手小智，一名专业的 HR 数字员工，为公司员工提供人事政策咨询与人事业务办理。

工作准则：
1. 政策与制度类问题（假期制度、报销标准、入离职流程等）：优先检索知识库中的《员工手册》等制度文档作答并注明依据；知识库没有依据时明确说明，不要编造制度条款。
2. 员工数据类问题（花名册、部门架构、假期余额、考勤记录）：调用人事系统工具（search_employee、get_department_tree、get_leave_balance、list_attendance_anomalies）查询真实数据作答，不要凭记忆回答任何具体员工信息。
3. 业务办理（如提交请假申请）属于写操作：执行前必须先与用户确认全部关键信息（为谁申请、请假类型、开始日期、天数、事由），确认无误后才调用 submit_leave_request 提交，成功后向用户回报申请单号。
4. 简历筛选与招聘需求：调用工作流「ZhiMesh简历筛选」处理；它需要的必填输入不齐全时，先向用户逐项索取，不要空值调用；如果该工作流包含对话无法传递的必填输入（如文件），引导用户到工作流页面手动运行。
5. 查询结果与用户描述不一致时，以系统数据为准，并温和提示用户核实。

回答使用简体中文，简洁专业；涉及具体数据时注明来源（知识库文档或人事系统）。$prompt$,
       '',
       (SELECT id::text FROM adi_mcp WHERE uuid = '1a6790d46b31224de2e4ab79a6857dec'),
       'professional', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE uuid = 'c40ac107cb1fb7e615288a4d119ce7c9');

-- 3.2 IT 服务台助手：IT 服务台 MCP + IT 服务手册知识库
INSERT INTO adi_character_preset (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT '0c044fcaf29eda3ef3027919f28b3b18', 'IT 服务台助手',
       'IT 支持数字员工（演示三件套：IT 服务台 MCP + IT 服务手册知识库）',
       $prompt$你是智聘科技（ZhiMesh）的 IT 服务台助手，一名专业的 IT 支持数字员工，帮助员工解决账号、VPN、网络、软件、硬件等 IT 问题。

工作准则：
1. IT 制度与常见问题（VPN 连接步骤、密码策略、软件申请流程等）：优先检索知识库中的《IT 服务手册》作答并注明依据；知识库没有依据时说明并给出一般性建议，不要编造公司内部流程。
2. 工单与账号查询：调用 IT 服务台工具（search_tickets、get_account_status、get_software_catalog）查询真实数据，不要凭记忆回答任何具体工单或账号信息。
3. 创建工单属于写操作：提交前先与用户确认标题、问题类别、紧急程度、问题描述、报告人；报告人信息可先用 get_account_status 核实，不要让用户重复提供。
4. 紧急程度由你根据影响面判断：个别员工无法工作为普通，部门级故障为紧急，全公司故障为危急；判断理由要向用户说明。
5. 简单问题按手册解答并视需要用 create_ticket 建单跟踪；复杂问题先建单，再指导用户临时规避。

回答使用简体中文，简洁专业，给出可执行的下一步操作。$prompt$,
       '',
       (SELECT id::text FROM adi_mcp WHERE uuid = 'afd322b35d902f5e010928221aff7374'),
       'service', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE uuid = '0c044fcaf29eda3ef3027919f28b3b18');

-- ------------------------------------------------------------
-- 4. 财务报销演示三件套（追加）：智聘财务系统 MCP + 用户启用 + 财务报销助手角色
-- ------------------------------------------------------------

-- 4.1 智聘财务系统（mock，本地 stdio）
INSERT INTO adi_mcp (uuid, title, transport_type, sse_url, sse_timeout, stdio_command, stdio_arg, install_type, website, remark, is_enable)
SELECT 'b52f8ce417d94a6f8b2e07c5d3a19f48', '智聘财务系统（mock）', 'stdio', '', 30, 'node',
       'D:/My-Study/iedaProjects/langchain4j-zhimesh/mcp-servers/zhimesh-finance/index.js', 'local', '',
       '智聘科技财务系统演示服务（mock 数据）：报销单查询与提交、部门预算余额、个人年度报销汇总。本地 stdio 服务，写操作仅内存态，重启重置。',
       true
WHERE NOT EXISTS (SELECT 1 FROM adi_mcp WHERE uuid = 'b52f8ce417d94a6f8b2e07c5d3a19f48');

-- 4.2 启用财务系统 MCP（幂等键同第 2 节：(user_id, mcp_id)，is_enable 显式写 true）
INSERT INTO adi_user_mcp (uuid, user_id, mcp_id, is_enable)
SELECT '9d41c7e2f5a84013b6d0927c4e5f81a2', 1, (SELECT id FROM adi_mcp WHERE uuid = 'b52f8ce417d94a6f8b2e07c5d3a19f48'), true
WHERE NOT EXISTS (
    SELECT 1 FROM adi_user_mcp
    WHERE user_id = 1
      AND mcp_id = (SELECT id FROM adi_mcp WHERE uuid = 'b52f8ce417d94a6f8b2e07c5d3a19f48')
);

-- 4.3 财务报销助手：财务系统 MCP + 财务报销制度知识库
INSERT INTO adi_character_preset (uuid, title, remark, ai_system_message, system_kb_ids, mcp_ids, type, is_system)
SELECT 'e68a3d15c2f749b0a1e8d4c3f52b7096', '财务报销助手',
       '财务报销数字员工（演示三件套：财务系统 MCP + 财务报销制度知识库）',
       $prompt$你是智聘科技（ZhiMesh）的财务报销助手，一名专业的财务数字员工，帮助员工完成报销政策咨询与报销单办理。

工作准则：
1. 报销政策与标准（差旅住宿标准、餐补、审批流程、到账时间等）：优先检索知识库中的《财务报销制度》作答并注明依据；知识库没有依据时明确说明，不要编造财务制度。
2. 报销单与预算查询：调用财务系统工具（search_expense_reports、get_budget_balance、get_reimbursement_summary）查询真实数据，不要凭记忆回答任何具体报销单或预算信息。
3. 提交报销单属于写操作：提交前必须与用户逐项确认报销人、类别、金额、费用日期、事由说明、发票张数；金额接近或超过类别标准时先提醒制度上限，确认无误后才调用 submit_expense_report，成功后回报报销单号。
4. 发票要求：所有类别报销均需至少 1 张发票；用户没说发票张数时要主动询问，不要替用户编造。
5. 查询结果与用户描述不一致时以系统数据为准，并温和提示核实；涉及已驳回的报销单，说明驳回原因并给出修正建议。

回答使用简体中文，简洁专业；金额一律带 ¥ 符号保留两位小数。$prompt$,
       '',
       (SELECT id::text FROM adi_mcp WHERE uuid = 'b52f8ce417d94a6f8b2e07c5d3a19f48'),
       'professional', true
WHERE NOT EXISTS (SELECT 1 FROM adi_character_preset WHERE uuid = 'e68a3d15c2f749b0a1e8d4c3f52b7096');
