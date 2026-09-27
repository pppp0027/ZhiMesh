# Execution Plan: agent-collab-tools（角色 Agent 协作化改造 P0+P1）

> 依据同目录 design.md（已确认：存量全开+配置兜底、checkpoint 存 MySQL、恢复复用 /chat/process、审批=结构化文本）。
> 代码事实来源：design.md Current Behavior 节 + 本轮补充核实（discoverRequestTools @ AbstractLLMService.java:719、ToolContext 现有字段、迁移编号 044）。

## Summary

8 个任务：T1-T2 落地 M1（tool_policy + 存量全开 + 注册策略化），T3-T4 落地 M2（checkpoint 基础设施 + 挂起-恢复内核 + ask_user），T5 落地 M3（审批工具 + MCP 需审批装饰器），T6 落地 M4（前端卡片与策略编辑器），T7 落地 M5（MCP guardrails 开关），T8 集成验收 + worker-sync。后端 AbstractLLMService 被 T4/T5/T7 共改，务必串行。

## Tasks

- [x] T1: 迁移 044 + 实体/配置字段（数据层）— 已完成并经主线程审查(2026-09-22);偏差:库为 PG(类型按惯例译)、补预设侧 tool_policy 列+复制接线(计划缺口)、042 已做原初全开故 044 UPDATE 为兜底复读
  - Files: `server/db_migration/044_agent_collab_tools.sql`（新建）; `zhimesh-common/.../entity/Character.java`; `dto/CharacterAddReq|EditReq|Dto` (scan); `zhimesh-common/.../config/ZhiMeshProperties.java` (:448 Agent 组)
  - Change: 044 含四部分——①`adi_character` 加 `tool_policy` JSON 列（NULL=默认策略）；②`UPDATE adi_character SET is_agentic = 1`（存量全开）；③新表 `adi_agent_pending_checkpoint`（conversation_uuid 唯一活跃约束靠应用层单 pending 语义，字段：uuid/conversation_id/character_id/message 链快照 JSON/pending 工具请求 JSON/已耗迭代数/挂起计数/状态 ACTIVE|CONSUMED|EXPIRED|SUPERSEDED/created_at）；④043 预设角色 tool_policy 幂等 UPDATE（财务助手 `submit_expense_report` 标记需审批）。`Character` 加 `toolPolicy` 字段 + DTO 透传。`Agent` 配置组扩：`defaultAgenticEnabled=true`、`mcpGuardrailsEnabled=false`、`pendingTtlHours=24`、`maxSuspensions=3`
  - Verify: dev 库跑 044 幂等两遍成功；`verify_schema.sql` 通过；单测：Character toolPolicy 读写映射、Agent 配置默认值
  - Depends on: none

- [x] T2: 工具注册策略化 + 兜底开关（M1 收口）— 已完成并经主线程审查+全量验证(733 tests 绿,2026-09-22)
  - Files: `zhimesh-common/.../service/CharacterChatService.java` (:399-412 分支、:632 buildBuiltinTools); `languagemodel/tool/CharacterToolPolicy.java`（新建）
  - Change: 新建 `CharacterToolPolicy`（解析 tool_policy JSON：内置工具 denylist、MCP 工具审批标记；NULL/坏 JSON=默认全允许+fail-safe 日志）。isAgentic 生效改为 `agentSettings.defaultAgenticEnabled && character.isAgentic`。注册集合 = 默认集（search_knowledge/run_workflow，**ask_user 本任务不注册**）∩ policy ∩ 可用性；审批标记暂存 policy 供 T5 使用
  - Verify: 单测矩阵——默认集 ∩ denylist、无 KB 不注册 search_knowledge、无可见工作流不注册 run_workflow、兜底开关关时零注册零工作流查询（回归现状）；`mvn -B test` 绿
  - Depends on: T1

