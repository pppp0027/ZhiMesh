# Execution Plan: agent-chat-ux（Agent 对话体验改造）

> 输入：同目录 `design.md`（已确认）。java 路径相对 `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/`，前端相对 `user-web/src/`。

## Summary

按设计落地三组改动：① ask_user 提问策略（工具描述 + 023 种子/存量修正）；② TOOL_STARTED 实时反馈链路 + 动态状态条 + 流式批量 flush；③ 移除「深度思考」「连续对话」开关（思考按模型能力自动判定、上下文恒启用）。后端 5 个任务、SQL 1 个、前端 4 个、集成验证 1 个。

## Tasks

- [x] T1: 重写 `AskUserTool` 工具描述
  - Files: `languagemodel/tool/AskUserTool.java`（仅 SPEC.description 与类注释相应句子）
  - Change: description 改为——仅在缺少**会改变任务方向、无法合理默认**的关键信息时调用；每次只问当前最关键的一个问题（确需确认至多两个）；其余维度自行采用合理默认假设，并在最终回答开头简要列出所做假设；提问时附带建议默认值供用户确认。保留"单独调用，不与其他工具同轮"约束。`question` 参数描述同步（"一句话一个关键缺口"）。**不改** execute/schema/挂起逻辑。
  - Verify: `cd server && mvn -pl zhimesh-common -am test -Dtest='ToolExecutorResolutionTest,ToolLoopSuspensionTest,ToolLoopApprovalTest'`；既有断言不涉 description，应全绿
  - Depends on: none

- [x] T2: 023 种子文案修正 + 存量角色幂等 UPDATE
  - Files: `server/db_migration/023_seed_useful_mcp_presets.sql`
  - Change: 沿用文件头部既有 amendment 模式追加：
    1. `UPDATE adi_character_preset SET ai_system_message='<新文案>' WHERE title='产品与市场分析师' AND ai_system_message='<旧文案>'`（新文案去掉"先明确…时间窗口"式命令，改为"基于合理默认的范围与时间窗口直接开展分析，仅在缺少影响结论方向的关键信息时询问一次"，其余句子保留）
    2. `UPDATE adi_character SET ai_system_message='<同一新文案>' WHERE ai_system_message='<同一旧文案>'`（存量副本，逐字匹配、天然幂等，不限定 title）
    3. 「网页抓取」MCP（adi_mcp 与未来 NOT EXISTS 分支两处）remark 追加："不要抓取搜索引擎结果页（会被 robots.txt 拒绝），直接抓取目标网站。"
  - Verify: 本地库先 `SELECT count(*) FROM adi_character WHERE ai_system_message LIKE '%先明确分析目标%'`（预期≥3）→ 应用 SQL → 复查归零、新文案就位；**连跑两遍验证幂等**；注意 dev 与生产共库，执行前把命中行 id 列表贴给用户过目
  - Depends on: none

- [x] T3: 后端 TOOL_STARTED SSE 事件
  - Files: `cosntant/ZhiMeshConstant.java`、`helper/SseManager.java`、`languagemodel/AbstractLLMService.java`
  - Change:
    1. `SSEEventName` 新增 `TOOL_STARTED`（命名风格对齐现有常量，前端事件名 `[TOOL_STARTED]`）
    2. `SseManager.sendToolStarted(String uuid, String toolName, String args)`：null/未注册 uuid 短路口径与 `sendToolCall` 一致；载荷 `{toolName, args?}`（args 截断至 `TOOL_CALL_RESULT_SUMMARY_MAX_CHARS`，复用常量）
    3. `executeToolRound` 普通工具在 `long toolStart = …`（:1130）前发送；`executeCollaborativeRequest` 在 `long toolStart = …`（:1206）前发送（ask_user 等协作工具同样先点亮再挂起）。guardrails 限制 marker（:372）与"无 executor"分支不发 started
  - Verify: `mvn -pl zhimesh-common -am test -Dtest='ToolLoopSuspensionTest,ToolLoopApprovalTest'`；编译通过
  - Depends on: none

- [x] T4: `checkIfReturnThinking` 改为纯模型能力判定
  - Files: `util/CharacterChatHelper.java`
  - Change: 新签名 `checkIfReturnThinking(AiModel aiModel, String platformName, boolean toolsOrWebSearchActive)`：非 reasoner → `null`；`platformName == ZhiMeshConstant.ModelPlatform.DEEPSEEK && toolsOrWebSearchActive` → `false`；其余 reasoner → `true`。调用点（:523 `buildChatRequestParams` 内）：platformName 取 `llmService.getPlatform().getName()`；`toolsOrWebSearchActive = enableMcp || enableWebSearch || agentic 工具集非空`（agentic 判定沿用该方法既有可用信息，保守取"可能注册工具即 true"）。**删除**对 `character.getIsEnableThinking()` 的读取；`is_thinking_closable` 不再参与。`character` 参数从签名移除
  - Verify: 新增/更新单测矩阵（非 reasoner / reasoner / deepseek+工具 / deepseek 无工具 / 非 deepseek reasoner+工具）随 T5 一起跑
  - Depends on: none（与 T5 同文件，须先于 T5 执行）

