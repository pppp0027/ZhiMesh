# Feature Routes: user-web 用户端

> 生成于 2026-09-22，由代码盘点自动汇总。每条路由 = 页面/功能 → 落点文件。查代码先查这里。
> 路径均相对 `user-web/src/`；路由表在 `router/index.ts`（hash 模式）；全部后端调用集中在 `api/index.ts`（单文件默认导出，页面里以 `api.xxx` 调用）。

## 布局
- `views/` → 页面目录：chat（Agent 对话）、knowledge-base（知识库问答）、knowledge-base-manage（知识库管理）、draw / gallery（绘图与广场）、workflow、mcp、team-manage、user（登录/激活）、exception（404/500）
- `components/common/` → 跨页共享：Setting（用户设置弹窗）、PromptStore（提示词库）、LLMSelector / ImageModelSelector、ApiKeyModal+ApiDocPanel（外部 API Key 与接入文档）、AudioRecorder（录音上传）、UserAvatar、SiderAccountBar 等；`components/custom/` 仅 GithubSite
- `router/` → `index.ts` 路由表（含 chunk 加载失败自动 reload 恢复）+ `permission.ts` 页面守卫
- `api/` → `index.ts` 汇总约 120 个 HTTP/SSE 函数（`utils/request/` axios 封装、`commonSseProcess` SSE 公共处理）
- `store/modules/` → Pinia：app / auth / chat / knowledge-base / workflow / draw / gallery / mcp / prompt / settings / user
- `hooks/` → useBasicLayout、useLanguage；`hooks/api-doc/` 按资源类型生成 OpenAPI 接入文档（useCharacterDoc 等 5 个）
- `locales/` → i18n 文案 `zh-CN.ts`、`en-US.ts`，入口 `index.ts`

## 路由表

### 会话（Agent 对话）
- **Agent 对话页** — `views/chat/index.vue` (路由 `/chat/:uuid`，根路径重定向 `/chat/default`；外壳 `views/chat/layout/Layout.vue`) → `api.sseProcess` SSE 流式对话、`fetchConversationMessages`/`fetchMessages` 拉历史、`messageDel` 删消息；对话核心状态机在 `views/chat/hooks/useChat.ts` + `store/modules/chat`
- **消息渲染与 Agent 步骤** — `views/chat/components/Message/`：`Text.vue`（markdown 渲染）、`ToolSteps.vue`（工具调用步骤折叠）、`AudioMessage.vue`（语音消息，`api.messageTextByAudio` 语音转文字）
- **答案证据与引用溯源** — `views/chat/components/AnswerEvidenceActions.vue` + `EvidenceMarkdown.vue`；溯源弹层 `RefMemory.vue`(`api.memoryEmbeddingRef`)、`RefGraph.vue`(`api.messageGraphRef`)、`RefKeyword.vue`(`api.messageKeywordRef`)、`api.knowledgeEmbeddingRef`
- **输入区** — `views/chat/InputEditor.vue`（`api.searchPrompts` 提示词补全）、`InputToolbar.vue`（`api.fileDel` 附件、`characterEdit`/`characterToggleUsingContext`/`characterToggleThinking` 角色开关）、`components/AudioRecorder.vue`（`api.fileUpload`）
- **会话侧栏** — `views/chat/layout/sider/List.vue` → 角色分组会话列表：`fetchCharacters`/`fetchConversations`/`fetchDefaultConversation`/`fetchConversationMessages`、`conversationEdit`/`conversationDelete`
- **登录门禁** — `views/chat/layout/Permission.vue` → 未登录遮罩：`api.login`/`register`/`passwordFind`

### 角色与预设
- **添加角色弹窗（预设角色/自定义双 tab）** — `views/chat/layout/sider/CreateConv.vue` → 预设 tab：`api.searchPresetCharacters` 按 11 类 type 分组、`api.listCharacterPresetRels` 标"已使用"、`api.characterAddByPreset` 一键复制预设为角色（MCP/系统知识以标签展示）；自定义 tab 内嵌 `EditConvDetail`
- **角色编辑** — `views/chat/components/Header/EditConvDetail.vue` → `api.characterAdd`/`characterEdit`/`characterDel`；入口 `Header/EditConv.vue`（`api.userMcpList` 供绑定 MCP）
- **会话知识库选择** — `views/chat/ConvKnowledgeSelector.vue` → `api.knowledgeBaseSearchMine` 列表 + `characterEdit` 把 kbUuid 绑到角色

### 知识库问答（QA）
- **知识库问答页** — `views/knowledge-base/index.vue` (路由 `/qa/:kbUuid`，布局 `views/knowledge-base/layout/`) → `api.knowledgeBaseQaSseAsk` SSE 问答、`knowledgeBaseQaRecordSearch/Add/Del` 问答记录；引用溯源 `knowledgeBaseEmbeddingRef` + `RefGraph.vue`(`api.knowledgeBaseGraphRef`)
- **QA 侧栏** — `views/knowledge-base/layout/sider/List.vue` → 我的/团队/公司 `knowledgeBaseSearchMine|Team|Company` + 收藏 `knowledgeBaseStarListMine`
- **QA 头部** — `views/knowledge-base/Header/KbInfo.vue` → `api.knowledgeBaseStar` 收藏、`knowledgeBaseQaRecordClear` 清空问答记录

