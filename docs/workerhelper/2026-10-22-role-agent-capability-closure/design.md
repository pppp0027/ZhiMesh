# Design: 角色 Agent 能力闭合（role-agent-capability-closure）

## Goal

前两轮改造（character-agentic-upgrade、agent-collab-tools）完成了引擎层：工具循环、挂起-恢复、审批卡、tool_policy、trace 全部就绪。但共库实测证明角色层四个缺口让角色"办不成职责内的事"：知识（制度 KB 空转）、身份（调用者不注入）、约束（写操作防护不一致）、验收（无角色级度量）。本轮把这四层补齐，并为 8 个系统预设建立"岗位说明书"式角色规格——agentic = 定位 × 能力面 × 约束 × 反馈，本轮闭合后三层。

## Scope

- In:
  - M1 制度知识库落地：3 个 is_system 库（《员工手册》《IT 服务手册》《财务报销制度》）+ 文档灌入 + 预设 system_kb_ids 回填
  - M2 调用者身份：system message 身份行注入 + tool_policy argConstraints 参数域装饰器（MCP 写操作限本人）
  - M3 写操作防护统一：HR submit_leave_request、IT 新建工单补审批硬门；三领域提示词改结构化确认口径
  - M4 预设剧本化：8 预设按五栏模板（职责边界/任务剧本/能力清单/权限模型/失败兜底）重写；run_workflow 描述携带可见清单；fetch 降级口径
  - M5 角色级 eval：三领域各 ≥10 条固定剧本 + 跑分报告（复用 RAGAS 服务器通道）
- Out（明确不做，后续演进）:
  - 计划-执行（todo 规划）长任务模式、跨会话长期记忆、ask_character 角色互调（原 P2/P3 scope）
  - mock MCP 换真实系统、多租户员工权限体系（审批人=对话用户本人，同 agent-collab-tools 口径）
  - 工作流 AgentNode 链路行为变更；非 agentic 回退路径的任何变化

## Assumptions

- 演示口径的身份映射：adi_user.name 与 mock 花名册员工同名即视为同一人；映射失败（名字不在册）时 MCP 自行返回"查无此人"，模型转述，不阻断
- 制度文档由人工起草、管理端建库上传（019 起 is_system 列与索引已就绪），但**内容口径必须与 mock 校验规则一致**（假期额度、报销类别上限、紧急度判定），否则检索答案与工具校验互相打架
- 系统 KB 按 preset 关系运行时解析（CharacterService#getCurrentSystemKbIds :718、编辑剥离 :577-583），预设回填即对全部存量实例生效，无需逐实例 UPDATE
- 身份行注入仅 agentic 生效路径；`default-agentic-enabled=false` 回退口径逐字节不变，另设 `zhimesh.agent.caller-identity-injection-enabled`（默认 true）独立开关
- argConstraints 仅约束 MCP 工具（employee_name 是 MCP 参数）；内置工具不适用

## Current Behavior（本轮代码扫描 + 共库实测，src: scan + DB）

- 三个领域预设 system_kb_ids 均为 ''（043 注释"制度文档知识库尚未创建"）→ 提示词第一条准则（检索《员工手册》等）空转；filteredKb 为空时 search_knowledge 不注册（buildBuiltinTools @ CharacterChatService:939）
- 调用者身份零注入：PromptUtil.createPrompt 无用户字段；system message = ai_system_message + "\n\n" + dateContext（CharacterChatHelper:597-600）；ToolContext.user 已携带 User 但仅内置工具可见，MCP 工具 employee_name 全自由参数 → 任何用户可查任何人考勤/假期/账号、可替任何人提交请假/报销
- 写操作防护不一致：财务 submit_expense_report 有 approvalRequiredMcpTools 硬门（044 种子），HR submit_leave_request 与 IT 新建工单仅提示词软确认；ask_user 全库真实调用仅 5 次
- 通用五预设为一句人设（96-149 字符），无任务剧本/输出契约；fetch 生产失败率 46%（50 调用 23 失败：跨境网络 + robots.txt）；run_workflow 50/50 失败（~~模型猜不存在标题——工具描述不携带可见清单~~ **2026-10-23 修正：此断言有误**——trace 实查 50 次失败全部为「未找到标题为「并发压测检索查询」…可用工作流标题：ZhiMesh简历筛选」，即 10-03 SSE 压测产物（压测提示词被当标题），拒绝消息本身携带可见清单；动态清单描述自 0611fe3a 已实现、有测试、已部署，T5② 无需再开发）
- loop_limit_reached 真实发生 8 次；角色级任务成功率无度量（trace 有、评估无）

