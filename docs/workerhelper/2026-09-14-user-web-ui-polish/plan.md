# Execution Plan: user-web 三组页面 UI/UX 统一打磨

## Summary

按 design.md 十条统一规范，修复 chat / qa / kb-manage 三组页面约 50 项审计问题 + 清空历史数据损失 bug（前后端）。
locale 键由主线程先行添加（避免并行冲突），四个实现子代理并行（文件集互斥），review 子代理复审，主线程终验。

## 文件所有权（并行安全的前提，子代理不得越界编辑）

- `locales/{zh-CN,en-US}.ts`：**仅主线程**（T0）
- `api/index.ts`、`typings/knowledge-base.d.ts`、`store/modules/knowledge-base/index.ts`：**仅 T2**
- `typings/chat.d.ts`：**仅 T1**
- `views/chat/**`：仅 T1；`views/knowledge-base/**`：仅 T2；`views/knowledge-base-manage/**`：仅 T3
- `server/**`：仅 T4

## Tasks

- [x] T0（主线程）：locale 新增键
  - Files: `user-web/src/locales/zh-CN.ts`、`en-US.ts`
  - Change: 按下方键清单在对应 section 追加（zh/en 同键同序）；子代理只消费不编辑
- [x] T1: chat 组修复（子代理 A）
  - 高：Permission.vue 登录弹窗三 tab 输入框尺寸统一（删内联 height:40px）；ToolSteps.vue 全部文案走 t() + emoji→SvgIcon；三处证据弹窗加载态统一（index.vue:871/RefMemory:57/RefKeyword:37 的 loading-loop+text-green-800→NSpin 或主题色 spinner）；RefGraph.vue:182 NButton 空态→居中 muted 文本；sider List.vue 首屏 loading 不闪"暂无角色" + getOrCreateDefaultConversation in-flight 去重；InputToolbar MCP 弹窗与 ConvKnowledgeSelector 保存按钮 :loading/:disabled + submitting 守卫
  - 中：index.vue:727 NTabs -30px 负 margin 魔数→统一 pane padding 且多答案与单答案左缘对齐；AudioMessage 与文本消息头像形状/尺寸统一；ToolSteps 移到 message-content 层级宽度随气泡；EditConvDetail 角色删除→openDeleteDialog；硬编码色→--zhimesh-*（Header/index.vue:64、InputToolbar:532-539、Message/index.vue:319、RefMemory:62、RefGraph:176）；弹窗宽度统一三档（index.vue:868,885,892,899、List.vue:283、InputToolbar:380,385）；ConvKnowledgeSelector 交互外壳统一为弹窗（EditConvDetail 内联展开收编）；Message meta 行 📥📤⏱→文本/SvgIcon；InputEditor.vue:445 绑定 placeholder/placeholderMobile；移动端双击回顶修复（Header/index.vue:29-33，querySelector id 不存在）；index.vue:474 删除答案后同步选最新（去 3s setTimeout）、:421 noMore 降为默认样式；停止请求按钮视觉强化（非 tiny）+ 重新生成 isChatting 时 disabled；CreateConv.vue 预设角色 loading/空态
  - 低：InputToolbar:393 双重 mt-4；Permission.vue:93 ms.success('success')→i18n
  - Verify: 先逐条核实审计行号属实再修；`pnpm type-check` 零错误
- [x] T2: qa 组修复（子代理 B）
  - 高：清空历史前端侧——api.knowledgeBaseQaRecordClear(kbUuid) 传路径参数、KbInfo 成功后仅清当前库、确认迁 openDeleteDialog（与 T4 契约对齐：POST /knowledge-base/qa/clear/{kbUuid}）；loaddingKbList 落地视图——SubList 三 Tab 与主区 loading 期不闪"暂无数据"（NSpin）；currKbUuid==='default'（登录但无库）主区给"去新建知识库"引导 + 禁用 composer
  - 中：KbInfo NTag 尺寸/round 统一 + 去 #ff000000 透明 hack + 修 #ff0000000 非法色值；主区空态统一 resource-list-empty/--zhimesh-text-muted；未登录侧栏整体替换登录引导（不渲染三 Tab 空壳）；SubList 滚动恢复 localStorage key 加 ownerType 前缀（display-directive='if' 下 unmount 删 key 使其失效——修到可用或干脆移除该功能，实现时定）；sider 顶部加"新建知识库"主按钮（跳 kb-manage 新建），footer 团队管理弱化为次级；消息区容器与 composer 宽度对齐（max-w-screen-xl→min(980px,100%)）
  - 低：List.vue privateListLoaded→kbListLoaded 改名+报错文案；console.log 删除 10 处（index.vue:41,106,140,198,312,411,419、sider/index.vue:57、RefGraph:93,156）；List.vue:164 NTabPane 无效 size prop 删除；SubList 图标统一 24 网格；KbInfo STAFF 档拼 companyScopeStaff；pc.vue:33-41 两项统一先登录校验；KbInfo Tooltip 只渲染当前模式说明+去悬空 <br>；api includeVisible 死参查证后处理（若确无调用方传入则删参并确认后端默认行为不受影响，拿不准就保留并报告）
  - Verify: `pnpm type-check`；清空历史与 T4 联调口径一致
