# Design: 角色 Agent 协作化改造（agent-collab-tools，P0+P1）

## Goal

把角色 Agent 从"单轮全自动工具循环"升级为具备**协作类工具**的完整形态：
- P0：全角色（系统预设 + 自定义）默认工具集 + 角色级工具策略（tool_policy）+ MCP 工具纳入统一防护
- P1：挂起-恢复机制（跨请求 checkpoint）+ `ask_user` 澄清提问工具 + `request_human_approval` 审批工具

协作类工具是本次核心诉求：每个角色默认具备向用户提问、请求审批的能力，而不是靠模型在正文里自然语言转述。

## Scope

- In:
  - 默认工具集：所有角色默认注册 `search_knowledge`（绑库可用时）、`run_workflow`、`ask_user`、`request_human_approval`（实现按「全开 + 配置兜底」决策默认注册，tool_policy.builtinDenylist 可按角色禁用——初稿「按 tool_policy 启用」的 opt-in 措辞未采用）
  - `Character.tool_policy` JSON 列：内置工具 allowlist/denylist + MCP 工具级"需审批"标记
  - 存量全开：迁移把全部角色 `is_agentic` 置 true + 系统级兜底开关 `zhimesh.agent.default-agentic-enabled`（默认 true，可一键回退）
  - 挂起-恢复：`adi_agent_pending_checkpoint` 表存消息链快照；模型调用 ask_user/approval 时循环挂起、本轮 SSE 干净收尾；下一轮用户消息作为 ToolExecutionResultMessage 注入恢复循环；惰性 TTL 过期
  - `ask_user(question, options?)`：SSE 结构化事件 `agent_question`，前端渲染问题卡片，选项按钮 = 按钮文本走普通聊天提交
  - `request_human_approval(action, summary, risk_level)`：`approval_request` 事件 + 审批卡片（同意/拒绝 + 理由）；被 tool_policy 标记"需审批"的 MCP 工具执行前自动拦截转审批
  - 迭代预算跨挂起累计 + 同一工具链挂起次数上限，防无限追问
  - 挂起/恢复/审批全程记 ToolCallTrace，历史回放可见
  - MCP 工具纳入 timeout/截断防护：`zhimesh.agent.mcp-guardrails-enabled`（默认 false，灰度后开）
  - 043 领域预设种子补 tool_policy（财务助手 `submit_expense_report` 标记需审批）
- Out（明确不做，另立任务或后续演进）:
  - `ask_character` 角色互调 / 同步子代理（P2）
  - 异步 A2A 消息、后台 Agent 运行时、收件箱通知
  - 运行时 LLM-judge / groundedness 评估、整轮 token 预算、上下文压缩（P3）
  - 审批人角色体系（审批人 = 当前对话用户本人，不做多人审批流）
  - 深度提问嵌套（ask_user 恢复轮内再 ask_user 允许，但受挂起次数上限约束）
  - **工作流 AgentNode 链路的审批门控**：AgentNode 走 `LocalAgentService` 不经 CharacterChatService，tool_policy 审批标记不覆盖该入口（MCP guardrails 开关在 T7 后对该链路生效，但审批不生效）；ext API（`/ext/v1/character`）走同一 CharacterChatService，自动继承全部机制

## Assumptions

- ask_user 的用户回复复用现有 `/chat/process` 入口：自由文本或按钮提交的选项文本，**不新建 REST 端点**；pending 上下文随消息链带给模型，由模型理解用户答了什么
- pending 审批存在时，用户的新消息一律进入恢复流程（approve/reject 由前端按钮提交结构化文本，模型识别）；用户答非所问由模型自行追问
- 单会话（conversation）同一时刻最多一个活跃 checkpoint；新挂起产生前旧 pending 置 superseded
- checkpoint 存 MySQL（新表）而非 Redis：消息链大、需历史审计、与 `adi_character_message_tool_call` 同域；短期记忆"只存用户原文"口径不变
- 过期采用惰性判定：读取时校验 `created_at + pending-ttl`（默认 24h，配置），不新增定时任务
- 恢复时工具集按**当前**角色配置重新装配（快照只保消息链，不保工具快照），角色配置变更后恢复自然生效
- 旧版前端收到未知 SSE 事件类型忽略（user-web 事件分发确认向后兼容）