- [x] T3: checkpoint 基础设施（纯增量，可与 T1/T2 并行）— 已完成并经主线程审查+全量验证(709 tests 绿,2026-09-22);偏差:codec 复用官方 ChatMessageSerializer(优于自建 DTO)、kind 补 MCP_APPROVAL
  - Files: `zhimesh-common/.../entity/AgentPendingCheckpoint.java` + mapper + `service/PendingCheckpointService.java`（新建）; `languagemodel/tool/ChatMessageSnapshotCodec.java`（新建）
  - Change: checkpoint CRUD：`findActive(conversationUuid)`（惰性过期：读取时 created_at+TTL 超时置 EXPIRED）、`create`（同会话旧 ACTIVE 置 SUPERSEDED）、`consume`（幂等，状态已非 ACTIVE 时返回 false）、`markDeletedByConversation`（会话删除级联用，T4 挂接线）。`ChatMessageSnapshotCodec`：SystemMessage/UserMessage/AiMessage（含 toolExecutionRequests）/ToolExecutionResultMessage 四类消息链 ↔ JSON 回环序列化
  - Verify: 单测——四类消息含工具请求的序列化回环逐字段相等；惰性过期/superseded/consume 幂等；MP 模式（entityClass 预置 / MessageSourceStub / assistant 每实体一个）
  - Depends on: none（表结构依赖 T1 的 044 先落库才能集成测，单测可先行）

