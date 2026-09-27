# Design: agent-chat-ux（Agent 对话体验改造）

> 来源：2026-09-24 用户实测「产品与市场分析师」对话（会话 `fd0c4687…`）后的三点反馈。
> Route lookup 见会话记录：覆盖度 sufficient，关键文件均有 route-map/scan 来源标注。

## Goal

1. **提问策略**：`ask_user` 只在缺少**会改变任务方向、无法合理默认的关键信息**时提问；能合理默认的维度自行假设并在回答中说明，不把全部选择抛给用户。
2. **等待期互动反馈 + 流式性能**：动态状态条（分析→检索→调用工具→生成）、工具执行中实时可见、流式渲染批量 flush 消除 O(n²) 卡顿。
3. **开关收敛**：移除「深度思考」「连续对话」两个按钮——上下文永远开启；深度思考按模型能力自动决定（推理模型=有思考，非推理模型=无思考），速度由用户选什么模型决定。

## Scope

- In：
  - `AskUserTool` 工具 description 重写（全局生效）
  - 023 种子文案修正（产品与市场分析师）+ 存量角色幂等 UPDATE（用户已确认：种子+存量一起修）
  - 网页抓取 MCP remark 追加「不要抓取搜索引擎结果页」引导（实测 Brave/Google 被 robots.txt 拒、白等 3.5s）
  - 后端新增 `TOOL_STARTED` SSE 事件（普通工具 + 协作工具执行前发送）
  - 前端动态状态条 + 工具步骤 running 态 + 流式批量 flush（~60ms）
  - 移除 PC（InputToolbar）与移动端（Header/index.vue）的两个开关按钮及配套 watch/auto-close 逻辑
  - `checkIfReturnThinking` 改为纯模型能力判定；聊天管线 `understandContextEnable` 条件全部改为恒启用
- Out：
  - 不改挂起-恢复内核、审批链路、tool_policy、MCP guardrails
  - 不改模型选择器/模型管理/043 领域预设（本轮只动 023 中被实测证实的文案）
  - `character/toggleThinking`、`character/toggleUsingContext` 后端端点保留（兼容），前端不再调用
  - `is_enable_thinking`/`understand_context_enable` 列保留但聊天行为不再读取（后续清理另起任务）

## Assumptions

- **DeepSeek 系 reasoner + 工具/联网 → 思考自动关闭（工具优先）**：依据仓库内既有 TODO（langchain4j #3461，partialArguments cannot be null）。原前端 workaround 是"删工具保思考"，现反转为后端"保工具关思考"，且不再改用户数据。
- 存量角色 UPDATE 仅命中 `ai_system_message` 与旧种子文案**逐字相同**的行（用户没改过的），幂等可重复执行；改过的角色不动。
- 前端 `useUsingContext` hook 为遗留未使用代码，一并清理；`emptyCharacter()` 默认值字段保留（typings 兼容）。
- 长答案的 O(n²) 渲染问题已在代码事实中确认：`index.vue` messageReceived 逐字符 `appendChunk` → `Text.vue` computed 全量 `mdi.render`。

## Current Behavior → Proposed Behavior

### 1. 提问策略

| | 现状 | 目标 |
|---|---|---|
| 工具描述 | "缺少完成任务的必要信息（如部门、**时间范围**、偏好等）时应调用，**不要自行假设**" | 只问缺失的、对任务方向有决定性影响的关键信息；每次只问当前最关键的一个问题（确需确认至多两个）；可合理默认的维度自行假设，并在最终回答开头简要列出所做假设；提问时附带建议默认值供用户确认（如"按中国大陆分析可以吗？"） |
| 预设种子 | "先明确分析目标、范围、**时间窗口**和判断标准"（产品与市场分析师，023） | 改为"基于合理默认的范围与时间窗口直接开展分析，仅在缺少影响结论方向的关键信息时询问一次" |
| 存量角色 | 3+ 副本带旧文案 | 幂等 UPDATE 逐字匹配替换 |

### 2. 交互反馈

| | 现状 | 目标 |
|---|---|---|
| 等待期 | 静态"问题分析中"转圈（`Text.vue:61`） | 状态条按 `question.state` 流转：分析中 → 检索知识库（补 i18n，`knowledge_searching` 事件已发但前端无文案不渲染）→ 正在调用工具 X → 生成回答中 |
| 工具执行 | 完成后才发 `TOOL_CALL`，步骤默认折叠，执行中无感知 | 执行前发 `TOOL_STARTED`（toolName+args 摘要），步骤实时点亮 spinner，摘要行显示"正在执行…"；完成事件回填时长/结果 |
| 流式渲染 | 逐字符 appendChunk → 每字符全量 markdown 重渲染 | 缓冲 + ~60ms 定时批量 flush；done/suspension/error 时立即 flush 防丢尾 |

