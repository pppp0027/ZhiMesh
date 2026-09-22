# Feature Routes: server 后端

> 生成于 2026-09-22，由代码盘点自动汇总。每条路由 = 一个功能入口 → 落点文件。查代码先查这里。
> java 路径均相对 `server/<module>/src/main/java/com/pppp/zhimesh/`（如 `common/service/...` → zhimesh-common）。controller 仅是薄壳，业务实现基本都在 zhimesh-common 的 service/rag/workflow 包。

## 模块布局
- `zhimesh-bootstrap` → 唯一启动模块：`BootstrapApplication` + application.yml，打成运行镜像
- `zhimesh-chat` → C 端 REST 入口（controller 包 + externalapi/v1 子包）
- `zhimesh-admin` → 管理端 REST 入口（全部 `/admin/*` 前缀）
- `zhimesh-common` → 业务主体：service / rag / workflow / memory / openrouter / 定时任务 / 过滤器
- `server/pom.xml` → 父 pom，经 langchain4j-bom 统一版本；`server/db_migration/` 为 DDL 与校验脚本

## 路由表

### 会话与消息
- **SSE 聊天** — `ChatController` (`chat/controller/ChatController.java`) `POST /chat/process` → `CharacterChatService.sseAsk`：`ChatContextResolver` 解析会话/角色，四路检索器（`ZhiMeshEmbeddingStoreContentRetriever`/`DeduplicatingContentRetriever`/`GraphStoreContentRetriever`/`Bm25ContentRetriever`）+ 短/长期记忆，SSE 流式返回
- **会话管理** — `ConversationController` (`chat/controller/ConversationController.java`) `/conversation` CRUD/`default`/`{uuid}/messages` → `ConversationService`；角色历史消息回填会话由 `CharacterService`→`ConversationBackfillService` 完成
- **消息与引用溯源** — `CharacterMessageController` (`chat/controller/CharacterMessageController.java`) `/character/message` → `CharacterMessageService`；`embedding-ref`/`memory-embedding-ref`/`graph-ref`/`keyword-ref` 分别查 `CharacterMessageRefEmbeddingService`/`...RefMemoryEmbeddingService`/`...RefGraphService`/`...RefBm25Service`
- **团队** — `TeamController` (`chat/controller/TeamController.java`) `/team` → `TeamService` 团队与成员管理

### 角色系统
- **角色 CRUD** — `CharacterController` (`chat/controller/CharacterController.java`) `/character` → `CharacterService`（含 `addByPreset` 从预设建角色）
- **角色预设** — `CharacterPresetController` (`chat/controller/CharacterPresetController.java`) `/character-preset` → `CharacterPresetService`
- **预设绑定关系** — `CharacterPresetRelController` (`chat/controller/CharacterPresetRelController.java`) `/character-preset-rel` → `CharacterPresetRelService`

### 认证与用户
- **注册/登录/找回密码** — `AuthController` (`chat/controller/AuthController.java`) `auth` 前缀（register/captcha/active/login/password/forgot|reset/search-engine/list）→ `UserService`，验证码与激活码走 `AUTH_*` Redis key
- **用户信息** — `UserController` (`chat/controller/UserController.java`) `/user` → `UserService`：info/config/edit/改密/头像
- **前台系统配置** — `SysConfigController` (`chat/controller/SysConfigController.java`) `/sys/config` → `AiModelService` 返回默认模型等前台配置
- **鉴权过滤器** — `TokenFilter` (`common/filter/TokenFilter.java`) 解析 `USER_TOKEN`；`HttpServletRequestReplacedFilter` 包装可重复读请求