## Proposed Behavior

1. **制度知识库**：管理端建 3 个 is_system=true 库并上传制度文档；迁移 045 按库标题幂等回填三预设 system_kb_ids；领域角色政策问题走 search_knowledge 命中条文并出引用
2. **身份行注入**：system message 追加 `"\n当前对话用户：{name}。以该身份理解"我/本人"的指代；为其办理业务时以其姓名填写员工姓名类参数。"`（dateContext 同点，CharacterChatHelper:597）；取当前用户沿 ThreadContext.getCurrentUser() 既有口径；开关关闭时不追加，逐字节回到现状
3. **参数域装饰器**：tool_policy 新增第三键 argConstraints：`{"工具名": {"参数名": "@caller"}}`；新建 ArgScopeDecorator（仿 ApprovalRequiredDecorator implements ToolExecutor）在装配期包装命中 MCP 工具——参数值 ≠ 当前用户名时拒绝执行，返回"该业务仅限本人办理（当前用户：X），无法为 Y 代办"结果消息，模型转述；trace 记录 ARG_SCOPE 拒绝形态。HR 种子 submit_leave_request.employee_name=@caller，财务 submit_expense_report 同
4. **写操作审批统一**：HR submit_leave_request、IT 新建工单（create 工具名以 data.js 实测为准）进 approvalRequiredMcpTools 种子；三领域提示词统一口径："写操作前必须 ask_user 逐项确认关键信息（不得在正文口头确认），需审批的工具走审批卡"
5. **预设剧本化**：8 预设重写为五栏结构（职责边界 / 任务剧本 5-10 条含越权拒绝与查无依据兜底 / 能力清单 KB+MCP+workflow / 权限模型本人边界 / 失败兜底）；RunWorkflowTool 的 spec description 动态拼接当前可见工作流标题清单（装配时已知，零额外查询）——**2026-10-23 核实：该子项自 0611fe3a 已实现（目录+必填门卫+相近标题提示，RunWorkflowToolTest 覆盖），T5 仅剩提示词重写**；联网研究员提示词写明 fetch 失败降级 Brave 的口径
6. **角色级 eval**：剧本 JSON（三领域各 ≥10 条：政策引用断言、数据查询断言、业务办理全链路断言、越权拒绝断言、审批路径断言）+ 跑分脚本落 scripts/role-eval/，走 server-eval 既有通道执行，产出任务成功率报告入 docs/reports/
7. SSE 事件、trace 表结构、checkpoint 机制、quota 口径全部不动

## Implementation Direction

按里程碑每步独立可上线：

- **M1 知识落地（数据+运维为主，零后端代码）**：建库灌文档 → 045 回填 → 领域角色政策问题出引用
- **M2 身份与参数域（后端）**：045 种子（argConstraints + HR/IT 审批标记）→ CharacterToolPolicy 解析 → 身份行注入 → ArgScopeDecorator 装配。**装饰器叠加顺序：ApprovalRequired 在外层、ArgScope 在内层**（先过审批门再审参数域，审批凭证 hash 基于模型原始参数，不被装饰器改写）
- **M3 审批统一（种子+提示词）**：与 M2 同批迁移，提示词改动并入 M4 批次
- **M4 剧本化（提示词+一处代码）**：8 预设幂等 UPDATE（并入 045）+ RunWorkflowTool description 动态清单
- **M5 eval（脚本）**：剧本 + 跑分 + 基线报告

## Affected Files