- [x] T5: 上下文恒启用 + 受影响单测更新
  - Files: `util/CharacterChatHelper.java`（:483）、`service/CharacterChatService.java`（:444、:743、:919、:1293）、`service/LocalAgentService.java`（:99）、测试 `CharacterChatServiceAgenticTest`、`CharacterChatServiceResumeTest`
  - Change: 5 处 `Boolean.TRUE.equals(character.getUnderstandContextEnable())` 条件全部改为恒定启用（直接传 shortTermMemoryId / 无条件 append / 无条件接线记忆），删除对列的读取并更新相应注释；`AgenticTest:254-276` 的"flag 关→记忆为空"负例改写为"恒启用→记忆接线存在"；`ResumeTest:261-264` 的"flag 关→跳过 append"负例同理改写；其余 setUnderstandContextEnable(true) 的用例保留（列仍在，只是不再读）
  - Verify: `mvn -pl zhimesh-common -am test -Dtest='CharacterChatServiceAgenticTest,CharacterChatServiceResumeTest,CharacterChatServicePresetInstantiationTest'` 全绿；T4 的判定矩阵单测一并加入
  - Depends on: T4（同文件 CharacterChatHelper.java，且 :523 调用点相邻）

- [x] T6: 前端 TOOL_STARTED 分发 + 工具步骤实时化
  - Files: `api/index.ts`、`typings/chat.d.ts`、`views/chat/index.vue`、`views/chat/InputEditor.vue`、`views/chat/components/Message/ToolSteps.vue`
  - Change:
    1. `commonSseProcess` 新增 `toolStartedReceived?: (data: { toolName: string; args?: string }) => void` 分支（`[TOOL_STARTED]`，JSON parse 失败告警不抛，对齐 `[TOOL_CALL]` 写法）；`sseProcess` 透传
    2. `Chat.ToolCall` 增加 `running?: boolean`
    3. `index.vue` / `InputEditor.vue` 的 toolStartedReceived：往 `answer.toolCalls` push `{toolName, args, running: true, durationMs: 0, success: true}`；toolCallReceived 回填策略——先找**同名且 running** 的最近一条回填（清 running、填 durationMs/success/resultSummary/seq），找不到则照旧直插（兼容 TOOL_LIMIT_MARKER 与异常序）
    4. `ToolSteps.vue`：`running` 步骤图标用 loading spinner（对齐 `Text.vue` 的 `line-md:loading-twotone-loop`），状态色用 is-wait 同款 accent；摘要行存在 running 步骤时追加"正在执行…"文案（i18n key 见 T7 一并加）
  - Verify: `pnpm type-check`；手动：发起会触发工具的提问，观察步骤先 spinner 后回填（完整验收在 T10）
  - Depends on: T3（事件存在）；与 T8 同文件（index.vue/InputEditor.vue），须协调顺序

- [x] T7: 前端动态状态条 + i18n
  - Files: `views/chat/components/Message/index.vue`、`views/chat/components/Message/Text.vue`、`locales/zh-CN.ts`、`locales/en-US.ts`
  - Change:
    1. `Message/index.vue` 新增可选 prop `state?: Map<string, string>`，`index.vue`/`InputEditor.vue` 渲染处把 `qaMessage.state` 传入
    2. `Text.vue` loading 且无文本时：按 state 优先级显示——有 running 工具步骤（由父层合并判断或传 state `tool_running`）→"正在调用工具"；`knowledge_searching` →"正在检索知识库"；默认 `question_analyzing`→"问题分析中"。收到首 token 后行为不变（切正文渲染）
    3. i18n：`chat.state.knowledge_searching`（zh:"正在检索知识库"/en:"Searching knowledge base"）、`chat.state.tool_running`（zh:"正在调用工具"/en:"Running tool"）、`chat.toolStepRunning`（"正在执行…"/"Running…"）；question_analysing 文案保留
  - Verify: `pnpm type-check`；手动：无 KB 角色看"分析中"、挂 KB 角色看"检索知识库"流转
  - Depends on: none（state 事件后端早已存在）；与 T6/T8 无同文件冲突（Message/ 子组件与 locales 独立），locales 与 T9 有共享须后做或协调

- [x] T8: 流式渲染批量 flush
  - Files: `views/chat/index.vue`、`views/chat/InputEditor.vue`
  - Change: 两处 `messageReceived`/`thinkingDataReceived` 的逐字符 `appendChunk` 循环改为：字符推入局部缓冲数组 + 启动 ~60ms 定时器批量 `appendChunk`（join 后一次追加，thinking/正文两套缓冲）；`doneCallback`/`suspensionReceived`/`errorCallback` 入口处先同步 flush（清定时器）再走原逻辑；`onUnmounted`/请求切换（`chatRequestGeneration` 变更）时清定时器防串话。store `appendChunk` 本身不改
  - Verify: `pnpm type-check`；手动长答案对比：流式无卡顿、结尾字符不丢（完整验收 T10）
  - Depends on: T6（同文件，顺序执行：T6 → T8）