### 知识库与 RAG
- **知识库管理** — `KnowledgeBaseController` (`chat/controller/KnowledgeBaseController.java`) `/knowledge-base`：saveOrUpdate、`uploadDocs|upload/{uuid}`、`mine|team|company/search`、`transfer`、`indexing/{uuid}`、`item/indexing-list`、`indexing/check` → `KnowledgeBaseService`（向量/图谱/BM25 三路索引触发）
- **知识库 QA（SSE）** — `KnowledgeBaseQAController` (`chat/controller/KnowledgeBaseQAController.java`) `/knowledge-base/qa/`：`add`/`process/{qaRecordUuid}`(SSE)/`search`/`del`/`embedding-ref`/`graph-ref`/`clear` → `KnowledgeBaseQaService` 出答案，`KnowledgeBaseQaRecordReferenceService`+`KnowledgeBaseQaRefGraphService` 出引用溯源；process 由 `KB_QA_PROCESS_LOCK` 防重
- **知识点/文档条目** — `KnowledgeBaseItemController` (`chat/controller/KnowledgeBaseItemController.java`) `/knowledge-base-item` → `KnowledgeBaseItemService` + `CanonicalChunkQueryService`（共享 canonical chunk）
- **向量索引** — `KnowledgeBaseEmbeddingController` (`chat/controller/KnowledgeBaseEmbeddingController.java`) `/knowledge-base-embedding` → `IKnowledgeEmbeddingService`
- **图谱管理** — `KnowledgeBaseGraphController` (`chat/controller/KnowledgeBaseGraphController.java`) `/knowledge-base-graph` → `KnowledgeBaseGraphService`
- **知识库收藏** — `KnowledgeBaseStarController` (`chat/controller/KnowledgeBaseStarController.java`) `/knowledge-base/star` → `KnowledgeBaseStarService`
- **检索引擎内核** — `common/rag/`：`CompositeRag` 聚合 `EmbeddingRag`/`GraphRag`/`rag.bm25.Bm25Rag`；`rag.intent.IntentRoutingService` 决定检索路由；`rag.profile.KnowledgeRouteProfileCoordinator` 维护有界路由画像
- **图谱化 ingest 管线（2026-09-22 B 档稳定性改造）** — `KnowledgeBaseItemService.indexingGraph`（graphConcurrency 信号量 + DOING/DONE/FAIL 状态机）→ `GraphRag.extractSegment` 质量自愈循环（`graphExtractionQualityMaxAttempts` 默认 3 轮：首轮抽取、后续轮携带累积 issues 修复；坏 JSON 与质检不过均进下一轮，轮数耗尽 fail-closed 抛出；`recordCost` 对缺失 tokenUsage 记 0 防 NPE）→ `GraphExtractionRequestExecutor`（HTTP 级重试只管超时/限流/5xx，与质量层重试不叠加）→ `GraphExtractionTaskRunner` fail-fast（任一段失败取消整文档）→ AGE 串行写入；失败路径 `cleanupDocument` 再失败时写 `KB_GRAPH_CLEANUP_RETRY_SIGNAL`，消费侧核对条目状态——仅 FAIL 才清理、DONE/NONE 丢弃陈旧标记、DOING 推迟、条目已删除仍清残留

### 模型与平台
- **模型列表/详情** — `ModelController` (`chat/controller/ModelController.java`) `/model` → `ModelPlatformService`+`AiModelService`+`ModelHealthService`（健康探测）
- **提示词模板** — `PromptController` (`chat/controller/PromptController.java`) `/prompt` → `PromptService`

### MCP
- **MCP 服务端点** — `McpController` (`chat/controller/McpController.java`) `/mcp` → `McpService`
- **用户 MCP** — `UserMcpController` (`chat/controller/UserMcpController.java`) `/user/mcp` → `UserMcpService`（运行时配置校验走 `McpRuntimeConfigValidator`）

### 绘画社区与文件
- **绘图** — `DrawController` (`chat/controller/DrawController.java`) `/draw` → `DrawService`+`FileService`（`USER_DRAWING` 并发锁）
- **绘图收藏** — `DrawStarController` (`chat/controller/DrawStarController.java`) `/draw/star` → `DrawService`
- **绘图评论** — `DrawCommentController` (`chat/controller/DrawCommentController.java`) `/draw/comment` → `DrawCommentService`（`DRAW_COMMENT_LIMIT_KEY` 防并发提交）
- **文件上传/下载** — `FileController` (`chat/controller/FileController.java`) 无类级前缀：`/file/upload`、`/image/upload`、`/file/{uuid}`、`/my-thumbnail/{uuid}`、`/file/del/{uuid}` → `FileService`

### 工作流
- **工作流定义与运行** — `WorkflowController` (`chat/controller/WorkflowController.java`) `/workflow`：CRUD/copy/set-public/enable/`run/{wfUuid}`(SSE)/mine+public search/operators → `WorkflowStarter`（编译执行）+ `WorkflowService` + `WorkflowComponentService`
- **运行实例** — `WorkflowRuntimeController` (`chat/controller/WorkflowRuntimeController.java`) `/workflow/runtime`：`resume`/page/单实例/`nodes/{uuid}`/cancel/clear/del → `WorkflowRuntimeService`
- **引擎内核** — `common/workflow/`：`WorkflowEngine`+`WfNodeFactory`+node/edge/def 子包；`WorkflowRuntimeExecutionRegistry` 登记活跃执行

