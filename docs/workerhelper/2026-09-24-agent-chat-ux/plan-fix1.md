# Fix Plan 1: agent-chat-ux 验收反馈修复（2026-09-24 第二轮）

> 输入：用户实测后 4 个新问题 + 2 个产品决策（思考=完成后自动折叠；来源标注=提示词+前端来源列表）。
> 根因诊断（已查库与代码确认）：见本文件各任务"Root cause"。本修复无后端 Java 代码改动（F4 为纯 SQL 文件）。

## Tasks

- [x] F1: markdown 中文标点加粗渲染修复
  - Files: `user-web/src/views/chat/components/Message/Text.vue`
  - Root cause: CommonMark 强调规则——闭合 `**` 前是全角标点（如 `：`）且后紧跟汉字时不算 right-flanking，`**假设：**以下` 原样显示星号（库里消息 1936 实证）。
  - Change: 在 `text` computed 的 mdi.render 前加预处理：对「闭合 `**` 前一字符为 CJK/全角标点、后一字符为非空白」的 span，在闭合 `**` 后插入发丝空格 U+200A（Unicode 空白，使闭合合法且视觉不可见）。正则要点：`[^*\n]*` 限定行内；仅命中 `[　-〿！-～‘-”…]` 结尾的加粗；英文常规加粗不受影响。不引入新 npm 依赖（markdown-it@13 与 cjk-friendly 插件兼容性存疑，弃插件方案）。
  - Verify: `pnpm type-check`；用例自测：`**假设：**以下`、`**建议的产品定位：**不要`、`**2026年**，部分`（此例本就合法，不应被改动产生双空格）、英文 `**bold** next`。
  - Depends on: none

- [x] F2: 思考面板完成后自动折叠
  - Files: `user-web/src/views/chat/components/Message/index.vue`
  - Root cause: watch(props.thinking) 在思考结束时把 expandedNames 置为 `['thinking','finalAnswer']`，思考全文始终展开摆在回答上方，被当成答案的一部分（用户实测反馈）。
  - Change: 思考中（thinking=true）→ `['thinking']`；结束（false）→ `['finalAnswer']`（思考自动收起、可手动点开）。历史回放（thinking 初始即 false）保持初始 `['finalAnswer']` 不变。保留思考中脉冲圆点。`chat.deepThinking`/`chat.finalAnswer` i18n key 保留（面板仍在）。
  - Verify: `pnpm type-check`；手动：思考中面板展开→回答出来后自动收起；历史回放思考默认收起。
  - Depends on: none（与 F3 同文件，同一子代理顺序执行）

- [x] F3: 消息 meta token 展示加文字标注与"累计"说明
  - Files: `user-web/src/views/chat/components/Message/index.vue`、`locales/zh-CN.ts`、`locales/en-US.ts`
  - Root cause: meta 行只有 ↑↓ 图标+数字无文字，用户把累计输入 88212 / 输出 11258 看反；且数字是全部工具轮次的累计值（LLMTokenUtil 按轮求和），无口径说明。
  - Change: 数字前加文字标签（`chat.metaInputTokens`="输入"/"In"、`chat.metaOutputTokens`="输出"/"Out"），并加 title 提示（`chat.metaTokensCumulativeTip`：输入/输出为本次问答全部模型轮次的累计值，含每次工具调用后重新携带的上下文）。
  - Verify: `pnpm type-check`；grep 确认新 key 在两个 locale 均存在。
  - Depends on: none

- [x] F4: 网页抓取 MCP remark 补齐三条行为约束
  - Files: `server/db_migration/023_seed_useful_mcp_presets.sql`
  - Root cause: ①模型每轮只发 1 个 fetch（后端 executeToolRound 本就支持一轮多调用），12 轮串行导致输入 token 按轮累计 88212、耗时 208s；②3 个 JS 渲染页只抓到标题（57~92 字符）仍被当作有效资料；③正文只在开头泛述"以本次抓取的官方页面为准"，无逐处来源（用户选：提示词+前端来源列表双管）。
  - Change: 沿用文件内 amendment 模式，UPDATE `adi_mcp` id=5（WHERE remark=当前完整旧文案逐字匹配，库中已验证）与 NOT EXISTS INSERT 分支两处，remark 末尾追加："多个同类页面尽量在同一轮并行发起多个抓取调用，减少串行轮次；返回内容过短（仅页面标题）视为抓取失败，不要基于该页展开分析；回答中引用抓取到的事实时，须在该事实后括注来源站点与抓取日期（如：据 notion.com 定价页，2026-09）。"
  - Verify: 无 Java 改动不需 mvn；用户手动 psql 执行后用只读通道查库验证（预期 UPDATE 1 行；第二遍 0 行）。库上当前旧文案全文：`抓取公开网页内容并提取正文，适合研究、资料整理和知识库准备。首次运行需要安装 uv/uvx，并使用 mcp<2 约束启动（官方包与 mcp SDK 2.x 暂不兼容）。不要抓取搜索引擎结果页（会被 robots.txt 拒绝），直接抓取目标网站。`
  - Depends on: none

- [x] F5: 工具步骤下自动渲染"信息来源"列表
  - Files: `user-web/src/views/chat/components/Message/ToolSteps.vue`、`locales/zh-CN.ts`、`locales/en-US.ts`
  - Change: 从 toolCalls 解析 `args` JSON 中 fetch 类调用的 `url`（tool_name 含 fetch）→ `new URL().hostname` 去重计数（如 `notion.com ×2`），在步骤列表底部渲染一行"信息来源"域名 chips（i18n `chat.sourceList`="信息来源"/"Sources"）；JSON 解析失败或无 URL 时整行不渲染。
  - Verify: `pnpm type-check`；手动：竞品对比问答结束后步骤下方出现来源域名列表。
  - Depends on: none（locales 与 F3 共享，同一子代理顺序执行）

## Verification

- Commands: `cd user-web && pnpm type-check && pnpm build`（无 Java 改动，后端不重跑）；F4 由用户 psql 执行后查库验证
- Manual checks: F1/F2/F3/F5 重启前端后实测；F4 生效需重发起一次竞品对比问答（模型行为类改动，接受不保证 100% 遵循）

## Task Relationships

- **Strongly related**: F2 + F3（同 Message/index.vue）；F3 + F5（同 locales）
- **Conflict risks**: user-web 文件全部归前端子代理串行执行；F4 独立无交集

## WorkerSync

- Need worker-sync: **uncertain**（F4 为提示词行为变化、F5 为前端展示增强，均不改变入口/核心文件路径；待执行完判断，倾向 no）

## Risks

- F1 正则预处理误伤边界（跨行加粗、代码块内 `**`）——限定行内 + 渲染层处理可接受
- F4 为提示词约束，模型遵循度非 100%（并行抓取、逐处引用），属概率性改善
- F5 域名解析依赖 args 为合法 JSON（现网数据已验证均合法）