## Current Behavior

（来源：本轮代码核实，src: route-map）

- `CharacterChatService.ask()` Agentic 分支（:399-412）：`isAgentic=false` 时零注册零工作流查询，行为与存量逐字节等价；`buildBuiltinTools`（:632）按可用性组装 `search_knowledge`/`run_workflow`
- `AbstractLLMService.innerStreamingChat`（:319）：同步递归工具循环，活在一次 SSE 请求内，无暂停点；`maxToolIterations=8` 达上限优雅收尾（:321，剥离工具规格追加收尾指令）
- `createToolExecutionMessages`（:788）：模型返回 toolExecutionRequests 后**立即按名执行**，无任何确认门
- `executeToolWithGuardrails`（:830）：timeout（60s）/结果截断（4000 chars）只施加于内置工具；`isMcpTool()` 为 true 的 MCP 工具直调绕过全部防护
- 短期记忆每轮只落用户原文（AbstractLLMService :617-619），工具链 request-scoped 不持久
- `ChatContextResolver.resolve` 只解析 会话/角色/memoryId，无 pending 概念；memoryId 按 conversation 维度
- 短期记忆每轮记账：请求期落用户原文（persistRawUserMessage，AbstractLLMService :617-619），完成后在 `saveAfterAiResponse` 追加 AiMessage（CharacterChatService :769-774，受 `understandContextEnable` 门控）——挂起-恢复必须与该口径闭合
- 工具装配合并：内置工具重名时**天然优先于 MCP**（AbstractLLMService :735-737，"Builtin tool overrides MCP tool"），`ask_user` 等保留名无需额外防抢占
- 模型"问用户"只能靠正文自然语言；`run_workflow` 门卫前置（缺必填输入）返回指引文本让模型正文转述，答案流失在下一轮闲聊上下文里
- 配置骨架：`ZhiMeshProperties.Agent`（:448）现有 maxToolIterations / toolTimeoutMs / toolResultMaxChars 三项
- 迁移编号最新 043（领域预设角色种子）；工具轨迹表 `adi_character_message_tool_call`（041）

## Proposed Behavior

1. **工具注册策略化**：注册集合 = `默认集 ∩ tool_policy ∩ 可用性`；isAgentic 生效 = `default-agentic-enabled 配置 && is_agentic 列值`（迁移后全 true，兜底开关关 = 全体回到现状）
2. **ask_user 挂起**：模型发起 `ask_user` 调用 → 循环识别该工具名 → 不执行、不继续调模型，而是：消息链（含本轮 AiMessage+toolRequests）快照 + 已耗迭代数 + pending 请求 + options 落 checkpoint → SSE 发 `agent_question` 事件 → 合成收尾（见下）落库（消息带 pending 标记，前端刷新可重放卡片）
   - **同轮并行工具调用规则**：模型同轮发多个 toolExecutionRequests 时，非协作类同伴**先照常执行**（结果进快照）；协作类工具挂起。同轮出现多个协作请求时第一个挂起、其余在恢复轮注入「已被忽略，请单独重新发起」结果（langchain4j 要求 AiMessage 的每个 toolExecutionRequest 都有配对结果消息，快照与恢复必须满足该完整性）；工具 description 同时引导"需要用户输入时单独调用，不要与其他工具同轮"
   - **合成收尾**：挂起轮没有最终 ChatResponse——合成一个（text=问题文本、tokenUsage=当轮真实用量）走现有 else 分支收尾（calculateToken/consumer/落库全复用），消息内容即问题文本，AnswerMeta 携带 suspension 载荷；短期记忆按现状口径追加一条 AiMessage（文本=问题），保证后续轮次历史可读