### 外部 API（/ext/v1，API key 鉴权）
- **API key 管理** — `ExtApiKeyController` (`chat/controller/ExtApiKeyController.java`) `/external-api-key` → `ExtApiService`
- **鉴权** — `ExtApiAuthFilter` (`common/filter/ExtApiAuthFilter.java`) 拦截 `/ext/v1/` → `ExtApiService.validateApiKey|validateUserApiKey`
- **ext 角色对话** — `ExtCharacterController` (`chat/controller/externalapi/v1/ExtCharacterController.java`) `/ext/v1/character` → `CharacterChatService`
- **ext 知识库** — `ExtKnowledgeBaseController` (`chat/controller/externalapi/v1/ExtKnowledgeBaseController.java`) `/ext/v1/knowledge` → `KnowledgeBaseService`+`KnowledgeBaseQaService`
- **ext 绘图** — `ExtDrawController` (`chat/controller/externalapi/v1/ExtDrawController.java`) `/ext/v1/draw` → `DrawService`
- **ext MCP** — `ExtMcpController` (`chat/controller/externalapi/v1/ExtMcpController.java`) `/ext/v1/mcp` → `UserMcpService`+`McpService`
- **ext 工作流** — `ExtWorkflowController` (`chat/controller/externalapi/v1/ExtWorkflowController.java`) `/ext/v1/workflow` → `WorkflowStarter`

### 管理端（zhimesh-admin，/admin/*）
- **用户管理** — `AdminUserController` (`admin/controller/AdminUserController.java`) `/admin/user` → `UserService`
- **系统配置** — `SystemConfigController` (`admin/controller/SystemConfigController.java`) `/admin/sys-config` → `SysConfigService`
- **运营统计** — `StatisticController` (`admin/controller/StatisticController.java`) `/admin/statistic` → `StatisticService`（读 `statistic:*` Redis hash）
- **模型管理** — `AdminModelController` (`admin/controller/AdminModelController.java`) `/admin/model` → `AiModelService`
- **模型平台** — `ModelPlatformController` (`admin/controller/ModelPlatformController.java`) `/admin/model-platform/` → `ModelPlatformService`
- **OpenRouter 同步** — `AdminOpenRouterModelSyncController` (`admin/controller/AdminOpenRouterModelSyncController.java`) `/admin/openrouter-model-sync` → `OpenRouterModelSyncService`/`OpenRouterSyncRunService`/`OpenRouterModelStateService`（手动触发与运行历史）
- **角色/预设管理** — `AdminCharacterController` (`admin/controller/AdminCharacterController.java`) `/admin/character`、`AdminCharacterPresetController` (`admin/controller/AdminCharacterPresetController.java`) `/admin/character-preset` → `CharacterService`/`CharacterPresetService`
- **知识库管理** — `AdminKbController` (`admin/controller/AdminKbController.java`) `/admin/kb` → `KnowledgeBaseService`+`KnowledgeBaseItemService`+`IKnowledgeEmbeddingService`+`KnowledgeBaseGraphService`
- **MCP 管理** — `AdminMcpController` (`admin/controller/AdminMcpController.java`) `/admin/mcp` → `McpService`
- **工作流管理** — `AdminWorkflowController` (`admin/controller/AdminWorkflowController.java`) `/admin/workflow` → `WorkflowService`；`AdminWfComponentController` (`admin/controller/AdminWfComponentController.java`) `/admin/workflow/component` → `WorkflowComponentService`
- **RAG 评估** — `AdminRagEvaluationController` (`admin/controller/AdminRagEvaluationController.java`) `/admin/rag-evaluation` → `KnowledgeBaseService`（评估任务下发/查询，配套 `rag/RagEvaluationResultMapper`）

