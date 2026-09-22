# Design: 角色对话 Agentic 改造（character-agentic-upgrade）

## Goal

把角色对话从"预检索 + 单次 LLM"的管道升级为真正的 Agent 运行时：模型通过工具循环**自主决定**何时查知识库、查什么、是否调用工作流，并在前端可视化整个执行过程。生产可用（鉴权、quota、上下文预算、循环上限、历史回放全部覆盖），不是 demo。

## Scope

- In:
  - 统一工具执行抽象 `ToolExecutor`（现有 MCP 工具收编 + 内置工具接入同一循环）
  - 内置工具 `search_knowledge(query, kbHint?)`：复用现有检索链路（resolver 鉴权 + scope-gate）
  - 内置工具 `run_workflow(workflowTitle, input)`：角色可调用用户可见的工作流
  - 混合检索模式：保留现有自动首检索（scope-gate 判定相关时），模型可追加调用工具迭代检索
  - 角色 `isAgentic` 开关（默认关）+ 系统级配置项（循环上限、工具超时、结果长度上限）
  - 工具调用过程落库 + SSE 事件 + 前端步骤条（扩展现有 🔧 角标为可展开步骤列表，含结果摘要）
  - quota 聚合：一次回答跨多次循环迭代的 token 全部计入该消息成本
- Out（明确不做，留作后续演进）:
  - 多 Agent 编排 / 子代理
  - Deep Research 计划模式（todo 列表式规划 UI）
  - A2A 远程 Agent 协议
  - 知识库问答页、工作流编辑器的行为变更（只作为工具提供方，不改自身交互）

## Assumptions

- 检索模式采用**混合**而非纯 tool 模式：保证简单 KB 问题的首字延迟与质量不回退；纯 tool 模式留作 `isAgentic` 下的可选档位（实现为配置，不在本期 UI 暴露）
- `isAgentic` 默认关闭，存量角色行为零变化
- 内置工具以**当前用户身份**执行（走既有 resolver / 可见性规则），无任何提权
- `run_workflow` 仅可调用"当前用户可见的工作流"（mine + public，与 user-web 选择器同口径）
- 工具结果注入上下文有硬上限（字符数），防止撑爆上下文窗口

## Current Behavior

（来源：本轮代码扫描，src: scan）

- `CharacterChatService.ask()`：短记忆 turn 锁 → `CharacterChatHelper.retrieve()` **无条件预检索** → `PromptUtil.createPrompt` 把记忆+知识塞进 prompt → 一次流式 LLM 调用 → 落库 is_ref_* 标记
- `AbstractLLMService`：已有递归工具循环（`innerStreamingChat`，`MAX_TOOL_CALL_DEPTH=5` 硬编码），但工具执行 `createToolExecutionMessages` **只认 `McpClient`**（`Map<ToolSpecification, McpClient>` 按 name 匹配）
- `SseManager.sendToolCall(uuid, toolName, durationMs, success)` 已存在且前端已消费（`api/index.ts` `toolCallReceived` → `Message/index.vue` 🔧 计数角标）
- `LocalAgentService`（工作流 AgentNode 用）与聊天共用 `AbstractLLMService`，工具循环改造自动受益
- `WorkflowStarter.streaming(user, wfUuid, userInputs)` 是 SSE 出口，无同步/编程式运行路径
- quota 在 `CharacterChatService` ~L656 `userDayCostService.appendCostToUser` 落账，token 来自 `SseManager.calculateToken`

## Proposed Behavior

1. **统一工具层**：新接口 `ToolExecutor { ToolSpecification spec(); String execute(ToolExecutionRequest req, ToolContext ctx); }`。`McpToolExecutor` 包装现有 `McpClient`；内置工具实现同一接口。`discoverRequestTools` 返回 `Map<String, ToolExecutor>`（按工具名索引），循环/执行/错误处理/`sendToolCall` 事件逻辑保持不变
2. **search_knowledge 工具**：入参 `{query: string, kbHint?: string}`；执行体调用 `CharacterChatHelper.retrieve`（同一角色 KB 集合、同一 resolver 鉴权、同一 scope-gate），返回按相关度截断的片段文本（含来源标题）；命中写 `is_ref_*` 语义保持——工具检索的证据同样进引用弹窗
3. **run_workflow 工具**：入参 `{workflowTitle: string, input: string}`；执行体走 `WorkflowStarter` 抽出的同步核心（新增 `runToCompletion(user, wfUuid, inputs, timeout)`），返回最终输出摘要；循环深度经 ThreadLocal 传递，**工作流内 AgentNode 再触发 run_workflow 时直接拒绝**（防递归）
4. **混合检索**：`isAgentic=true` 时保留 scope-gate 相关的自动首检索，同时注册内置工具；简单问题不调工具零额外开销，复杂问题模型可多轮检索
5. **过程可视化**：工具事件扩展为 `{toolName, args?, durationMs, success, resultSummary?}`；`AnswerMeta` 增加 `toolCalls` 数组随 meta SSE 事件下发并落库（新表 `adi_character_message_tool_call`），历史消息同样回放步骤条
6. **quota**：`innerStreamingChat` 每轮迭代的 tokenUsage 累加进同一 sseUuid 的统计（`SseManager.calculateToken` 已按 uuid 聚合则确认即可），一次回答 N 轮调用 = 一条消息的总成本
7. **配置**：`zhimesh.agent.max-tool-iterations`（默认 8，替换硬编码 5）、`tool-timeout-ms`（默认 60s，run_workflow 用）、`tool-result-max-chars`（默认 4000）