- [x] T3: kb-manage 组修复（子代理 C）
  - 高：index.vue 操作列重构——列宽 200+、次级操作（API/转入团队/转回个人/删除）NDropdown"更多"收纳、主按钮"查看"常驻、无权限项隐藏不 disabled 占位；itemColumns.ts 索入/图谱/全文三列改单行 NTag（对齐 admin-web statusTagType 映射：处理中 info/成功 success/失败 error）+ 时间戳移 tooltip、FAIL 行内"重试"入口（带默认 indexType）；上传流程重做——去 3s setTimeout 盲关、监听全部文件 finish/error 后关窗刷新、失败文件保留可重传、关弹窗重置 fileList/fileListLength（修重复上传）；详情面包屑按 ownerType 动态（团队库不再叫"我的知识库"）+ 返回列表不丢分区（scope 入 query 或等效方案）
  - 中：归属列三档统一 NTag（PERSONAL 不再裸文本，列表+编辑弹窗两处）；itemColumns 条目操作列横排统一；createTime/updateTime 两列合并为"更新时间"（或压宽）；弹窗宽度统一 width:90%+max-width、上传弹窗去 min-height:700px；ms.warning('indexing') 等硬编码→t()（common.indexing 已有）；console.log 删除（KnowledgeBaseDetail:22,136,160,324,364、ItemGraph:29,58,103）；"转回个人"改普通确认弹窗（复用红色删除弹窗观感错误）；删条目确认带条目名插值（新增键 deleteItemConfirm）；团队分区新建但归属选"个人"时保存后跳正确分区（或提交前提示归属不一致）；条目编辑弹窗补"取消"按钮 + label 结构与库编辑弹窗统一 + title 必填星号
  - 低：pageSize 统一 20；index.vue:282 public 注释残影更新（isSystem 防御逻辑保留，仅改注释说明）；搜索输入框与按钮加 gap-2；"严格模式"是/否→NTag
  - Verify: `pnpm type-check`
- [x] T4: 后端清空历史按库清除（子代理 D）
  - Files: `server/zhimesh-chat/.../KnowledgeBaseQAController.java`、`server/zhimesh-common/.../KnowledgeBaseQaService.java`（+ 对应测试）
  - Change: `/clear` → `/clear/{kbUuid}`；Controller checkReadPrivilege + getOrThrow 先例镜像 add；Service 清除条件 userId+kbId；核实实体字段名；补/改 Service 单测（MP 单测模式：MessageSourceStub、lambdaQuery entityClass 预置，参照同模块现有测试）
  - Verify: `mvn -pl zhimesh-common -am test`（或含 zhimesh-chat 的 scoped 组合）全绿
- [x] T5: review 子代理复审（对照 design 十条规范 + 本 plan 逐项核销）→ 主线程 `pnpm type-check` + `pnpm build-only` + scoped mvn test 全部新鲜复跑 → 修复复审确认问题 → 勾选本文件
  - 复审结论：无 Critical，4 条 Important 已由主线程修复（itemColumns 硬编码主色→主题变量、qa 两个 80% 弹窗落 640/860 档、ItemEmbeddingList 60%→90%/700、KnowledgeBaseDetail 图标 32→24 网格）；chat 目录 25 处 console.log/info 清理为复审阶段补的规范缺口（2 处转 console.error、1 处恢复为 console.warn 修复单行 else 悬空）

## T0 locale 键清单（zh / en 同步添加）

chat 段新增（紧随现有工具相关键，插在 `toolLabel` 附近）：

```
toolCallDetails: '工具调用详情' / 'Tool call details'
collapseToolCalls: '收起工具调用详情' / 'Collapse tool call details'
toolCallCount: '次工具调用' / 'tool calls'
toolCallArgs: '参数' / 'Arguments'
toolCallResult: '结果' / 'Result'
toolCallDuration: '耗时' / 'Duration'
operateSuccess: '操作成功' / 'Success'（放 common 段，Permission 复用）
```

knowledgeBase 段新增（插在 `deleteKbConfirm` 附近）：

```
deleteItemConfirm: '删除后数据无法恢复，确定要删除知识项【 {title} 】吗?' / 'This cannot be undone. Delete knowledge item "{title}"?'
createKb: '新建知识库' / 'New knowledge base'
emptyNoKbTitle: '还没有知识库' / 'No knowledge bases yet'
emptyNoKbDesc: '先创建一个知识库，上传制度文档后即可开始问答' / 'Create a knowledge base and upload documents to start asking'
goCreateKb: '去新建知识库' / 'Create one now'
clearKbHistoryConfirm: '将清除该知识库的全部问答记录，确定继续吗?' / 'This clears all Q&A history of this knowledge base. Continue?'
retryIndex: '重试' / 'Retry'（若与现有重复则复用，T0 落键时核对）
```

## Verification

- `pnpm type-check`、`pnpm build-only`（主线程终验，基线零错误）
- `mvn -pl zhimesh-common -am test`（T4 子代理跑 + 主线程复核输出）
- review 子代理逐项核销 + 主线程抽查 diff
- 用户 `pnpm dev` 人工走查（聊天发消息/工具步骤/证据弹窗、qa 三 Tab 切换/清空历史/无库引导、kb-manage 上传/索引状态/转移）

## Task Relationships

- Independent: T1 / T2 / T3 / T4（文件所有权互斥，可完全并行）
- Depends on: T1–T4 均依赖 T0（locale 键先行）；T5 依赖 T1–T4 全部完成
- Conflict risks: locales 由主线程独占；api/index.ts、typings、store 归属单一子代理；审计行号偏差由各子代理"先核实再修"兜底

## WorkerSync

- Need worker-sync: no（视图层打磨 + 单端点签名修正，无路由结构/功能入口变化）

## Risks

- 并行子代理误触他人文件 → plan 明示所有权，review 阶段核对 diff 文件清单
- 审计与现状偏差 → 子代理核实后修，不属实项跳过并报告
- 移动端/暗色主题细节无自动化覆盖 → 依赖人工走查清单兜底