### 后台任务与恢复（全部在 zhimesh-common）
- **索引恢复** — `IndexingRecoveryJob` (`common/rag/IndexingRecoveryJob.java`)：@Scheduled 60s 找回 abandoned 索引（embedding/fulltext 各自 DoingTimeout 配置，默认 30 分钟）+ 消费 `KB_GRAPH_CLEANUP_RETRY_SIGNAL` 重试清理（见图谱化管线条目）；@ApplicationReadyEvent 把上一进程遗留的 graph 索引即刻置失败（`KnowledgeBaseItemService.failGraphIndexingStartedBefore`，严格早于启动时刻才转 FAIL，消除重启后最长 60 分钟盲区）
- **路由画像协调** — `KnowledgeRouteProfileCoordinator` (`common/rag/profile/KnowledgeRouteProfileCoordinator.java`)：3 个 @Scheduled —— processSignals（消费 rebuild 信号，防抖 5s）、recoverIncompleteBuilds（60s）、refreshActiveCaches（每小时从 ACTIVE release 刷 Redis）
- **工作流恢复** — `WorkflowRuntimeRecoveryJob` (`common/workflow/WorkflowRuntimeRecoveryJob.java`) @Scheduled 30s：对照 `WorkflowRuntimeExecutionRegistry` 快照，把僵死 run/node 落终态
- **OpenRouter 模型同步** — `OpenRouterModelSyncJob` (`common/openrouter/OpenRouterModelSyncJob.java`)：cron 每日 04:00 全量同步 + 0/9/14/19 点健康检查 + 启动时若数据过期补跑（后台线程池执行）。429 语义（2026-09-22 修复）：账户级限流中止整轮 + 60 分钟冷却；`limit_source: upstream_provider_shared_pool`（上游共享免费池按模型饱和）只废掉该次探测、继续下一模型，否则 discovery 永远到不了、清单冻结
- **知识库统计** — `Jobs` (`common/base/Jobs.java`) @Scheduled 60s → `KnowledgeBaseService.updateStatistic()`，消费 `KB_STATISTIC_RECALCULATE_SIGNAL`
- **模型上下文初始化** — `AiModelInitializer` (`common/service/`)：按 DB 配置构建 LLM/TTS/ASR/图像模型上下文与搜索引擎上下文（`AiModelService` 调用）；`Initializer` 以 @PostConstruct 做系统预热

### Redis 键速查（`common/cosntant/RedisKeyConstant.java`，注意包名拼写就是 cosntant）
- **信号（Set，生产者 → 消费者）**：`KB_STATISTIC_RECALCULATE_SIGNAL`（`KnowledgeBaseService`/`KnowledgeBaseItemService` → `Jobs` 每分钟重算）；`KB_GRAPH_CLEANUP_RETRY_SIGNAL`（`KnowledgeBaseItemService` 图谱化失败标记 → 重试清理）；`KB_ROUTE_PROFILE_REBUILD_SIGNAL`（`KnowledgeRouteProfileCoordinator.requestRebuild` → 同类 processSignals）
- **跨实例锁**：`KB_ROUTE_PROFILE_BUILD_LOCK:{kbUuid}`（画像构建）；`CHARACTER_MEMORY_UPDATE_LOCK:{characterId}`（角色语义记忆合并，可续期）；`KB_QA_PROCESS_LOCK:{qaRecordUuid}`（QA 记录防重处理）
- **并发/限流计数**：`USER_ACTIVE_SSE_COUNT`（SSE 并发数，替代已废弃 `USER_ASKING`）、`USER_DRAWING`、`USER_REQUEST_TEXT_TIMES`/`USER_REQUEST_IMAGE_TIMES`、`AQ_ASK_TIMES`（每日提问量）、`QA_ITEM_CREATE_LIMIT`、`USER_INDEXING`、`DRAW_COMMENT_LIMIT_KEY`
- **认证/会话**：`USER_TOKEN`、`GUEST_UUID`、`USER_INFO`、`LOGIN_FAIL_COUNT`、`AUTH_ACTIVE_CODE`/`AUTH_*_CAPTCHA_ID`、`FIND_MY_PASSWORD`
- **统计与缓存**：`STATISTIC`/`STATISTIC_USER`/`STATISTIC_KNOWLEDGE_BASE`/`STATISTIC_TOKEN_COST`/`STATISTIC_CHARACTER`/`STATISTIC_IMAGE_COST`（hash）、`TOKEN_USAGE_KEY`、`WORKFLOW_COMPONENTS` 等 `workflow:*` 组件缓存

### 配置骨架（`common/config/ZhiMeshProperties.java`，前缀 `zhimesh.*`）
- `Auth` 演示注册开关；`Proxy` 出网代理；`Datasource`+`Neo4j` 图库连接；`Encrypt` AES key
- `Indexing` 共享 canonical chunk 索引；`GraphStore` 图存储（ApacheAge）；`Retrieval`（含 `Bm25` 子组）检索并发与 BM25 总开关
- `IntentRouting` 意图路由开关；`KnowledgeScopeGate` 知识范围门控（含 rebuild 防抖参数）
- `Memory` 记忆参数；`Conversation` 会话（legacy rollout flag）；`Agent` 单请求最大递归工具调用轮数
- `AsyncExecution`/`Executor` chat 等线程池；`OpenRouterSync` 模型同步与健康检查开关
