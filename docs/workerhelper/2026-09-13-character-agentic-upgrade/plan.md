# Execution Plan: 角色对话 Agentic 改造（character-agentic-upgrade）

依据：同目录 `design.md`（已确认）。代码事实来源：本轮 scan + 会话内已验证（`WorkflowStarter.blocking` 已存在；`LLMTokenUtil.cacheTokenUsage` 为 Redis List 累加但仅在最终轮调用——中间工具轮漏记，T5 为真实修复项）。

## Summary

M1 后端工具运行时（ToolExecutor 统一 + search_knowledge + isAgentic + quota 修复 + 轨迹落库）→ M2 前端可视化（步骤条 + 开关 + 历史回放）→ M3 workflow-as-tool（复用已有 `WorkflowStarter.blocking`）。M4 不在本计划。

## Tasks

### M1 后端工具运行时

- [x] T1: 迁移 041 — is_agentic 列 + 工具轨迹表
  - Files: `server/db_migration/041_character_agentic.sql`（新建）、`all_ddl.sql`、`verify_schema.sql`、`README.md`、`README.zh-CN.md`
  - Change: `adi_character` 加 `is_agentic boolean default false not null`；新建 `adi_character_message_tool_call`（id、message_id FK、tool_name、args text、result_summary text、duration_ms、success、seq、create_time）；verify_schema 补 required_columns；README 按 040 模式补 041 行
  - Verify: dev 库 SqlRunner 执行 + MCP 查询确认列/表/约束；幂等重跑一次
  - Depends on: none

- [x] T2: ToolExecutor 统一抽象 + 配置化
  - Files: `zhimesh-common/.../languagemodel/tool/`（新建包：`ToolExecutor.java`、`ToolContext.java`、`McpToolExecutor.java`）、`AbstractLLMService.java`、`McpToolRegistry.java`、`dto/ChatModelRequest.java`、`zhimesh-bootstrap/.../application.yml`
  - Change: `ToolExecutor { ToolSpecification spec(); ToolExecutionResult execute(ToolExecutionRequest req, ToolContext ctx); }`；`McpToolExecutor` 包装现有 `McpClient`；`discoverRequestTools`/`createToolExecutionMessages`/`innerStreamingChat` 全部改按 `Map<String, ToolExecutor>`（按工具名索引）工作，SSE `sendToolCall`/错误处理行为保持；`MAX_TOOL_CALL_DEPTH` 硬编码 5 → `zhimesh.agent.max-tool-iterations`（默认 8）；加 `tool-timeout-ms`（默认 60000）、`tool-result-max-chars`（默认 4000）两个配置；`ChatModelRequest` 增加 `builtinTools`（`List<ToolExecutor>`）字段随请求传递
  - Verify: 新单测 `ToolExecutorResolutionTest`（内置+MCP 混合按名匹配、未知工具返回错误结果消息不抛异常）；既有测试全绿（MCP 行为等价）
  - Depends on: none（与 T1 并行）

- [x] T3: SearchKnowledgeTool 内置工具
  - Files: `zhimesh-common/.../languagemodel/tool/SearchKnowledgeTool.java`（新建）、`util/CharacterChatHelper.java`
  - Change: spec = `search_knowledge(query: string, kbHint?: string)`；execute 以 `ToolContext`（携带 user、characterId、filteredKb、llmService、embeddingModel、memoryId）调用 `CharacterChatHelper.retrieve`，结果按 `tool-result-max-chars` 截断、按相关度排序、带来源标题前缀；检索到的片段写入 `ToolContext.refCollector`（新增），供落库链路合并进 `adi_character_message_ref_*`（引用弹窗语义保持）；kbHint 仅作标题模糊过滤，鉴权仍以 filteredKb 为准（无提权）
  - Verify: 单测 `SearchKnowledgeToolTest`：resolver 矩阵（EXECUTIVE/非成员团队/他人个人库 → 空结果）、kbHint 过滤、截断、refCollector 填充
  - Depends on: T2

- [x] T4: CharacterChatService agentic 分支 + 轨迹落库 + AnswerMeta 扩展
  - Files: `service/CharacterChatService.java`、`entity/Character.java`、`dto/CharacterAddReq/CharacterEditReq/CharacterDto/AnswerMeta.java`（加 `List<ToolCallTrace> toolCalls`）、`entity/CharacterMessageToolCall.java`+mapper（新建）、`helper/SseManager.java`
  - Change: Character 加 `is_agentic` 字段链路（entity/DTO 三件套）；ask() 中 `isAgentic=true && 工具可用` 时把内置工具塞进 `ChatModelRequest.builtinTools`（混合检索：现有预检索不动）；`ToolContext` 由 ask() 构造并携带 `refCollector`；`saveAfterAiResponse` 合并 refCollector 证据 + 批量落 `adi_character_message_tool_call`；`sendToolCall` 载荷扩展 `{toolName, args, durationMs, success, resultSummary}`（向后兼容：新字段可空）；meta SSE 事件带 toolCalls
  - Verify: 单测：isAgentic=false 路径行为等价（回归锁）、isAgentic=true 时 builtinTools 非空、轨迹落库参数正确；`mvn -B test` 全绿
  - Depends on: T1、T2、T3