## Implementation Direction

按里程碑顺序（每步独立可验收、可上线）：

- **M1 后端工具运行时**：`ToolExecutor` 抽象 + MCP 收编 + `search_knowledge` + `isAgentic` 字段（迁移 041）+ 循环上限/超时/截断配置化 + quota 聚合校验 + 工具轨迹落库。验收：开关打开的角色能多轮检索，工具轨迹入库
- **M2 前端可视化**：步骤条组件（名称/参数摘要/耗时/成败/结果摘要，可展开）+ 角色编辑表单 Agentic 开关 + 历史回放
- **M3 workflow-as-tool**：`WorkflowStarter` 抽同步核心 + `run_workflow` 工具 + 递归防护 + 超时。验收：对话中"帮我跑一下周报工作流"端到端出结果
- **M4（后续）**：deep research 计划模式、纯 tool 检索档位

## Affected Files

- `zhimesh-common/.../languagemodel/AbstractLLMService.java` (scan)：ToolExecutor 抽象替换 McpClient 映射；深度上限配置化；迭代 token 聚合
- `zhimesh-common/.../languagemodel/McpToolRegistry.java` (scan)：产出 `McpToolExecutor` 列表
- `zhimesh-common/.../agent/tool/`（新建）：`ToolExecutor`、`ToolContext`、`SearchKnowledgeTool`、`RunWorkflowTool`、`BuiltInToolRegistry`
- `zhimesh-common/.../service/CharacterChatService.java` (scan)：agentic 分支（工具注册、混合检索开关）、工具轨迹落库、AnswerMeta.toolCalls
- `zhimesh-common/.../util/CharacterChatHelper.java` (scan)：buildChatRequestParams 携带内置工具
- `zhimesh-common/.../workflow/WorkflowStarter.java` (scan)：抽 `runToCompletion` 同步核心
- `zhimesh-common/.../entity/Character.java` + `dto/CharacterAddReq|EditReq|Dto` (scan)：isAgentic 字段
- `server/db_migration/041_character_agentic.sql`（新建）：is_agentic 列 + adi_character_message_tool_call 表
- `zhimesh-common/.../helper/SseManager.java` (scan)：sendToolCall 载荷扩展
- `server/zhimesh-bootstrap/.../application.yml` (scan)：zhimesh.agent.* 配置组
- `user-web/src/views/chat/components/Message/index.vue` (scan)：🔧 角标 → 步骤条
- `user-web/src/views/chat/`（角色编辑表单，位置待 plan 定位）：Agentic 开关
- `user-web/src/typings/chat.d.ts` (scan)：toolCalls 载荷扩展

## Risks

- **上下文膨胀**：多轮工具结果累积 → 硬截断（tool-result-max-chars）+ 仅保留最近 K 轮完整结果，更早轮次降级为摘要
- **token 计量**：递归每轮独立 ChatResponse，需确认 calculateToken 按 sseUuid 累加；若只记最后一轮则 quota 少扣 → M1 中写单测锁死
- **长工作流阻塞对话**：run_workflow 同步执行有超时上限，超时返回状态摘要而非失败
- **递归调用**（角色→工作流→AgentNode→同角色）：ThreadLocal 深度标记直接拒绝第二次 run_workflow
- **流式体验**：工具轮次间无正文输出，前端步骤条需展示"执行中"状态防呆
- 存量行为回归：`isAgentic=false` 路径代码与现状等价（用现有测试基线 533+ 保证）

## Test Strategy

- 单测（沿用 MP 测试模式：ReflectionTestUtils entityClass / MessageSourceStub）：
  - `SearchKnowledgeToolTest`：resolver 矩阵（个人/团队/企业 STAFF/EXECUTIVE 不可见库 → 工具内检索为空）、scope-gate 过滤、结果截断
  - `ToolExecutor` 解析：内置+MCP 混合注册按名匹配、未知工具错误消息
  - 循环上限触发 → 优雅终止（复用现有 errorAndShutdown 行为）
  - token 聚合：fake StreamingChatModel 多轮 usage 累加断言
  - run_workflow 递归防护（ThreadLocal 置位后拒绝）
- 集成验收：dev 库 + 临时角色（复用 P4 验收的 fixture 模式），开关开/关对比、历史回放、quota 日落账核对
- 回归：`mvn -B test` 全绿；user-web `pnpm build`

## WorkerHelper Impact

- Need worker-sync: uncertain（docs/workerhelper 路由图尚不存在；待 worker-init 初始化后再补录 chat/agent 路由条目）
- Affected routes: chat 主链路、workflow starter、character 编辑

## Acceptance Criteria

1. `isAgentic=false` 的角色：行为与现在逐字节一致（含 quota、引用标记、SSE 事件序列）
2. `isAgentic=true` 的角色问"对比 A 库和 B 库里 X 的差异"：模型至少 2 次 `search_knowledge` 调用，步骤条完整展示每次调用（名称/耗时/结果摘要），引用弹窗含两库证据
3. 工具循环达上限时优雅收尾：给出基于已有信息的回答 + 步骤条标注截断，不 500
4. "帮我跑周报工作流"：run_workflow 返回工作流输出，工作流内 LLM 消耗计入发起用户 quota
5. 历史会话重新打开：工具步骤条完整回放
6. 工具内检索始终以当前用户身份鉴权：不可见库即使被模型点名也不返回内容（返回空结果 + 提示）
7. 全部单测绿 + 两端 build 绿