### 3. 开关

| | 现状 | 目标 |
|---|---|---|
| 深度思考 | 按钮（`isReasoner && isThinkingClosable` 时显示）写 `character.is_enable_thinking`；DeepSeek watch 自动清空工具/联网 | 按钮移除。`checkIfReturnThinking`：非 reasoner → null；reasoner → true；DeepSeek 系且（挂 MCP 工具或联网）→ false。判定入参需含平台标识（`aiModel` 之外传入 platform 名或 service 类型） |
| 连续对话 | 按钮写 `understand_context_enable`；管线 5 处按列值门控（记忆检索/记忆注入/工具记忆/回填） | 按钮移除。管线条件全部改为恒启用；列保留不读 |
| 行为变化（需知晓） | 存量 `is_enable_thinking=false` 的 reasoner 角色现在会真正开启思考（更深但更慢）；之前关上下文的角色现在全部携带历史（token 用量上升）——这正是"按模型能力/永远在线上下文"的语义 | |

## Implementation Direction

1. **AskUserTool.SPEC.description**（`AskUserTool.java:57-60`）：按上表目标重写，保留"单独调用，不与其他工具同轮"约束。
2. **023 种子**（`023_seed_useful_mcp_presets.sql`）：沿用文件内既有 amendment 模式（文件头部已有一段"Upgrade an earlier draft"式幂等 UPDATE 先例）：
   - `UPDATE adi_character_preset SET ai_system_message = 新文案 WHERE title='产品与市场分析师' AND ai_system_message = 旧文案`
   - `UPDATE adi_character SET ai_system_message = 新文案 WHERE ai_system_message = 旧文案`（存量副本；不限定 title，逐字匹配即安全）
   - 网页抓取 MCP remark 追加一句"不要抓取搜索引擎结果页（会被 robots.txt 拒绝），直接抓取目标网站"
3. **TOOL_STARTED**：`ZhiMeshConstant.SSEEventName` 新增事件名；`SseManager.sendToolStarted(uuid, toolName, argsSummary)`（载荷与 TOOL_CALL 同构的精简版）；`AbstractLLMService.executeToolRound`（:1130 前）与 `executeCollaborativeRequest`（:1206 前）执行前发送；guardrails 的 TOOL_LIMIT_MARKER（:372）只有完成事件，前端需兼容"无 started 的完成"。
4. **前端状态条**：`Message/index.vue` 接收 `state`（index.vue 已有 `question.state` Map，目前收了不渲染）向下传递；`Text.vue` loading 态按 state key 显示对应 i18n 文案；`InputEditor.vue` 同步（它也有独立 sseProcess 通道 :210）。
5. **工具步骤实时化**：`api/index.ts` commonSseProcess 分发 `TOOL_STARTED` → chat index.vue/InputEditor.vue 往 `answer.toolCalls` push `{toolName, running: true}`；完成事件按"同名最近的 running 步骤"回填，无匹配则直插（兼容 marker/历史回放）；`ToolSteps.vue` running 态 spinner + 摘要行进行中文案。
6. **批量 flush**：`index.vue`/`InputEditor.vue` 的 messageReceived/thinkingDataReceived 改缓冲数组 + 60ms 定时器 appendChunk；doneCallback/suspensionReceived/errorCallback 先同步 flush 再走原逻辑。
7. **开关移除**：InputToolbar（按钮、`toogleThinking`/`toggleUsingContext`/`isDeepSeekThinking` watch 链）、Header/index.vue（移动端图标）、index.vue `toggleUsingContext`、`api/index.ts` 两个函数、相关 i18n key、`useUsingContext.ts` 删除。
8. **后端判定**：`CharacterChatHelper.checkIfReturnThinking` 重写（签名扩展平台参数）；`buildChatRequestParams`/`CharacterChatService`（:444/:743/:919/:1293）/`LocalAgentService`（:99）移除 `understandContextEnable` 条件。

## Affected Files

**后端**
- `server/zhimesh-common/.../languagemodel/tool/AskUserTool.java` (route-map)：description 重写
- `server/zhimesh-common/.../util/CharacterChatHelper.java` (route-map)：checkIfReturnThinking 重写；:483 上下文条件移除
- `server/zhimesh-common/.../languagemodel/AbstractLLMService.java` (route-map)：两处工具执行前 sendToolStarted
- `server/zhimesh-common/.../helper/SseManager.java` (route-map)：sendToolStarted
- `server/zhimesh-common/.../cosntant/ZhiMeshConstant.java` (route-map)：SSEEventName.TOOL_STARTED
- `server/zhimesh-common/.../service/CharacterChatService.java` (route-map)：:444/:743/:919/:1293 条件移除
- `server/zhimesh-common/.../service/LocalAgentService.java` (scan)：:99 条件移除
- `server/db_migration/023_seed_useful_mcp_presets.sql` (scan)：种子修正 + 存量 UPDATE + fetch remark