- `server/db_migration/045_role_agent_capability_closure.sql`（新建）：system_kb_ids 按标题回填、tool_policy 幂等 UPDATE（HR/IT 审批标记 + argConstraints）、8 预设 ai_system_message 剧本化重写
- `zhimesh-common/.../languagemodel/tool/CharacterToolPolicy.java`：argConstraints 解析（坏 JSON fail-safe 沿现口径）
- `zhimesh-common/.../languagemodel/tool/ArgScopeDecorator.java`（新建）
- `zhimesh-common/.../service/CharacterChatService.java`（discoverRequestTools/装配一带：装饰器包装与 identity 开关）
- `zhimesh-common/.../util/CharacterChatHelper.java`（:597 身份行）
- `zhimesh-common/.../languagemodel/tool/RunWorkflowTool.java`（description 动态清单）
- `zhimesh-common/.../config/ZhiMeshProperties.java`（Agent 组：callerIdentityInjectionEnabled）
- `scripts/role-eval/`（新建，剧本 + 跑分脚本）；`docs/workerhelper/feature-routes/server-backend.md`（worker-sync）
- mcp-servers 三件套 **零改动**（约束在 ZhiMesh 侧装饰器，MCP schema 不动）

## Risks

- 身份行改变全部 agentic 角色的 system message → 行为回归风险：独立开关可关 + eval 剧本前后对比锁行为
- 合理代办场景（主管替下属提请假）被硬拒 → 演示口径明示"仅限本人"；需要放开时清空该角色 argConstraints 即可（策略数据驱动，不改代码）
- 装饰器叠加顺序错误（ArgScope 在外先拒绝，审批凭证 hash 口径混乱）→ 叠加顺序单测锁死
- 制度文档与 mock 校验漂移（检索说能报 600、工具按 500 拒）→ 文档起草 checklist 以三件套 data.js 校验规则为唯一事实源，eval 剧本含交叉断言
- 提示词批量重写的存量会话 regenerate 行为变化 → 剧本验收覆盖 regenerate 路径
- eval 依赖 mock 数据与预设提示词版本 → 剧本仓内固定 data.json 快照与提示词 hash

## Test Strategy

- 单测（MP 测试模式沿用）：
  - CharacterToolPolicy：argConstraints 解析（正常/缺省/坏 JSON fail-safe 默认空）
  - ArgScopeDecorator：等值放行 / 不等拒绝且消息含双方姓名 / 参数缺失拒绝 / trace 记录 / 与 ApprovalRequiredDecorator 叠加顺序（审批凭证基于原始参数）
  - 身份行：开关两态、user 为 null 降级不追加、与 dateContext 拼接顺序
  - RunWorkflowTool：description 含清单标题；清单空时不注册（现口径不变）
  - 045 幂等两遍
- 集成验收（dev 库 + 三领域剧本）：政策问题出引用、"查我的假期余额"零自报姓名、"替张三办"被拒话术、请假/报销/工单 ask_user+审批卡全链路、历史回放、regenerate
- 回归：`mvn -B test` 全绿；user-web `pnpm build` 绿；`default-agentic-enabled=false` 回退口径单测不变
- M5：三领域剧本跑分产出基线报告

## WorkerHelper Impact

- Need worker-sync: yes（chat 主链路装配扩展、tool_policy 语义扩展、角色系统预设种子、Agent 配置组新键、迁移 045）
- Affected routes: 会话与消息（SSE 聊天）、角色系统、MCP（装饰器）、配置骨架（zhimesh.agent.*）

## Acceptance Criteria

1. 领域角色问"年假有几天/差旅住宿标准"→ search_knowledge 命中对应系统 KB，引用弹窗含条文出处
2. "帮我查下我的假期余额"零自报姓名一步出数；当前用户李四让助手"帮张三提交请假申请/报销单"被 ArgScopeDecorator 拒绝并礼貌转述仅限本人；读操作（查他人考勤/假期/账号）本轮保持开放（演示口径），是否收口留待 T5/T6 裁决
3. 请假/报销/工单三类写操作全部走 ask_user 结构化确认 + 审批卡；正文口头确认不再出现（剧本断言）
4. run_workflow 目标不存在时错误消息含当前可见工作流清单（模型不再盲猜标题）
5. 8 预设提示词均含五栏结构；越权/查无依据/工具失败三类剧本有明确兜底话术
6. 角色级 eval 基线报告产出（三领域各 ≥10 剧本，通过率入 docs/reports/）
7. `default-agentic-enabled=false` 回退逐字节等价；`caller-identity-injection-enabled=false` 时 system message 与现状逐字节一致