- [x] T9: 移除「深度思考」「连续对话」开关
  - Files: `views/chat/InputToolbar.vue`、`views/chat/components/Header/index.vue`、`views/chat/index.vue`、`api/index.ts`、`locales/zh-CN.ts`、`locales/en-US.ts`、`utils/functions/index.ts`、`views/chat/hooks/useUsingContext.ts`、`store/modules/chat/index.ts`、`store/modules/chat/helper.ts`
  - Change:
    1. InputToolbar：删「深度思考」「连续对话」两个 pill、`toogleThinking`/`toggleUsingContext` 函数、`isDeepSeekThinking` computed 及其两条 watch（DeepSeek 自动删工具/联网的 workaround 整链）、`contextUpdating`/`isReasoner`/`isThinkingClosable` 中仅为其服务的状态（`isReasoner` 若 webSearch pill 判定仍用则保留该用途）；相关模板与样式（`.context-pill`/`.context-status-light` 等）清理
    2. `Header/index.vue`（移动端）：删上下文开关图标与 `usingContext` prop / `toggle-using-context` emit；`index.vue` 移除 `toggleUsingContext` 函数与传参
    3. `api/index.ts`：删 `characterToggleUsingContext`/`characterToggleThinking` 及导出（后端端点保留不动）
    4. i18n：删 `chat.continuousConversation/contextEnabledStatus/contextDisabledStatus/turnOnContext/turnOffContext/deepThinking*/understandContextEnable` 及 `chat.usingContext`——删除前 grep 确认无其他引用（QA 页若复用 `usingContext` 文案则保留该 key）
    5. 遗留清理：删 `hooks/useUsingContext.ts`；store `usingContext` state/setUsingContext 与 `helper.ts:14` 默认值、`emptyCharacter()` 的 `understandContextEnable/isEnableThinking` 字段——typings 保留字段（`CharacterDto` 兼容后端返回），仅前端不再产生交互入口
  - Verify: `pnpm type-check && pnpm build`；grep 全库无 `characterToggleThinking|characterToggleUsingContext|useUsingContext` 残留引用
  - Depends on: T6/T7/T8（共享 index.vue / api/index.ts / locales，最后执行避免冲突）

- [ ] T10: 集成验证
  - Files: 无新改动（只验证）
  - Change: 后端 `cd server && mvn -pl zhimesh-common -am test`（全模块单测）；前端 `cd user-web && pnpm build`；启动本地服务手动验收设计文档 Acceptance Criteria 1-8（重点：重放"小型机器人市场调查"场景看提问收敛；观察状态条流转/工具 spinner/长文流式；PC+移动端按钮移除无布局空洞；推理模型思考流自动出现、DeepSeek+工具时思考关工具可用）
  - Verify: 输出逐条验收结果
  - Depends on: T1-T9 全部完成

## Verification

- Commands:
  - `cd server && mvn -pl zhimesh-common -am test`
  - `cd user-web && pnpm type-check && pnpm build`
  - T2 专用：`psql` 连本地库跑幂等验证（先 SELECT 命中、后复查归零，连跑两遍）
- Manual checks: 见 T10（设计文档 Acceptance Criteria 1-8 逐条）

## Task Relationships

- **Strongly related**: T4 + T5（同文件 `CharacterChatHelper.java`、调用点相邻，须 T4→T5 连续执行）；T6 + T8（同文件 index.vue/InputEditor.vue，T6→T8）；T6 + T7（状态条需要 running 步骤信息联动，接口约定 `ToolCall.running` 由 T6 定、T7 消费）
- **Weakly related**: T1、T2、T3 与其他任务文件边界清晰可独立；T9 依赖前端四任务完成后的最终形态
- **Independent**: T2（纯 SQL）与全部代码任务无文件交集
- **Conflict risks**: index.vue / InputEditor.vue 被 T6、T7、T8、T9 四个任务触碰——执行顺序锁定为 T6 → T7 → T8 → T9；locales 被 T7、T9 触碰——同锁顺序。禁止并行改这些文件。

## WorkerSync

- Need worker-sync: **yes**
- Expected updates: `feature-routes/server-backend.md`「Agent 协作工具与挂起-恢复」（AskUserTool 描述语义、TOOL_STARTED 事件、checkIfReturnThinking 判定口径）、「SSE 聊天」（上下文恒启用）；`feature-routes/user-web.md`「Agent 对话页」（状态条/实时步骤/flush）、「输入区」（两开关移除）；同步 `docs/workerhelper/feature-routes/` 相应条目

## Risks

- T2 直接写共库（dev=生产）：执行前必须先 SELECT 命中清单给用户确认，SQL 必须幂等
- T5 行为翻转（上下文恒启用、reasoner 必思考）使依赖旧语义的两个负例测试失效——按新语义改写而非删除断言
- T8 flush 时序：done/suspension/error 必须先同步 flush，定时器须随请求代际清理，防跨请求串写
- T9 删 i18n key 前必须 grep（`usingContext` 等键可能被 QA 页复用）
