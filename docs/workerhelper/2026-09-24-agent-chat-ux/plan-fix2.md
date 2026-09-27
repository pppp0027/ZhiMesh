# Fix Plan 2: agent-chat-ux 验收反馈修复（2026-09-24 第三轮）

> 输入：用户实测 2 个新问题。根因已查库确认：
> ① "我先按……来分析"不在正文（库里 1938 正文开头即 `**假设：**`），是实时思考流经「深度思考」面板显示——F2 收起时机为回答结束，回答流式期间面板仍展开（另有用户浏览器旧 bundle 可能，交付时提醒强刷）；
> ② 来源标注现为独立成行的"来源：CapCut 官网，抓取于 2026年。"，用户提案改为脚注形态：正文句尾 `[^n]` 可点击标记 → 跳转答案末尾"参考来源"条目（站点+抓取日期+原文链接）。

## Tasks

- [x] H1: 思考面板收起时机提前到首 token
  - Files: `user-web/src/views/chat/components/Message/index.vue`
  - Change: 新增 computed `phase`：`props.thinking && !props.text ? 'thinking' : 'answer'`；watch(phase, immediate) 切换 expandedNames（thinking→['thinking']，answer→['finalAnswer']）。替换现有 `watch(() => props.thinking, …)`。watch computed 只在相位跃迁时触发：正文首字出现即收起思考面板；流式期间用户手动展开思考不被后续 chunk 打断；挂起中途挂载（thinking=true 且 text 非空）直接进 answer 相位。
  - Verify: `pnpm type-check`；逻辑推演四态：思考中无正文/思考中首字到达/无思考直出正文/历史回放。
  - Depends on: none

- [x] H2: 脚注式来源标注（前端渲染）
  - Files: `user-web/package.json`、`user-web/src/views/chat/components/Message/Text.vue`
  - Change:
    1. `pnpm add markdown-it-footnote`（markdown-it@13 插件；若 peer 冲突或渲染实测失败，回退方案=渲染后 HTML 正则替换 `[^n]` 为 `<sup class="msg-cite">` + 容器事件委托滚动，不升级 markdown-it）
    2. Text.vue 的 mdi 实例 `mdi.use(markdownItFootnote)`；新增脚注区样式：`.footnotes` 区块字号 muted 小字、上边框分隔；行内 `sup.footnote-ref` 小标圆角可点击（继承 a 链接行为，点击滚动到对应脚注条目，插件自带锚点与回链）
  - Verify: `pnpm type-check`；node 实测渲染：`句末[^1]\n\n[^1]: [CapCut 官网](https://example.com)（抓取于 2026-09）` 产出 sup 标记与脚注区块、锚点 href 正确。
  - Depends on: none

- [x] H3: fetch remark 引用格式改为脚注约定（SQL amendment，改后由主线程经 docker psql 执行）
  - Files: `server/db_migration/023_seed_useful_mcp_presets.sql`
  - Change: 追加新 amendment：WHERE `title='网页抓取'` AND remark **逐字等于当前最终文案**（含"……括注来源站点与抓取日期（如：据 notion.com 定价页，2026-09）。"结尾那段），SET 为把最后一句引用要求替换为——"回答中引用抓取到的事实时，在该句句末用脚注标注：正文相应位置写 [^n]，并在回答末尾用脚注定义逐条列出来源（格式：[^n]: [站点名](抓取页URL)（抓取于 年-月））；编号与正文一一对应、按首次出现顺序编号；没有对应抓取页面的表述不得加脚注。"；NOT EXISTS INSERT 分支（:22）同步为同最终文案。追加语义注释（英文，对齐文件风格）。
  - Verify: 主线程 docker psql 执行预期 `UPDATE 1`；MCP 只读通道复查 remark 终态逐字匹配；单独重跑该 UPDATE 应 0 行。
  - Depends on: none

## Verification

- Commands: `cd user-web && pnpm type-check && pnpm build`；H3 经 docker psql 执行 + MCP 复查
- Manual checks: 用户强刷浏览器（Ctrl+F5）后重发起研究型问答：思考面板在正文出现瞬间收起；正文句尾 [1] 标记可点击跳到文末参考来源；来源条目含站点/日期/可点链接。

## Task Relationships

- H1、H2 同在 Message/ 组件但不同文件（index.vue / Text.vue），无冲突，同一前端子代理顺序执行；H3 独立（SQL 文件）。

## WorkerSync

- Need worker-sync: **no**（无入口/核心路径/测试入口变化）

## Risks

- markdown-it-footnote 与 markdown-it@13 兼容性未预验——已备回退方案（H2.1）
- 脚注格式是提示词约定，模型遵循度非 100%（漏标/编号错位可能），属概率性改善
- 旧消息（独立行"来源：…"格式）不会回溯变化，属预期