3. **恢复**：下一轮用户消息进来，ask() 前置检查发现活跃 checkpoint → 惰性校验未过期未消费 → ①**先把用户答复原文 append 进短期记忆**（UserMessage，与 persistRawUserMessage 同语义——否则 resume 完成后的后续轮次丢失用户答了什么，这是跨挂起记忆记账的关键一步；understandContextEnable=false 角色不记，与现状一致）→ ②构造 `ToolExecutionResultMessage`（用户输入原文给协作请求，被忽略的同伴请求给忽略文案）→ ③以快照重建 chatRequest、按当前配置重装配工具集 → 恢复 `innerStreamingChat`（继承已耗迭代数与挂起计数）→ 走完正常落库，checkpoint 置 consumed
4. **request_human_approval**：同一挂起机制，事件 `approval_request`，卡片含 action/summary/risk_level；同意/拒绝按钮提交结构化文本恢复循环；tool_policy 标记"需审批"的 MCP 工具由装饰器包装——模型调用时先挂起转审批，批准后才真正执行 MCP 调用
5. **MCP 防护统一**：`mcp-guardrails-enabled=true` 时 MCP 工具同样走 timeout/截断/trace
6. **过期与覆盖**：pending 超 TTL 惰性置 expired，前端卡片置灰，新消息走正常流程；新挂起前旧 pending 置 superseded；**regenerate 对挂起消息视为重问**——ACTIVE checkpoint 置 superseded、走全新一轮（不进恢复）；**会话删除/清空级联**置 deleted（挂 ConversationService 删除路径，防孤儿 ACTIVE）
7. **trace**：ask_user/approval 的挂起、用户应答、审批结论全部记 ToolCallTrace，与现有步骤条/历史回放共用渲染

## Implementation Direction

按里程碑顺序（每步独立可验收、可上线）：

- **M1 工具策略与存量全开**：`tool_policy` 列 + 迁移 044（UPDATE is_agentic 全 true + 种子补 tool_policy）+ `buildBuiltinTools` 策略化 + 兜底开关 + 单测。验收：存量角色默认带 search_knowledge/run_workflow/ask_user 注册（ask_user 此时仅注册，M2 才有挂起行为——**M1 先不注册 ask_user，M2 一起上**，避免半成品工具）
- **M2 挂起-恢复内核**：checkpoint 表 + `AskUserTool` + 循环挂起分支 + 恢复注入 + 惰性过期 + `agent_question` SSE 事件 + trace。验收：任意角色可提问-挂起-应答-续跑全链路，历史回放完整
- **M3 审批**：`RequestHumanApprovalTool` + MCP 需审批装饰器 + `approval_request` 事件。验收：财务助手报销单走审批卡
- **M4 前端**：问题/审批卡片组件、角色表单 tool_policy 编辑器（2026-09-23 产品决策：用户端编辑器移除，tool_policy 仅预设实例化/管理端可写）、步骤条挂起/恢复/审批节点、历史消息卡片重放
- **M5（独立小步）**：MCP guardrails 开关，默认 false 零回归

关键设计决策：

- checkpoint 快照序列化需覆盖 SystemMessage/UserMessage/AiMessage（含 toolExecutionRequests）/ToolExecutionResultMessage 四类 ChatMessage 多态，单测锁死
- 恢复入口复用聊天端点而非新端点：按钮 = 文本提交，模型在 pending 上下文里理解语义，前后端改动最小
- 预算双上限：总迭代数（跨挂起累计，沿用 maxToolIterations）+ 同链挂起次数（新配置 `max-suspensions`，默认 3）
- 装饰器包装而非改 McpToolExecutor 本体：审批标记来自 tool_policy，包装在装配期完成，McpToolExecutor 零改动

