# Design: user-web 三组页面 UI/UX 统一打磨（chat / qa / kb-manage）

## Goal

修复三组页面（角色对话、知识库问答、知识库管理）的对齐失调、范式不统一、交互逻辑缺陷，
使前端"干净整洁、风格一致"。**保持现有 Naive UI + 玻璃拟态视觉语言，只补齐统一，不重做设计。**

依据：三份并行只读审计（chat 21 条 / qa 19 条 / kb-manage 20 条），归并为 6 个主题 + 1 个数据损失级 bug。

## Scope

- In: `user-web/src/views/chat/**`、`user-web/src/views/knowledge-base/**`、
  `user-web/src/views/knowledge-base-manage/**`；`api/index.ts` 与 `typings/{chat,knowledge-base}.d.ts` 的跟随改动；
  `locales/{zh-CN,en-US}.ts` 新增键（主线程独占编辑）；
  后端清空历史 bug：`KnowledgeBaseQAController` + `KnowledgeBaseQaService` 按 kbUuid 清除 + 测试。
- Out: draw / gallery / workflow / mcp / team-manage 页面；admin-web；视觉重设计；不改变知识库归属改造的功能语义（只收尾毛边）；不动用户未提交改动的意图。

## 统一规范（钉死，所有修复在此词汇表内进行）

1. **加载态**：列表/主区用 `NSpin`；按钮内用 `NButton :loading`；禁止 `text-green-800` 类硬编码 spinner。
2. **空态**：侧栏列表空态复用现有 `resource-list-empty` 结构；弹窗/主区空态用居中 muted 文本（`--zhimesh-text-muted`）；禁止用 NButton 当静态标签。
3. **破坏性确认**：一律项目已有 `openDeleteDialog`（红色 dialog）；现有 NPopconfirm 场景迁移过去（消息级轻量删除已走 dialog 的保持不变）。
4. **弹窗宽度**统一三档：小 `min(480px, 92vw)`（确认/小表单）、中 `min(640px, 92vw)`（表单/证据）、大 `min(860px, 92vw)`（选择器/图谱）；kb-manage 侧统一为 `width:90%; max-width:{480|640|860}px`，上传弹窗去固定 `min-height`。
5. **颜色**：一律 `--zhimesh-*` 主题变量；Tailwind 色类仅用于与主题无关的中性布局属性。
6. **图标**：一律 SvgIcon 体系（remix/clarity 线性），替换全部 emoji 图标（🔧⚠✓✗↳⏱📥📤）。
7. **i18n**：用户可见文案一律 `t()`；新增键见 T0 清单；**子代理不得编辑 locales 文件**，缺键时用最近的现有键并在报告中说明。
8. **防重入**：所有写操作 submitting 守卫 + 按钮 `:loading`/`:disabled`。
9. `console.log/info` 全删（约 15 处）。
10. **图标网格**：列表图标统一 24 网格（Building24Regular / PeopleTeam24Regular / Person24Regular）。

## 后端契约（清空历史 bug，前后端一起修）

- 现状：`POST /knowledge-base/qa/clear` → `clearByCurrentUser()` 删当前用户**全部**库的问答记录；KbInfo 弹窗文案只指当前库 → 数据损失级 bug。
- 改为：`POST /knowledge-base/qa/clear/{kbUuid}`，Controller 镜像 `add` 的先例：`checkReadPrivilege(kbUuid)` + `getOrThrow(kbUuid)` 后调 `clearByCurrentUser(knowledgeBase)`；
  Service 删除条件 = `userId = 当前用户 AND kbId = 该库 id`（实体字段名以实际代码为准，实现时核实）。
- 前端：`api.knowledgeBaseQaRecordClear(kbUuid)` 传路径参数；KbInfo 成功后仅清当前库本地记录；确认方式迁到 openDeleteDialog。
- 旧无参端点直接改签名（唯一调用方是 user-web，无兼容负担）。

## 风险

- 三组页面文件集不相交可并行；`locales/*.ts`、`api/index.ts`、`typings` 所有权划分见 plan，杜绝同文件并行写。
- 审计行号可能有细小偏差：实现子代理必须先读目标文件核实问题存在再修，不存在则跳过并报告。
- 用户工作区有大量未提交改动：只做加法/定点修复，禁止顺手重构、禁止回退用户改动。

## Test Strategy

- 前端：`pnpm type-check`（基线已确认零错误）+ `pnpm build-only`；改动后必须依旧零错误。
- 后端：`mvn -pl zhimesh-common -am test`（scoped），重点 KnowledgeBaseQaService 相关用例按仓库 MP 单测模式（MessageSourceStub、lambdaQuery entityClass 预置）。
- 视觉回归无自动化：review 子代理逐条对照修复项；用户 `pnpm dev` 人工走查演示剧本。

## WorkerHelper Impact

- Need worker-sync: no（纯视图层打磨 + 一个 Controller 签名修正；无功能入口/路由结构变化。后端 clear 端点路径变化在 swagger 自文档）