### 知识库管理
- **知识库列表** — `views/knowledge-base-manage/index.vue` (路由 `/kb-manage`) → `knowledgeBaseSearchMine|Team`；新建/编辑 `knowledgeBaseSaveOrUpdate`（`loadRerankModels` 选重排模型）；转移 `knowledgeBaseTransfer`（个人↔团队，`teamMyLite`）；删除 `knowledgeBaseDelete`
- **知识库详情（文档管理/索引）** — `views/knowledge-base-manage/KnowledgeBaseDetail.vue` (路由 `/kb-manage/:kbUuid`) → 文件 NUpload 直传 `/api/knowledge-base/upload/{uuid}`，可勾选"上传后索引"（embedding 向量化 / graphical 图谱化 / fulltext 全文）；条目 CRUD `knowledgeBaseItemSearch|SaveOrUpdate|Delete`；触发索引 `knowledgeBaseItemsIndexing` + 轮询 `knowledgeBaseIndexingCheck`；状态列（DOING/DONE/FAIL+重试）定义在 `itemColumns.ts`；文件预览 `api.loadFileContent`
- **向量索引列表** — `views/knowledge-base-manage/ItemEmbeddingList.vue` → `api.knowledgeBaseEmbedding`（条目切片明细弹窗）
- **图谱查看** — `views/knowledge-base-manage/ItemGraph.vue` → `api.knowledgeBaseGraph`（按 maxVertexId/maxEdgeId 分页拉顶点与边）

### 工作流
- **工作流页** — `views/workflow/index.vue` (路由 `/workflow/:uuid`，布局 `views/workflow/layout/`) → 定义/运行记录双视图切换
- **画布定义** — `views/workflow/WorkflowDefine.vue` → `api.workflowUpdate` 保存 DSL；节点属性面板 `components/nodes/*Property.vue`（Agent 节点 `api.fetchCharacters` 选角色；`WfKnowledgeSelector.vue` 选知识库）；`WfDefineSiderbar.vue`/`WfDefineRightPanel.vue`
- **运行** — `views/workflow/components/RunDetail.vue` → `api.workflowRun` SSE 运行、`workflowRuntimeResume` 人工反馈续跑、`workflowRuntimeCancel` 取消
- **运行记录** — `views/workflow/WfRuntimeList.vue` → `workflowRuntimes`/`workflowRuntimeDetail`/`workflowRuntimeNodes`/`workflowRuntimeNodeDetail`/`workflowRuntimeDelete`
- **工作流侧栏与头部** — `views/workflow/layout/sider/List.vue`（`workflowSearchMine|Public`）+ `CreateWorkflow.vue`（`workflowAdd`/`workflowBaseInfoUpdate`/`workflowDel`）；`Header/pc.vue` 复制 `api.workflowCopy`

### MCP
- **MCP 页** — `views/mcp/index.vue` (路由 `/mcp`) → 介绍/配置双 tab；`UserMcpList.vue` 个人 MCP（`api.userMcpList`/`userMcpSaveOrUpdate`）；`McpInfoList.vue` 公共 MCP 广场（`api.mcpSearch`）；`ApiKeyModal`(type=mcp) 生成用户级外部 Key

### 绘图与广场
- **绘图页** — `views/draw/index.vue` (路由 `/draw`) → 供应商面板 `components/wanx|siliconflow|gpt-image/`（`api.imageGenerate`）；`components/DrawDetail.vue`（详情、评论 `fetchDrawComments`/`drawCommentAdd`、新旧翻页 `fetchNewer|Older*Draw`）；`DrawDetailFuncBar.vue`（`drawStarOrUnStar`）
- **绘图广场** — `views/gallery/index.vue` (路由 `/gallery`) → `api.fetchPublicDraws`/`fetchStarDraws`

### 团队
- **团队管理** — `views/team-manage/index.vue` (路由 `/team-manage`) → `api.teamSearchMine`；`components/TeamEditModal.vue`（`teamSaveOrUpdate`）、`components/MemberDrawer.vue`（`teamMemberList|Add|UpdateRole|Remove`、`teamLeave`）、`api.teamDelete`

### 用户与设置
- **全局登录框** — `views/user/Login.vue`（无独立路由，由 `App.vue` 全局挂载）→ `api.login`/`register`/`passwordFind`；`views/user/LoginTip.vue` 为各页未登录占位
- **邮箱激活结果** — `views/user/Active.vue` (路由 `/active`) → 读 query 的 active/msg 展示成败，倒计时 5 秒回首页
- **用户设置弹窗** — `components/common/Setting/index.vue` → `api.fetchUserConfig`；子页 `General.vue`（`userEdit`/`logout`）、`ModifyPassword.vue`（`modifyPassword`）、`Quota.vue`
- **提示词库** — `components/common/PromptStore/index.vue` → `api.searchPrompts`/`promptsSave`/`promptEdit`/`promptDel`
- **外部 API Key 与接入文档** — `components/common/ApiKeyModal.vue` → 资源级/用户级 `extApiKey*Generate|Info|Reveal`；内嵌 `ApiDocPanel.vue`（`hooks/api-doc/` 按类型出 OpenAPI 文档）
- **模型选择** — `components/common/LLMSelector.vue`（`api.loadLLMs`）；`ImageModelSelector.vue` 走 appStore 缓存

### 异常页
- **404/500** — `views/exception/404/index.vue` (路由 `/404`)、`views/exception/500/index.vue` (路由 `/500`)；未匹配路由重定向 `/404`