## Affected Files

- `zhimesh-common/.../service/CharacterChatService.java` (route-map)：ask() 工具注册策略化、pending 前置检查与恢复装配、saveAfterAiResponse 挂起载荷落 AnswerMeta.suspension（实现口径：初稿的「消息 pending 标记」列未按字面落地，历史重放由挂起载荷 + 轨迹行共同承担）
- `zhimesh-common/.../languagemodel/AbstractLLMService.java` (route-map)：循环挂起分支（识别协作类工具不执行不递归）、恢复入口参数（预算继承）、MCP 防护开关
- `zhimesh-common/.../languagemodel/tool/` (route-map)：新建 `AskUserTool`、`RequestHumanApprovalTool`、`ApprovalRequiredDecorator`；`ToolContext` 扩展挂起状态字段
- `zhimesh-common/.../service/ChatContextResolver.java` (route-map)：resolve 或 ask() 内查询活跃 checkpoint（hook 点）
- `zhimesh-common/.../entity/Character.java` + CharacterAddReq|EditReq|Dto (scan)：toolPolicy 字段
- `zhimesh-common/.../config/ZhiMeshProperties.java` (route-map)：Agent 组扩 `defaultAgenticEnabled`、`mcpGuardrailsEnabled`、`pendingTtl`、`maxSuspensions`
- `server/db_migration/044_agent_collab_tools.sql`（新建）：`tool_policy` 列、UPDATE `is_agentic`、`adi_agent_pending_checkpoint` 表（含 assistant 每实体一个的测试配套）
- `server/db_migration/043_seed_domain_agent_presets.sql` (scan)：幂等追加 tool_policy（或并入 044）
- `zhimesh-common/.../helper/SseManager.java` (route-map)：`agent_question`/`approval_request` 事件方法
- `user-web/src/views/chat/`（Message/index.vue 卡片与步骤条、角色编辑表单策略编辑器、chat.d.ts 类型）(scan)
- 短期记忆链路不动（只存用户原文口径保持）

## Risks

- **消息链快照一致性**：langchain4j ChatMessage 多态序列化/反序列化是最大技术点，四类消息 + toolRequests 结构单测锁死；快照损坏时 fail-safe 走"当普通新消息处理 + checkpoint 作废 + 提示用户"，不 500
- **SSE 中途断连**：挂起事件已发但 complete 未达 → 前端刷新拉历史，消息 pending 标记可重放卡片；checkpoint 以落库为准，不依赖前端状态
- **无限追问**：挂起次数上限 + 总迭代预算跨挂起累计；达限后模型收到"用户未有效回应"收尾指令优雅作答
- **恢复时配置漂移**（挂起期间解绑 KB/改策略/删工作流）：按当前配置重装配，被删工具的 toolRequests 若无法满足 → 返回"该工具已不可用"结果消息让模型自愈
- **MCP 防护打开误伤慢工具**：默认关 + 灰度，打开后超时时间可单独调
- **存量全开的成本回归**：兜底开关一键回退 + 上线后观察 token 日落账；另注意全量 agentic 后每条消息多一次 `listRunnableWorkflowOptions` 可见性查询（单条 SQL，量级可接受，暂不做缓存）
- **旧前端兼容**：未知 SSE 事件忽略策略需在 user-web 事件分发处确认

## Test Strategy

- 单测（沿用 MP 测试模式：ReflectionTestUtils entityClass 预置 / MessageSourceStub / assistant 每实体一个）：
  - 工具策略矩阵：默认集 ∩ denylist、无 KB 不注册 search_knowledge、MCP 审批标记装配、兜底开关关闭时零注册
  - 挂起分支：ask_user 调用 → 不再调模型、checkpoint 字段完整、SSE 事件载荷、AnswerMeta.suspension 挂起载荷（注：SSE 载荷键为 `kind`、AnswerMeta.suspension 字段名为 `type`，前端各按其名取值——实现已知不对称）
  - 恢复注入：pending 存在时构造 ToolExecutionResultMessage、消息链重建（四类消息序列化回环）、预算与挂起计数继承
  - 过期/覆盖/消费幂等：惰性 TTL、superseded、consumed 后第二条消息不二次恢复
  - 审批装饰器：未批不执行 MCP、approve 后执行、reject 结果消息内容
  - MCP guardrails 开关两态
  - 快照损坏 fail-safe