**前端**
- `user-web/src/views/chat/InputToolbar.vue` (route-map)：移除两按钮 + DeepSeek watch 链
- `user-web/src/views/chat/index.vue` (route-map)：toggleUsingContext、Header 传参、批量 flush、started 分发
- `user-web/src/views/chat/components/Header/index.vue` (scan)：移动端开关图标移除
- `user-web/src/views/chat/components/Message/index.vue` (route-map)：state 透传 + 状态条
- `user-web/src/views/chat/components/Message/Text.vue` (route-map)：动态状态文案
- `user-web/src/views/chat/components/Message/ToolSteps.vue` (route-map)：running 态
- `user-web/src/api/index.ts` (route-map)：TOOL_STARTED 分发；移除两个 toggle 函数
- `user-web/src/views/chat/InputEditor.vue` (route-map)：独立 SSE 通道同步（flush + started）
- `user-web/src/typings/chat.d.ts` (scan)：ToolCall.running
- `user-web/src/locales/zh-CN.ts` / `en-US.ts` (scan)：knowledge_searching 文案、进行中文案、清废弃 key
- `user-web/src/utils/functions/index.ts` (scan)：emptyCharacter 语义占位
- `user-web/src/views/chat/hooks/useUsingContext.ts` (scan)：删除

## Risks

1. **生产共库**：存量 UPDATE 直接作用于生产数据——SQL 必须幂等、先 SELECT count 验证命中行再执行，执行后抽查。
2. **行为变化需告知**：reasoner 角色响应会变慢（真实开启思考）；上下文全开使 token 用量上升。
3. **新 SSE 事件**：老前端收 `TOOL_STARTED` 走 `ignoreUnknownEvents` 兜底（route-map 已确认会话流开启）；新前端须兼容"无 started 的完成事件"与历史回放（无 started）路径。
4. **DeepSeek 判定**：以平台/服务标识判定（`DeepSeekLLMService` 类型或 platform name），勿用模型名子串（前端旧逻辑用 `modelName.includes('deepseek')` 不可靠，后端不做这种猜测）。
5. **flush 时序**：done/suspension 必须先同步 flush，否则尾部字符丢失或挂起卡片与文本错位。
6. **受影响存量单测**：`ToolLoopSuspensionTest`/`CharacterChatServiceResumeTest` 等若依赖 `understandContextEnable=false` 门控或旧 `checkIfReturnThinking` 签名需按新语义更新。

## Test Strategy

- 单测：`checkIfReturnThinking` 新判定矩阵（非 reasoner / reasoner / DeepSeek+工具 / DeepSeek+联网 / 非DeepSeek reasoner+工具）；`AskUserTool` 既有校验回归；受影响测试按新语义修复。
- SQL 验证：本地库先 `SELECT id, title FROM adi_character WHERE ai_system_message = 旧文案` 核对命中（预期：预设实例化且未改提示词的副本），UPDATE 后复查归零。
- 手动验收：重放"小型机器人市场调查"场景，对照 Acceptance Criteria 逐条检查（PC + 移动端各一遍）。

## WorkerHelper Impact

- Need worker-sync: **yes**
- Affected routes：server-backend「Agent 协作工具与挂起-恢复」（AskUserTool 描述、TOOL_STARTED、thinking 判定）、「SSE 聊天」（上下文恒启用）；user-web「Agent 对话页」（状态条/步骤/flush）、「输入区」（开关移除）。

## Acceptance Criteria

1. 新对话中 agent 不再一次抛多问：可默认维度（时间范围、地区等）自行假设并在回答开头说明；仅在缺失关键信息时提 1 个（至多 2 个）问题，且带建议默认值。
2. 「产品与市场分析师」存量副本提示词已替换为新文案（逐字匹配旧行归零）。
3. 等待期状态条动态流转（分析→检索→调用工具→生成），工具执行中步骤实时 spinner 可见。
4. 长答案流式输出无明显卡顿（批量 flush 生效）。
5. PC 与移动端输入区均无「深度思考」「连续对话」按钮，布局无残留空洞。
6. 推理模型自动携带思考流；非推理模型无思考流；DeepSeek 系挂工具/联网时思考自动关闭且工具可用。
7. 任意角色多轮对话均携带上下文（第二句"它呢？"类指代可被理解）。
8. 后端既有单测全绿（按新语义更新的除外），前端构建通过。