- [x] T5: quota 修复 — 中间工具轮 token 累加
  - Files: `AbstractLLMService.java`、`util/LLMTokenUtil.java`
  - Change: `innerStreamingChat.onCompleteResponse` 的**每一轮**（含工具中间轮）都调用 `LLMTokenUtil.cacheTokenUsage`，不只最终轮；最终 `calculateToken` 汇总逻辑不变（List 已是累加语义）
  - Verify: 单测：fake 两轮响应（第一轮带 toolExecutionRequests）断言 Redis List 有 4 个元素（2 轮×in/out）
  - Depends on: none（与 T2 同文件 → 必须串行：T2 先）
  - **冲突点：与 T2 同改 `AbstractLLMService`，建议同一执行批次连续完成**

- [x] T6: M1 集成验收（dev 环境）
  - Files: 无新改（验证性任务）
  - Change: 临时角色 fixture（复用 P4 验收 SqlRunner 模式）：isAgentic=true 绑多库，SSE 提问触发 ≥2 次 search_knowledge；核对 tool_call 表落库、引用弹窗数据、quota 日落账含多轮 token；isAgentic=false 角色行为对照；结束后清理 fixture
  - Verify: 上述断言全过 + `cd server && mvn -B test` 全绿
  - Depends on: T4、T5

### M2 前端可视化

- [x] T7: 步骤条组件 + SSE/历史双通道
  - Files: `user-web/src/views/chat/components/Message/ToolSteps.vue`（新建）、`Message/index.vue`、`api/index.ts`（toolCallReceived 载荷）、`typings/chat.d.ts`
  - Change: 🔧 内联角标升级为 `ToolSteps` 折叠条（默认收起一行摘要"🔧 3 次工具调用"，展开显示每步：名称/参数摘要/耗时/成败/结果摘要）；实时流经 `toolCallReceived` 追加、历史经消息 DTO `toolCalls` 回放（CharacterMsgDto 加 toolCalls 由 T4 的 meta 链路带出）
  - Verify: `pnpm build` 绿 + dev 手工核对实时/回放两通道
  - Depends on: T4（SSE/meta 契约冻结后可并行开发）

- [x] T8: 角色编辑表单 Agentic 开关
  - Files: `user-web/src/views/chat/Header/EditConvDetail.vue`、`locales/zh-CN.ts`、`en-US.ts`
  - Change: 设置区加"Agentic 模式"开关（默认关）+ 说明文案（开启后可自主调用知识库检索与工作流，token 消耗更高）；提交链路带 isAgentic
  - Verify: `pnpm build` 绿 + 新建/编辑角色开关状态持久化正确
  - Depends on: T4（DTO 字段）

### M3 workflow-as-tool

- [x] T9: RunWorkflowTool
  - Files: `zhimesh-common/.../languagemodel/tool/RunWorkflowTool.java`（新建）、`CharacterChatService.java`（注册）
  - Change: spec = `run_workflow(workflowTitle: string, input: string)`，描述中注入当前用户可见工作流标题清单（mine+public，与 user-web 选择器同口径）；execute 调 `WorkflowStarter.blocking(user, uuid, inputs)`（已存在，勿改签名），外层 `tool-timeout-ms` 超时包装（超时返回已运行状态摘要）；ThreadLocal 递归标记——工作流内 AgentNode 再触发 run_workflow 直接返回拒绝文案；输出按 result-max-chars 截断
  - Verify: 单测：标题→uuid 解析（可见性过滤）、递归防护、超时路径；集成：对话"帮我跑 XX 工作流"端到端 + 工作流内 LLM 消耗计入发起用户
  - Depends on: T2、T3（复用 ToolContext/Executor）；与 M2 无依赖
  - **注意：AgentNode→LocalAgentService→AbstractLLMService 链路自动获得工具能力，递归防护必须在 T9 落地前想清楚测试**

- [x] T10: 全量回归 + 文档
  - Files: 无/README
  - Change: `mvn -B test` + `pnpm build` + admin-web build（确认未受影响）；dev 环境跑 design.md 验收标准 1-7 逐条过；清理全部 fixture
  - Verify: 验收标准 1-7 全过
  - Depends on: T6、T7、T8、T9

## Verification

- Commands: `cd server && mvn -B test`；`cd user-web && pnpm build`；`cd admin-web && pnpm build`；dev 库 SqlRunner（JDBC 单引号 classpath 模式）
- Manual checks: 步骤条实时/回放、开关开合对比、run_workflow 端到端、quota 后台数字

## Task Relationships

- Strongly related: T2+T5（同改 AbstractLLMService，必须连续执行）；T3+T4（ToolContext 契约贯通）；T4→T7/T8（DTO/SSE 契约上游）
- Weakly related: T1∥T2（无共享文件，T4 汇合）；T9 依赖 T2/T3 但与 M2 完全并行
- Independent: T6、T10 为验证汇聚点
- Conflict risks: T2/T5 同文件；T4 与 T9 都动 CharacterChatService（T9 晚于 T4 执行即可）

## WorkerSync

- Need worker-sync: uncertain（docs/workerhelper 路由图尚不存在；本次改造落地后建议先 worker-init 初始化，再补录 chat-agent 主链路条目）

## Risks

- refCollector 与现有 saveAfterAiResponse 的证据合并次序（去重键 embeddingId）需在 T4 单测锁定
- sendToolCall 载荷扩展必须可空字段向后兼容，旧前端不炸
- `blocking()` 无内建超时，T9 的外层包装用独立线程 + 有界等待，不改动 WorkflowStarter 本身