- 集成验收（dev 库 + 财务助手剧本）：查预算（自动执行）→ 报销（转审批卡）→ 同意 → 提交成功可在 mock 里查到；ask_user 澄清部门 → 应答 → 续跑出对比答案；历史会话重开卡片与步骤条完整
- 回归：`mvn -B test` 全绿；user-web `pnpm build`

## WorkerHelper Impact

- Need worker-sync: yes（chat 主链路新增挂起-恢复入口、新表、新工具组、Agent 配置组扩展）
- Affected routes: 会话与消息（SSE 聊天）、角色系统、MCP、配置骨架（zhimesh.agent.*）

## Acceptance Criteria（2026-09-23 T8 验收勾账）

1. ✅ `default-agentic-enabled=false` 时：全体角色行为与现状逐字节一致（含 quota、引用标记、SSE 事件序列），存量回归零——单测覆盖 + T8 剧本 f 实测：无 [AGENT_QUESTION]/[APPROVAL_REQUEST]、日志零条 agentic、报销直通无审批门、0 新增 checkpoint；语义澄清：MCP 工具不受该开关门控（改造前即 enableMcp(true) 硬编码，开关语义=回到现状）
2. ✅ 任意未手动配置的存量角色：模型可发起 ask_user，前端出问题卡片；用户回复（文本或选项按钮）后工具链续跑，步骤条含挂起-应答节点且历史回放完整——后端链路 T8 剧本 d 双形态（无 options/带 options）实测续跑；前端卡片/步骤条/历史回放由 T6 落地（历史回放自轨迹行派生，MCP_APPROVAL 形态不可派生为已知限制）；纯 UI 视觉路径待日常使用确认
3. ✅ 需审批 MCP 工具：未批准前不产生任何真实调用；拒绝后模型收到拒绝原因并转述；审批记录进 trace——T8 剧本 b/c 实测（装饰器硬门拦截→MCP_APPROVAL→同意放行真实提交单号 EX-20260914-001；拒绝前缀→0 trace 转述拒绝）；trace 落库 SELECT 实证
4. ✅ pending 超 TTL 惰性过期：卡片置灰、新消息走正常流程、不二次消费 consumed checkpoint——单测覆盖（TTL 需等待未做活体，机制同 3/5 的状态机已实证）
5. ✅ 同一工具链挂起 ≤ max-suspensions（默认 3）、总迭代 ≤ maxToolIterations（跨挂起累计），达限优雅收尾不 500——单测覆盖；T8 观察 suspension_count 跨链继承累计（1→2）与 checkpoint 状态流转（8 行全 CONSUMED、0 ACTIVE 残留）
6. ✅ 043 财务助手升级后演示剧本端到端跑通：查询自动、报销走审批、澄清走 ask_user——T8 剧本 a-f API 层全过（含双审批形态链 APPROVAL→MCP_APPROVAL）；发现的批准恢复鲁棒性问题（裸「同意」被当工具结果虚构成功）当日修复：审批类恢复答复包机器可读引导（resumeAnswerToolResult），单测回归锁
7. ✅ `mcp-guardrails-enabled` 默认 false 零回归；true 时 MCP 调用受超时与截断——T7 单测三态覆盖（默认直调/超时/截断）
8. ✅ 全部单测绿 + 两端 build 绿——`mvn -B test` 804 tests 0 失败 BUILD SUCCESS；user-web `pnpm build`（含 type-check）绿