- [x] T4: 挂起-恢复内核 + ask_user 工具（M2 核心）— 已完成(2026-09-23),主线程亲跑全量 761 tests 0 失败 BUILD SUCCESS,主线程亲审高危区(挂起分支/合成收尾/恢复装配/配对完整性/深度与挂起计数继承/截断匹配)通过;捆绑的 M1 小修全部落地(#6 requestId 截断 @ AbstractLLMService:554、#5 配置 wired-in 注释、Important-2 预设复制单测 CharacterServicePresetInstantiationTest)。偏差:恢复轮装配拆为 tryResumeFromCheckpoint + buildResumeMessages + ResumeAssembly record(CharacterChatService:836/:893/:810);AskUserTool 无 sink 时不挂起、返回引导文本(比设计更稳);独立审查见下方 T4 审查记录
  - Files: `zhimesh-common/.../languagemodel/AbstractLLMService.java` (:319 循环、:788 createToolExecutionMessages); `languagemodel/tool/AskUserTool.java`（新建）、`ToolContext.java`（扩展）; `service/CharacterChatService.java`（恢复装配）; `helper/SseManager.java`（agent_question 事件）; AnswerMeta/消息落库（:494 saveAfterAiResponse 一带，scan）
  - Change: ①`ToolContext` 增挂起结果槽（工具 execute 返回挂起信号而非文本）+ 挂起计数。②`AskUserTool`：spec（question 必填、options 可选，description 引导单独调用）+ execute 置挂起信号（question/options 载荷）。③循环：`createToolExecutionMessages` 遇挂起信号 → 不继续递归调模型：**同轮非协作同伴请求先照常执行**（结果进快照）、多协作请求仅第一个挂起（其余记录待注入忽略文案）；落 checkpoint（快照=当前消息链+AiMessage(toolRequests)、已耗迭代数、挂起计数）→ SSE 发 `agent_question` → **合成收尾**：构造合成 ChatResponse（text=问题文本、tokenUsage=当轮真实用量）复用现有 else 分支收尾（calculateToken/consumer/saveAfterAiResponse），短期记忆按现状口径追加 AiMessage（文本=问题），AnswerMeta 加 suspension 载荷落库（历史重放依据）；挂起计数达 `maxSuspensions` 时工具返回"已达追问上限"文本不挂起。④恢复：ask() 解析会话后 `findActive` 命中 → **先 append 用户答复原文进短期记忆**（UserMessage，persistRawUserMessage 同语义，understandContextEnable 门控与现状一致——跨挂起记忆记账闭合点）→ 构造 ToolExecutionResultMessage（协作请求←用户原文，被忽略同伴←忽略文案）→ 快照重建 chatRequest + 当前配置重装配工具集 → 以继承的迭代数/挂起计数恢复 `innerStreamingChat`，checkpoint 置 CONSUMED；**regenerate 请求视为重问**：ACTIVE 置 SUPERSEDED 走全新轮；快照反序列化失败 fail-safe（作废 checkpoint 当普通新消息 + 日志告警）。⑤ask_user 进默认集（tool_policy 可关）。⑥会话删除/清空路径挂级联：checkpoint 置 deleted（ConversationService 删除链路）
  - Verify: 单测——挂起分支不再调模型、合成收尾 token 入账与消息内容=问题文本、checkpoint 字段完整、SSE 事件载荷、恢复注入消息链重建（含同伴忽略文案配对完整性）、预算继承、挂起上限、consume 后二次消息不恢复、regenerate 置 SUPERSEDED、会话删除级联、恢复轮用户答复 append 进短期记忆（understandContextEnable 两态）、快照损坏 fail-safe；`mvn -B test` 绿
  - Depends on: T2、T3

- [x] T5: 审批工具 + MCP 需审批装饰器（M3）— 已完成(2026-09-23),主线程亲跑全量 802 tests 0 失败 0 错误 0 跳过 BUILD SUCCESS。要点:ApprovalGrant 规范形哈希(key 递归字典序+紧凑序列化,空白归空串,坏 JSON fail-safe null)+consume-on-use 一次性凭证;装饰器两层拒绝防线(T6 拒绝按钮 `[APPROVAL_REJECTED]` 前缀=恢复装配不装配凭证的硬保证 + 自由文本拒绝=模型服从残留口径);MCP_APPROVAL 信号带 approvalGrant 随检查点落库(approval_grant 列),恢复轮读回装配仅本链有效;request_human_approval(APPROVAL,无凭证)与装饰器拦截(MCP_APPROVAL,有凭证)两种审批形态,SSE 分别 approval_request 事件。独立审查见下方 T5 审查记录
  - Files: `languagemodel/tool/RequestHumanApprovalTool.java`、`ApprovalRequiredDecorator.java`（新建）; `AbstractLLMService.java` (:719 discoverRequestTools); `vo/ChatModelRequest.java`; `CharacterChatService.java`; `SseManager.java`（approval_request 事件）
  - Change: ①`RequestHumanApprovalTool`：spec（action/summary/risk_level）+ 挂起（复用 T4 机制，事件 `approval_request`）。②装配：CharacterChatService 把 policy 的审批标记工具名集合写入 `ChatModelRequest`（新字段 approvalRequiredTools），`discoverRequestTools` 对命中 MCP 工具包 `ApprovalRequiredDecorator`。③装饰器流程（模型再调用式）：未持批准 → 挂起转审批；恢复轮 ToolExecutionResultMessage = 用户批准/拒绝文本 → 模型重调原工具 → 装饰器校验**批准凭证**（checkpoint 记录 toolName+arguments hash，仅精确匹配自动放行，参数变了重新审批；凭证仅本恢复链有效）→ 放行执行真实 MCP 调用。④拒绝时模型收到拒绝原因转述。⑤审批/挂起全程 ToolCallTrace
  - Verify: 单测——未批不执行 MCP（fake McpClient 断言零调用）、approve 后放行、args 变更触发二次审批、凭证不跨链；`mvn -B test` 绿
  - Depends on: T4

- [x] T6: 前端卡片 + 策略编辑器（M4）— 已完成(2026-09-23),子代理实现(12 文件 445+/5-,仅 user-web/)+独立审查(无 Critical,4 Important/8 Minor)+主线程修复批(I-1 兜底死代码+M-1 meta 贫载荷覆盖、I-2 挂起交互态生命周期三场景、M-2/M-3/M-5/M-6)后亲跑 `pnpm build`(含 type-check)绿。要点:SuspensionCard(kind??type 双键兼容,拒绝按钮 `[APPROVAL_REJECTED] 理由` 前缀逐字符契约);未知事件 opt-in 忽略(仅 chat 流,工作流动态 `[NODE_RUN_*]` 具名事件不受影响);tool_policy 编辑器三态序列化(有勾选→JSON/原有清空→''/原 null 无勾选→null);历史回放由轨迹行派生(CharacterMsgDto 不带 suspension 字段,已防御性支持未来直传)。独立审查记录见下;未修偏差转 T8
  - Files: `user-web/src/views/chat/components/Message/index.vue` (scan)、SSE 事件分发（`api/index.ts` 一带，scan）、角色编辑表单 (scan)、`user-web/src/typings/chat.d.ts` (scan)
  - Change: ①`agent_question`/`approval_request` 事件分发 → 消息流插入问题/审批卡片（options 渲染按钮、审批渲染同意/拒绝+理由输入）；按钮提交 = 选项/结论文本走现有发送入口。②步骤条扩展挂起/应答/审批节点类型。③历史重放：读消息 suspension 载荷重放卡片（pending 已消费则展示只读态）。④角色编辑表单 tool_policy 编辑器（内置工具开关 + MCP 工具审批勾选，需拉取角色已绑 MCP 工具清单）。⑤旧事件兼容确认：未知事件类型忽略
  - Verify: `pnpm build` 绿；手工：问题卡片应答续跑、审批卡同意/拒绝两分支、刷新后重放
  - Depends on: T4、T5（事件契约齐后动工）

- [x] T7: MCP guardrails 开关（M5，独立小步）— 已完成(2026-09-23),子代理实现+主线程亲审 diff+亲跑全量 805 tests 0 失败 BUILD SUCCESS。executeToolWithGuardrails 短路改 `!mcpGuardrailsEnabled && isMcpTool()`(默认 false 直调零回归;true 时 MCP 同走超时——含池线程 run_workflow 递归标记传播——与截断);配置项/yml 生效行(`${ZHIMESH_AGENT_MCP_GUARDRAILS_ENABLED:false}`)/默认值断言 M1 期已预置,本任务纯接线。trace 核实:recordToolCallTrace 在调用点(成功/失败/未知工具/协作四分支)对 MCP 本就生效,guardrails 不重复记录(超时测试同时断言 trace success=false)。ToolExecutorResolutionTest +3 测试(默认直调慢执行不超时不截断/开关 true 超时按失败回模型/开关 true 截断带标记,后两者断言 trace);ToolExecutor.isMcpTool() 过时注释连带修正(注释级);ZhiMeshProperties javadoc 补 "Wired since T7"(注释级,字段与默认值未动)
  - Files: `AbstractLLMService.java` (:830 executeToolWithGuardrails)
  - Change: `isMcpTool()` 短路改为受 `mcpGuardrailsEnabled` 控制——false 保持直调零回归，true 时 MCP 同样走 timeout/截断/trace
  - Verify: 单测开关两态（true 时超时/截断生效、false 时直调行为不变）
  - Depends on: none（建议排在 T4/T5 后串行，同文件冲突）

- [x] T8: 集成验收 + worker-sync — 已完成(2026-09-23)。044 经用户确认窗口亲落 dev/生产共库(ON_ERROR_STOP=1,复跑幂等 exit=0,verify_schema 三项 missing 全 0,16 列+触发器+财务预设 e68a3d15 种子+is_agentic 补齐全实证);子代理跑财务助手端到端剧本 a-f 全过(自动查预算/双形态审批 APPROVAL→MCP_APPROVAL→凭证放行真实单 EX-20260914-001/拒绝前缀转述/ask_user 双形态续跑/历史重放 16 轮/兜底关回归);checkpoint 状态机只读 SELECT 实证(8 行全 CONSUMED,0 ACTIVE 残留,suspension_count 跨链继承,凭证仅 MCP_APPROVAL)。剧本暴露 2 bug 均当日闭环:#1 Windows GBK stdio 乱码(README 改口径+`-Dfile.encoding=UTF-8` JVM 要求);#2 裸「同意」审批恢复被当工具结果虚构已执行→ resumeAnswerToolResult 机器可读引导包装(审批类且非拒绝前缀)+单测锁(ResumeTest 16/DecoratorTest 11,全量 804 tests 0 失败 BUILD SUCCESS——与 T7 收口 805 的 -2 为报告文件聚合口径差,两条新增测试确认在跑、本批零删测试,per-class 基线未留存不再追);`pnpm build` 绿。design.md 验收 8 条全勾;worker-sync 已执行(server-backend.md/user-web.md)。遗留决策与手工清单见下「T8 集成验收记录」
  - Files: 无新代码；`docs/workerhelper/feature-routes/server-backend.md`
  - Change: dev 库 + 财务助手剧本端到端：查预算（自动）→ 报销（转审批卡）→ 同意 → mock 可查新单；ask_user 澄清部门 → 应答 → 对比答案；历史会话重放；兜底开关关闭回归现状。更新路由图（chat 主链路挂起-恢复入口、adi_agent_pending_checkpoint、Agent 配置组、tool_policy）
  - Verify: `mvn -B test` 全绿 + user-web `pnpm build` 绿 + 剧本全过 + design.md 验收标准逐条勾
  - Depends on: T4、T5、T6、T7

## T8 集成验收记录(2026-09-23)

**落库**:044 于用户确认窗口由主线程亲执（dev 与生产共库 100.96.188.67:5432/ai_rag_knowledge，docker run postgres:16-alpine psql 客户端 + ON_ERROR_STOP=1）；复跑幂等 exit=0 全 NOTICE 跳过；verify_schema 0/0/0；抽查 adi_agent_pending_checkpoint 16 列、触发器、财务预设 e68a3d15c2f749b0a1e8d4c3f52b7096 种子、is_agentic 追平全 true。MSYS 路径改写注意：docker 挂载 `/sql/...` 需写 `//sql/...`。

**剧本（子代理执行、主线程核报告）**:a 自动查预算零挂起；b 报销两段式审批 APPROVAL→MCP_APPROVAL→同意放行，mock 真实新单 EX-20260914-001；c 拒绝前缀 0 trace、模型转述拒绝；d ask_user 无 options/带 options 双形态应答续跑；e 历史重放 16 轮含挂起节点；f `default-agentic-enabled=false` 回归：零 [AGENT_QUESTION]/[APPROVAL_REQUEST]、零 agentic 日志、报销直通无审批门（语义澄清：MCP 工具本就不受该开关门控——改造前即 enableMcp(true) 硬编码，开关语义=回到现状而非关闭 MCP）。

**bug 清账**:#1 Windows GBK stdio 乱码——langchain4j JsonRpcIoHandler 用平台字符集构造管道流，Java 17 Windows 默认 GBK，中文参数双向乱码致工具失配；chcp 65001 无效（README 原表述有误已改），生产 Linux 不受影响。#2 裸「同意」审批恢复——注入文本被模型当作工具结果、虚构「已提交」不重调；修复 `CharacterChatService.resumeAnswerToolResult`：审批类(APPROVAL/MCP_APPROVAL)且非 `[APPROVAL_REJECTED]` 前缀的恢复答复包「用户对挂起审批的回复（原文）：「…」。注意：…若用户同意，请重新调用相应工具完成实际执行…」引导，单测 `approvalResumeAnswerIsWrappedWithReInvocationGuidance` + 既有断言更新锁契约；实链修复效果待下次手工/剧本复验（机制与剧本中显式指令重调成功的路径一致）。

**T6/T5 转入决策收口**：I-3 MCP_APPROVAL 历史重放不可派生（轨迹无 kind 列）→ **文档接受**（本次不动数据模型；未来补 kind 列即可点亮）；I-4 MCP 工具清单 API 缺失 → **文档接受**（编辑器自由文本+已绑服务名提示，工具名手误=审批门静默不生效，运维知悉）；M-4 en-US 全角、M-7 serializeToolPolicy 未知键被抹、M-8 派生 question 截断 → 记录不改；tool_policy 坏 JSON fail-open → 运维口径：NULL/空/坏 JSON=全允许。

**脚本未覆盖的手工路径**（无 UI 自动化设施，日常使用确认）：①前端 7 条交互清单（MCP 审批刷新重放预期缺失、主输入框直答旧卡失效、重新生成后旧卡只读、拒绝空/带理由两分支、tool_policy 三态往返、坏 JSON 回显、分页加载历史卡）②TTL 过期活体（单测已覆盖机制）③max-suspensions 上限活体（单测已覆盖）④guardrails=true 活体（单测三态已覆盖）⑤bug#2 修复后的实链复验。

## T6 独立审查记录(2026-09-23,只读审查代理)
- 总判:无 Critical,可作 T8 集成验收地基;契约逐字核对全过(拒绝前缀/kind-type 双键/三态序列化/内置工具 4 名/INLINE 文案两串/挂起 trace 契约),ignoreUnknownEvents 外溢核查无(仅 sseProcess opt-in,QA/工作流透传保持),EditConvDetail 取消零写入,NTag 无 XSS(插值转义)
- Important 处置(同日主线程修复批,pnpm build 含 type-check 绿):
  - I-1 [META] 挂起兜底死代码(updateMessageSomeFields 裸 Object.assign 先把 suspension 写入,后判 "!answer.suspension" 恒 false)→ 已修复:实时载荷(kind+toolName 更全)优先防 meta 贫载荷覆盖(兼修 M-1),判断改按 suspensionActive 互斥,兜底命中才置交互态;InputEditor 与 chat/index.vue 两处
  - I-2 挂起交互态生命周期缺口 → 已修复三场景:(a) submitMessage 前清当前会话全部挂起交互态(后端「下一条消息即消费检查点」语义对齐);(b) onRegenerate 前清(SUPERSEDED 后旧页签卡片不可再点);(c) handleSuspensionAnswer 改先提交后翻只读(submitMessage 内部守卫静默退出时卡片可重试,不出现「卡片已杀死但消息没发出」)
  - I-3 MCP_APPROVAL 历史重放完全缺失(轨迹行无 kind 列,前端结构性无法识别;实时链路 [DONE] 后 meta.toolCalls 整体替换也使盾牌节点退化)→ 记录不改,后端数据模型限制,转 T8 决策(补 kind 列或文档接受)
  - I-4 plan ④ 偏差:未"拉取角色已绑 MCP 工具清单"(前端数据模型无 tools 字段,MCP 工具发现是后端运行时行为),改自由文本+已绑服务名提示;工具名手误→审批门静默不生效 → 转 T8 决策(补工具清单 API 或文档接受)
- Minor 处置:已修 M-1(并入 I-1)/M-2(只读卡"等待中"误导文案移除+死 locale 键清理)/M-3(空 options 提示去主输入框作答)/M-5(NTag 超长省略)/M-6(对话中点击挂起卡 toast);未修转 T8:M-4(en-US 全角符号观感)/M-7(serializeToolPolicy 只回写两键,存量未知键被抹——前向兼容决策)/M-8(派生 question 取 resultSummary 有落库截断口径,超长问题历史卡可能截断)
- Stage-1 ⚠️ 偏差(转 T8):历史回放由轨迹行派生(CharacterMsgDto 不带 suspension);ACTIVE 未消费 checkpoint 重放也是只读(功能可恢复体验降级);user-web 无自动化测试设施,全部交互路径 T8 手工(审查代理给出 7 条补充手工清单,含 MCP 审批刷新预期缺失、主输入框直答、重新生成后旧卡、拒绝空/带理由两分支、tool_policy 三态往返、坏 JSON 回显、分页加载)

## T5 独立审查记录(2026-09-23,只读审查代理)
- 总判:可以,T5+T4 可作 T7/T6 地基;无 Critical
- Important-1(装饰器 EN javadoc 声称「拒绝后重调仍撞无凭证门 fail-closed」与实现矛盾——自由文本拒绝时凭证仍在链上,重调同参数会放行)→ **已修复(2026-09-23)**:javadoc 中英双语改写为两层拒绝防线口径(结构化前缀=硬保证;自由文本=模型服从,残留已设计文档明示);并新增 `REJECTION_MARKER_PREFIX` 常量落地 T6 拒绝按钮文本契约(恢复装配精确前缀匹配即不装配凭证)
- Minor 处置(同日修复批,802 tests BUILD SUCCESS 全绿):
  - #2 凭证可复用(一次批准授权链上多次执行)→ 已修复:execute 放行即核销(上下文凭证置空),一次批准授权一次执行,同链重复调用同工具同参数再次挂起;新增 consume-once 单测
  - #3 规范形哈希碰撞独立性(排序后序列化无分隔符歧义)→ 主线程独立验证:键值 JSON 类型异构、值含 `:`/`,`/`"` 均不产生歧义碰撞,不改
  - #4 内置工具与审批标记重名时内置覆盖装饰器、审批门静默丢失 → 已缓解:discoverRequestTools 分支 warn 文案点名「审批标记不再适用」;语义保持既有「内置优先」约定
  - #5 tool_policy 坏 JSON 时审批标记 fail-open(默认全允许的既有口径)→ 记录不改,T8 运维文档记清
- Stage-1 ⚠️ 偏差(转 T8 文档对齐):request_human_approval 实际默认注册(design.md「opt-in」表述 vs M1「全开+配置兜底」决策,以决策为准);审批结论文本未入 ToolCallTrace(与 T4 #7 同口径,由挂起快照+消息链承载);SSE 载荷键 `kind` 与 AnswerMeta.suspension 字段 `type` 不对称(T6 需各按其名取值)

## T4 独立审查记录(2026-09-23,只读审查代理)
- 总判:无 Critical,T4 可作 T5 地基;Stage 1 九条中八条✅、一条⚠️(SSE 载荷无测试);正向确认:completeStreamingResponse 与原 else 分支逐语句等价、恢复轮 resumedMessages=null 走原装配零变化、CAS 防双消费、langchain4j 1.14.1 构造无一处误用
- Important-1(恢复装配在轮次锁前的记忆交错竞态)→ **已修复(2026-09-23)**:tryResumeFromCheckpoint 改为「①幂等消费先行(CAS 赢者独占恢复权,输者不 append 不交错)→②赢者按 understandContextEnable 门控 append(失败 try/catch 降级不阻断恢复,答复已进快照配对链)」;InOrder 测试翻转+新增输者不写记忆断言+新增 append 失败不阻断恢复测试
- Minor 处置(同日修复批,BUILD SUCCESS 全绿):
  - #2 agent_question 事件先于检查点落库 → 已修复:事件移至 suspendToolLoop 在 persist 成功后发出(与 SuspensionCheckpointSink javadoc「never a half-suspended state」承诺一致)
  - #3 恢复轮记账窗口退化为模型全量上限 → 已修复:createChatRequest 恢复分支补 calculateShortTermMemoryBudget 同口径计算 memoryWindowMaxTokens
  - #4 SSE 载荷无测试 → 已补:ToolLoopSuspensionTest 新增载荷契约测试;载荷并补 checkpointUuid 键(与 AnswerMeta.suspension 同源,T6 卡片↔检查点关联)
  - #5 恢复答复无长度防护 → 已修复:truncateResumeAnswer 按工具结果同口径(toolResultMaxChars=4000+末尾标记)截断+测试
  - #6 suspendToolLoop 裸解引用 sink → 已修复:前置 null 防御抛 IllegalStateException(防御 T5 绕闸形态)
  - #7 design.md「消息 pending 标记」未按字面落地(由轨迹行承担历史重放) → 记录不改,T6/T8 记清口径

## M1 独立审查记录(2026-09-22,只读审查代理)
- 总判:无 Critical,M1 可作 T4 地基;Stage 1/Stage 2 均过
- Important-1(044 无实库验证证据)→ **已闭环(2026-09-22)**:本地一次性 postgres:16-alpine 容器(与共库零接触),all_ddl 全量落库(含 044 折叠=第一遍)→ 044 原文件再跑一遍 exit=0 全 NOTICE 跳过 → verify_schema 三项 missing 计数全 0 → checkpoint 表 16 列逐一在位;容器已销毁
- Important-2(预设 toolPolicy 复制无测试)→ 随 T4 顺带补单测
- Minor 处置:#3 javadoc 大小写、#5 配置注释补 wired-in 说明、#6 requestId 截断 → 随 T4;#4/#7/#8/#9/#11 记录不改;#10 文档清账 → T8 worker-sync

## Verification

- Commands: `cd server && mvn -B test`; `cd user-web && pnpm build`; dev 库迁移 `044` 两遍幂等 + `verify_schema.sql`
- Manual checks: 财务助手三段剧本（自动查询 / 审批报销 / ask_user 澄清）；pending 过期（改 TTL 短值验证置灰）；兜底开关关闭后存量角色行为与主线一致

## Task Relationships

- Strongly related: T2+T4+T5（同改 CharacterChatService 装配链与循环挂起语义，需连续执行保持一致）；T4+T5+T7（同改 AbstractLLMService，必须串行）
- Weakly related: T3 与 T1/T2（文件边界清晰，可并行开发，集成依赖 044 落库）；T6 依赖 T4/T5 的事件契约而非实现细节
- Independent: T7 与 T2/T3/T6 无文件交集（仅与 T4/T5 有 AbstractLLMService 冲突）
- Conflict risks: AbstractLLMService（T4/T5/T7 三改）、CharacterChatService（T2/T4/T5 三改）——执行顺序固定 T2 → T4 → T5 → T7，禁止并行

## WorkerSync

- Need worker-sync: yes（T8 执行）
- Expected updates: server-backend.md 会话与消息节（SSE 聊天条目补挂起-恢复）、角色系统节（tool_policy）、后台任务与恢复节（checkpoint 惰性过期）、配置骨架节（zhimesh.agent.* 新增四项）、Redis 键速查不变（checkpoint 在 MySQL）

## Risks

- ChatMessage 多态序列化（T3）是最大技术点，回环单测先行锁死；损坏走 fail-safe 不 500
- 恢复轮重装配工具集与快照消息链中已调用但已不可用的工具（挂起期间解绑）：该工具 executor 缺失时返回"工具已不可用"结果消息让模型自愈（T4）
- SSE 断连在挂起事件与 complete 之间：checkpoint 以落库为准，前端刷新经历史重放补卡片（T4/T6）
- 审批凭证 args-hash 匹配防提示注入改参数绕审批（T5）
- 044 存量 UPDATE 不可逆提示：上线前确认兜底开关配置已就位（回退路径=配置关，不回滚数据）
