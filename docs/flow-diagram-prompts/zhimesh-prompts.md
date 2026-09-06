# ZhiMesh（智枢）流程图生成提示词

下面 6 份提示词均已按当前源码调用关系整理。执行任一提示词时，必须调用本地 `process-flow-diagram` skill，输出单个自包含 HTML 文件，并在交付前按 skill 要求预览、检查裁切/重叠/箭头端点后再修正。

## ZM-01：完整聊天过程

### 提示词正文

请为 ZhiMesh 生成一张“从用户发送消息到 AI 回复完成并持久化”的完整流程图，输出 `outputs/zhimesh-chat-process.html`。使用深色技术风，采用 5 条横向泳道并按时间从左到右、超过 5 个步骤后换行：① 用户/Vue 前端；② Spring Controller 与 SSE；③ 聊天编排；④ RAG、记忆、MCP 与 LLM；⑤ PostgreSQL/MapDB/Redis 等持久化。图中必须同时呈现正常主链、重新生成分支、工具调用循环、异常出口和最终落库，不能只画 Controller 到 LLM 的简化链路。

按以下事实绘制主链：

1. 用户在 `user-web/src/views/chat/InputEditor.vue#createChatTask` 输入文本。前端先校验登录态、是否选择会话、是否正在请求、文本是否为空；生成临时 `questionUuid` 与 `answerUuid`，立即向 Pinia chat store 插入“用户问题 + loading 状态的空回答”。
2. `fetchChatAPIOnce` 通过 `fetchEventSource` 调用 `POST /api/chat/process`，请求包含 `prompt`、`characterUuid`、`conversationUuid`、模型平台/名称、图片 UUID；Authorization 放在请求头。前端按 `[START]`、`[STATE_CHANGED]`、`[THINKING]`、普通 chunk、`[TOOL_CALL]`、`[DONE]`、`[ERROR]` 分派事件并增量更新页面。
3. `ChatController.ask -> CharacterChatService.sseAsk` 创建 `sseUuid`/`SseEmitter`，`SseManager.checkOrComplete` 做速率与并发控制；通过后 `startSse`，再调用代理对象的 `@Async asyncCheckAndChat`，HTTP 线程立即返回 SSE。
4. `ChatContextResolver.resolve` 校验会话属于当前用户；有 `conversationUuid` 时从会话反查角色并校验角色一致，无会话 UUID 时校验用户自有角色并获取/创建默认会话。默认会话可触发旧角色级短期记忆迁移；最终短期记忆 key 为 conversation 级，得到 `ChatContext(user, character, conversation, memoryId, dualWriteLegacy)`。
5. 判定是否“重新生成”：有 `regenerateQuestionUuid` 时加载原问题，复用原 prompt 和附件，不新建问题 UUID；否则继续使用新问题。向前端发送“正在分析问题”状态。ASR 分支当前已停用，用灰色注释卡说明，不进入主链。
6. `LLMContext.getServiceOrDefault` 根据用户选择、可用性与健康降级选出实际模型，并把实际 platform/name 回写请求；仅对最终选中的收费模型做 quota 校验。失败立即 `[ERROR]` 并结束。
7. `CharacterService.filterEnableKb` 取得角色可用知识库；存在知识库时发“正在检索知识库”状态。`CharacterChatHelper.retrieve` 只对问题做一次 embedding，然后并行检索三个逻辑通道：角色语义记忆、独立情景记忆、知识库。知识库内部默认走向量 + 图谱混合检索；此处用折叠子流程引用 ZM-03，避免把检索细节全部重复展开。
8. `buildMemoryAndKnowledge` 将语义记忆整理为 Stable knowledge、情景记忆整理为 Past events、知识库内容整理为 knowledge；`PromptUtil.createPrompt` 把原问题、两类记忆、知识证据和 locale 组装为 processedPrompt。
9. `CharacterChatHelper.buildChatRequestParams` 装配 system message、conversation 短期记忆 ID、用户消息、多模态图片、角色绑定的 MCP clients、是否返回 thinking、模型能力允许时的 web search。MCP 初始化和调用用折叠子流程引用 ZM-04。
10. `AbstractLLMService.streamingChat` 先发现 MCP tools，再根据 system message、短期记忆 token window、当前 user message、图片和工具定义构造 `ChatRequest`。模型流式返回 thinking/content 时由 `SseManager` 发送事件。若模型返回工具请求，执行工具、加入 `AiMessage(tool requests)` 和 `ToolExecutionResultMessage` 后再次调用模型，最多 5 层；最终没有工具请求才结束。
11. 完成回调计算 token/耗时，并根据 retriever 实际命中设置 `isRefEmbedding`、`isRefGraph`、`isRefMemoryEmbedding`。`saveAfterAiResponse` 在事务中：新问题则插入 USER 消息，重新生成则复用原问题；插入 ASSISTANT 消息并关联 parent、conversation、模型和引用标志；首轮会话自动用问题前 100 字更新标题；touch 会话时间；异步保存 LLM 调用记录；写向量/图谱/记忆引用表；累计用户当日 token。
12. 若角色开启上下文，`ShortTermMemoryWindow.append` 把最终 AI 消息写入 MapDB conversation 级窗口，默认会话可双写旧 key。随后异步提取长期记忆；当前模型不支持 JSON 时尝试免费可用模型降级，找不到则跳过。
13. `SseManager.sendComplete` 返回带 question/answer meta、音频信息（当前为空）和 `conversationUuid` 的 `[DONE]`。前端用服务端 UUID/引用标志/用量覆盖临时消息、停止 loading，并在首轮同步更新会话标题。

必须画出这些决策菱形：前端请求可发送？SSE 限流通过？会话/角色归属有效？是否重新生成？最终模型额度可用？是否有关联知识库？模型是否请求工具？是否开启短期记忆？长期记忆模型是否支持 JSON？任一异常均进入 `[ERROR] -> SSE complete -> 前端展示系统提示`。用户 AbortController 中止只画成客户端终止连接，不要误画成服务端数据库回滚。

底部信息卡列出：输入对象 `AskReq`；核心状态 `ChatContext/SseAskParam/ChatModelRequest`；SSE 事件；落库对象 `character_message/conversation/llm_call_record/引用表`；MapDB 短期记忆与向量长期记忆。明确标注“ASR/TTS 当前停用”。

源码依据：`InputEditor.vue`、`user-web/src/api/index.ts`、`ChatController`、`CharacterChatService`、`ChatContextResolver`、`CharacterChatHelper`、`AbstractLLMService`、`ShortTermMemoryWindow`、`LongTermMemoryService`。

## ZM-02：RAG 索引建立

### 提示词正文

请为 ZhiMesh 生成一张“知识库文档从上传到向量索引与知识图谱索引完成”的流程图，输出 `outputs/zhimesh-rag-indexing.html`。采用 4 条泳道：① 用户/API；② 文档与索引编排；③ 向量分支；④ 图谱分支与存储。主流程纵向展开，在“选择索引类型”处分成左右两条可并行分支，底部汇合到状态/计数清理。必须表现上传解析与索引是两个阶段，也要表现批量上传只启动一次批量索引，避免把每个文件画成互相抢 Redis 锁的任务。

绘制以下入口与归一化：

- `POST /knowledge-base/uploadDocs/{uuid}`：批量文件；逐文件先保存、解析、创建 item，收集成功的 item UUID，最后仅调用一次 `indexItems`。
- `POST /knowledge-base/upload/{uuid}`：单文件，可选择上传后索引。
- `POST /knowledge-base/indexing/{uuid}`：遍历整个知识库 item，逐项异步索引。
- `POST /knowledge-base/item/indexing-list`：JSON 或兼容 form 批量索引指定 item。
- 用户工作区入口必须先通过知识库写权限；`indexAfterUpload=true` 且未显式提供类型时，默认补成 `embedding`。类型只接受事实上的 `embedding` 与 `graphical` 两条分支。

上传解析阶段按顺序画：`FileService.saveFile -> FileOperatorContext.loadDocument -> 是否支持且文本非空 -> 去除 \u0000 -> 创建 KnowledgeBaseItem(uuid,kbUuid,sourceFileId,title,brief,remark)`。无法解析的文件保留文件记录但不创建 item，批量结果标记 `UNSUPPORTED_OR_EMPTY`；异常标记 `UPLOAD_OR_PARSE_FAILED`。

索引调度阶段要区分两条入口：指定 item/上传后索引走 `KnowledgeBaseService.indexItems`，整个知识库入口由 `KnowledgeBaseService.indexing` 通过 `BizPager.oneByOneWithAnchor` 分页遍历。两条入口统一调用 `KnowledgeBaseItemService.submitIndexTask`：在任务提交前先增加 Redis `USER_INDEXING` 计数，再调用代理对象 `@Async asyncIndex`；任务结束时一定进入 finally，写 `KB_STATISTIC_RECALCULATE_SIGNAL`，计数 -1，归零后删除 key。画像重建使用独立的批次提交哨兵，只有提交结束且全部异步任务完成时才投递 `kbUuid|Generation` 信号，后台会再次核对 Generation 并丢弃旧批次。`/indexing/check` 通过用户计数 key 是否存在判断完成。

向量分支必须展开：

1. 条件：类型包含 `embedding` 且 item 状态不是 DOING。
2. 创建带 `KB_UUID`、`KB_ITEM_UUID` metadata 的 Document；先删除该 item 的旧向量。
3. 获取 `embeddingIngestSemaphore`；item 状态/时间更新为 DOING/started。
4. `EmbeddingRag.ingest -> DocumentSplitterFactory` 根据 split strategy、max segment size、overlap、custom separator、token estimator 切块。
5. `EmbeddingStoreIngestor` 调 embedding model 生成向量，并写当前配置的 `kbEmbeddingStore`：PgVector 表（表名带模型维度后缀）或 Neo4j vector index。
6. 成功更新 DONE/completed；异常更新 FAIL；finally 释放 semaphore。

图谱分支必须展开：

1. 条件：类型包含 `graphical` 且 item 状态不是 DOING；获取 `graphIngestSemaphore`。
2. 重建前调用 `GraphRag.cleanupDocument(kbUuid,itemUuid)`：按 provenance 找旧边/点；共享元素保留，只删除此文档独占的边后点；删除来源映射与旧 graph segments。
3. 更新 item 为 DOING，并记录实际图谱抽取模型 ID 与 started time；按知识库 ingest model 构建低温 ChatModel。
4. `GraphRag.ingest` 使用同类切块配置。每个 segment 生成 UUID，先写 `KnowledgeBaseGraphSegment` 原文与 kb/item 来源。
5. 对非空 segment 先检查收费额度，再用 `GraphExtractPrompt` 请求 LLM 提取实体/关系，解析为旧格式，并累计 token。
6. `GraphStoreIngestor` 在 LLM 抽取之后进入 graphStore 短临界区：按 `KB_UUID` 识别已有实体/关系，创建或合并 vertex/edge，把 `KB_ITEM_UUID` 追加到来源，记录 graph element source/provenance。
7. 成功更新 DONE/completed；失败时再次 `cleanupDocument` 清除本次部分写入并更新 FAIL；finally 释放 semaphore。

用状态小时间线显示 `未开始 -> DOING -> DONE/FAIL`，向量和图谱状态相互独立。异常路径需要明确：某一分支失败不会阻止 asyncIndex 的 finally 清理 Redis 计数；图谱失败必须清理部分图数据；上传解析失败不能进入索引。

底部信息卡列出关键存储：文件表、`knowledge_base_item`、PgVector/Neo4j 向量库、graph store、`knowledge_base_graph_segment`、graph element source、Redis 任务计数。禁止把 `KnowledgeBaseChunk/ChunkSet` 的只读查询接口画成当前索引主链，因为 `CanonicalChunkQueryService` 明确不参与索引与检索。

源码依据：`KnowledgeBaseController`、`KnowledgeBaseService.uploadDocs/uploadDoc/indexItems`、`KnowledgeBaseItemService.asyncIndex/indexingEmbedding/indexingGraph`、`EmbeddingRag`、`GraphRag`、`GraphStoreIngestor`、`PgVectorEmbeddingStoreConfig`、`Neo4jEmbeddingStoreConfig`。

## ZM-03：RAG 在线检索

### 提示词正文

请为 ZhiMesh 生成一张“使用既有索引检索知识库并增强回答”的完整流程图，输出 `outputs/zhimesh-rag-retrieval.html`。采用双入口 + 共享检索内核的布局：左上入口 A 为角色聊天，右上入口 B 为知识库专用 QA；中部汇聚到 `CompositeRag/DeduplicatingContentRetriever`；下部再分回各自的提示词与回答持久化。使用泳道区分请求编排、向量路由、图谱路由、融合/重排、LLM/引用记录。

入口 A（角色聊天）画出：`CharacterChatHelper.retrieve` 对原问题做一次 query embedding；构造语义记忆 filter `CHARACTER_ID + MEMORY_TYPE != episodic`、独立情景记忆 filter `CHARACTER_ID`、知识库 filter `KB_UUID in enabledKbUuids`；三类 wrapper 并发检索，单 wrapper 失败软降级，最多等待 1 分钟。知识库 maxResults=3、minScore 使用默认值，`breakIfSearchMissed=false`。语义/情景记忆来自物理隔离的 vector store。

入口 B（专用知识库 QA）画出：`POST /knowledge-base/qa/process/{qaRecordUuid} -> KnowledgeBaseService.retrieveAndPushToLLM`；检查每日次数和记录归属；根据答案模型 maxInputTokens 与知识库配置决定 maxResults，问题过长且 strict=true 直接报错，loose 模式可跳过检索直接问 LLM。正常时用 `KB_UUID = qaRecord.kbUuid`、minScore、strict、graphHopDepth、可选 rerank model/topN、system message 和 token window 配置创建 retriever。

共享 `CompositeRag` 默认 HYBRID，必须画出两个并行路由：

1. 向量路由：`ZhiMeshEmbeddingStoreContentRetriever` 优先复用预计算 query embedding，否则调用 embedding model；构造 `EmbeddingSearchRequest(maxResults,minScore,filter)`；在 PgVector 或 Neo4j 向量索引搜索；每个命中写入 route=vector、vectorScore、vectorRank、embeddingId，并缓存 `embeddingId -> score` 供引用落库。
2. 图谱路由：按 filter 从 graph store 读取候选 vertex；先做问题中的实体名直接匹配，只有没有直接 anchor 时才用 ChatModel 提取实体；匹配 anchors 后按配置 1~2 hop 双向遍历 edge；收集 vertex/edge 引用；通过 provenance 将图元素解析回 `KnowledgeBaseGraphSegment` 原文，旧图没有来源时才回退到 vertex/edge description；对来源原文和少量关系描述做词法相关性与文档多样性排序。

`DeduplicatingContentRetriever` 融合阶段必须按真实顺序展开：各路由可并行执行并有 vector/graph 独立 timeout、retry；记录 completed/timeout/error。若没有候选且存在路由失败，整体报“无可用检索上下文”；若只是无命中，strict 模式抛 `B_BREAK_SEARCH`，loose 模式返回空。然后跨路由按规范化文本精确去重并合并来源排名，计算 RRF；若配置 BGE reranker，则对候选池重排，失败或熔断时回退 RRF；没有成功重排时可保护明显领先的向量证据；再做近重复内容后移、相对 rerank cutoff；最后按模型上下文预算、预留输出/历史/安全 token、每文档上限和 graph description 比例装箱，必要时按句边界截断首证据。

下游分成两路：

- 角色聊天：`buildMemoryAndKnowledge` 分开稳定语义记忆、过往事件和知识证据，`PromptUtil.createPrompt` 生成 processedPrompt，再走普通流式 LLM；完成后把向量、图谱、两类记忆引用写到 message ref 表。
- 专用 QA：`CompositeRag.ragChat` 可带两条消息的 memory 与 `CompressingQueryTransformer`，流式回答；strict 无证据时输出固定“根据当前知识库无法确定”；完成后更新 QA 记录、token/LLM 记录，并写 embedding/graph reference。

图中用实线表示证据内容，用点划线表示 trace/引用元数据。信息卡必须解释：HYBRID 不是“先向量失败再图谱”，而是两路并行后融合；图谱返回应优先回到来源原文，而非只把关系描述喂给模型；角色聊天的记忆检索与知识库检索使用同一套 wrapper 形式但不同 filter/store。

源码依据：`CharacterChatHelper`、`KnowledgeBaseService.retrieveAndPushToLLM`、`CompositeRag`、`ZhiMeshEmbeddingStoreContentRetriever`、`GraphStoreContentRetriever`、`DeduplicatingContentRetriever`、`BgeReranker`。

## ZM-04：MCP 完整生命周期

### 提示词正文

请为 ZhiMesh 生成一张“MCP 从管理员定义、用户启用、每次聊天初始化到模型工具调用完成”的端到端流程图，输出 `outputs/zhimesh-mcp-lifecycle.html`。采用上下两大阶段：上半部“配置期”，下半部“请求运行期”；运行期再用循环箭头表现 LLM -> MCP tool -> LLM。泳道：管理员、用户/Character、MCP 服务层、LangChain4j/LLM、外部 MCP Server。

配置期按事实绘制：

1. 管理员通过 Admin MCP API 保存 `Mcp` 定义，包含 enabled、transportType、SSE/Streamable HTTP URL 或 stdio command/args、timeout、preset params、customized param definitions。标记 `requireEncrypt` 的 preset value 用 AES 加密后入库。
2. 用户通过 `/user/mcp/saveOrUpdate` 保存自己的参数与 isEnable。`McpRuntimeConfigValidator.validateForStorage` 检查缺失、未知/重复参数、客户端伪报 encrypted、与 preset 名冲突；不合法直接拒绝。合法敏感自定义参数 AES 加密写 `UserMcp`。
3. Character 只保存要使用的 `mcpIds`；“MCP 定义启用 + 用户关系启用 + Character 绑定”三个条件同时满足才进入运行期。

请求运行期按顺序绘制：

1. `CharacterChatHelper.buildChatRequestParams` 发现 enableMcp 且 Character.mcpIds 非空，调用 `UserMcpService.createMcpClients(userId,mcpIds)`。
2. 查询用户已启用的 UserMcp，再查询系统已启用的 Mcp；逐个执行 runtime validator。无效配置或初始化异常只跳过当前 MCP，不让全部聊天失败。
3. 解密用户敏感参数；`createEnvironment` 合并管理员 preset 与用户自定义参数，标记 `cliArg=true` 的参数不放环境变量，而由 `buildCliArguments` 追加命令行。
4. transport 决策菱形：SSE -> `HttpMcpTransport`，将环境参数 URL encode 追加 query；Streamable HTTP -> `StreamableHttpMcpTransport` 同样追加 query；STDIO -> 组装 executable + args + cli args + environment，Windows 的 npx/npx.cmd 改成 `cmd /c npx`。每个 transport 生成 `DefaultMcpClient`。
5. `AbstractLLMService.discoverRequestTools -> McpToolRegistry.discover` 对每个 client 调 `listTools()`；按稳定顺序建立 `ToolSpecification -> McpClient`，工具同名时保留第一个 provider 并记录警告。工具定义计入短期记忆 token budget，再随 ChatRequest 发给 LLM。
6. LLM 返回普通内容则结束；返回 `ToolExecutionRequest` 时进入循环。若缺 id/name，`parseToolRequest` 从 arguments 补齐；按工具名选择 client，找不到则构造错误 ToolExecutionResultMessage。
7. 找到 client 后调用 `executeTool(req)` 到外部 MCP Server；成功/失败都记录 duration，并通过 SSE `[TOOL_CALL]` 向前端发送 `{toolName,durationMs,success}`；结果文本或错误消息封装为 `ToolExecutionResultMessage`。
8. 把模型的工具请求 AiMessage 与工具结果追加到对话消息，再次调用 LLM。循环深度小于 5 时继续；达到 5 层进入错误/关闭分支；最终无工具请求时输出答案并关闭全部 MCP clients。任何 streaming error、同步异常或完成路径都要执行 close。

必须画出安全/边界卡：不在图中展示任何真实 preset/custom value；HTTP 参数当前通过 URL query 传递，stdio 通过 environment/CLI；重复工具名不是随机选择；每次聊天临时建 client 并在完成后关闭；单个 MCP 初始化失败为软降级，工具递归过深为硬失败。

源码依据：`McpService`、`UserMcpService`、`McpRuntimeConfigValidator`、`CharacterChatHelper.buildChatRequestParams`、`McpToolRegistry`、`AbstractLLMService.streamingChat/createToolExecutionMessages`。

## ZM-05：工作流定义与编译

### 提示词正文

请为 ZhiMesh 生成一张“可视化工作流定义如何被编译成 LangGraph4j 状态图”的实现原理图，输出 `outputs/zhimesh-workflow-compile.html`。上半部画定义/持久化，下半部画运行前编译算法。使用 4 条泳道：Vue 工作流编辑器、WorkflowService/数据库、WorkflowEngine 编译树、LangGraph4j StateGraph。

定义阶段按顺序绘制：

1. `POST /workflow/add` 插入 Workflow，并自动创建唯一 Start 节点。
2. Vue 编辑器加载 `/workflow/public/component/list` 中可用组件，创建节点配置、输入定义、引用输入和边；`POST /workflow/update` 在事务中 create/update nodes、create/update edges、删除用户移除的 nodes/edges。
3. 数据模型必须展示：Workflow；WorkflowNode（uuid、componentId、nodeConfig、inputConfig，其中 inputConfig 包含 userInputs/refInputs）；WorkflowEdge（sourceNodeUuid、targetNodeUuid、sourceHandle）。
4. 当前可执行工厂组件只列：Start、End、Answer、DocumentExtractor、KeywordExtractor、FaqExtractor、KnowledgeRetrieval、Switcher、Classifier、Template、TextTransform、VariableAggregator、Google、HumanFeedback、MailSend、HttpRequest。明确注释：源码虽有 Agent/图像相关类和部分前端文件，但当前 enum/factory 未注册，不能画为已启用组件。

编译阶段从 `WorkflowStarter.asyncRun` 开始：加载 enabled components、workflow nodes、edges，创建 `WorkflowEngine`。`findStartAndEndNode` 必须校验恰好一个 Start；显式 End 和“有入边无出边”的节点均作为终点；无 Start/End 失败。`getAndCheckUserInput` 根据 Start.userInputs 校验必填、类型和值，生成 `NodeIOData`。

`buildCompileNode` 递归构建中间编译树，画出以下判断：

- 每个 node UUID 访问次数超过 10，判定可能有环并失败。
- 单一上游通常生成普通 `CompileNode`。
- 某上游有多个不带 `sourceHandle` 的出边，视为并行分叉，创建 `GraphCompileNode(id=parallel_rootUuid)`；分支在子图中执行，汇聚 tail 后回主图。
- 多上游节点视为汇聚点，接到对应并行 branch/tail。
- 普通节点有多个 next 且 next 不是并行子图时，标记 conditional；运行时节点返回的 `resultMap.next` 决定目标。

`buildStateGraph` 必须画出：普通节点通过 `node_async(state -> runNode)` 注册；START/END 边；并行 `GraphCompileNode` 编译为嵌套 StateGraph 子图；条件节点用 `addConditionalEdges` 和 target mapping；对 HumanFeedback 组件把 nodeUuid 加入 `interruptNodes`。最后使用 `MemorySaver`，`CompileConfig.checkpointSaver`，并将 `interruptBefore` 设置为人工反馈节点，调用 `mainStateGraph.compile` 得到 `CompiledGraph<WfNodeState>`。

信息卡解释三个状态层次：持久化定义 `WorkflowNode/Edge`；编译结构 `CompileNode/GraphCompileNode`；运行状态 `WfState/WfNodeState`。强调 parallel 与 conditional 的区分依据，禁止把所有多出边都画成并行。

源码依据：`WorkflowController`、`WorkflowService.update`、`WorkflowNodeService`、`WorkflowEdgeService`、`WfComponentNameEnum`、`WfNodeFactory`、`WorkflowEngine.findStartAndEndNode/buildCompileNode/buildStateGraph`。

## ZM-06：工作流运行与人工反馈恢复

### 提示词正文

请为 ZhiMesh 生成一张“已编译工作流如何流式执行、持久化节点结果并在人机节点暂停/恢复”的流程图，输出 `outputs/zhimesh-workflow-runtime.html`。采用 5 条泳道：前端、WorkflowStarter/SSE、LangGraph4j/WorkflowEngine、节点执行器、运行记录数据库。主链从 `POST /workflow/run/{wfUuid}` 开始；中部必须画人工反馈循环；右侧显示完成和失败两个终态。

按以下顺序绘制：

1. 前端 `workflowRun` 通过 SSE 发送 workflow UUID 与 inputs。`WorkflowStarter.streaming` 做 SSE 并发检查，只 register emitter，不能先发 START；校验 workflow 存在且 enabled，随后 `@Async asyncRun`。
2. 加载组件/节点/边并完成 ZM-05 的编译。`WorkflowEngine.run` 创建 `WorkflowRuntime`，此时才用 runtime DTO 作为 payload 发送 SSE `[START]`；建立 `WfState(user,input,runtimeUuid)` 并持久化 runtime input。
3. `app.stream(Map.of(), invokeConfig)` 产生输出；每个图节点进入 `runNode`：通过 `WfNodeFactory` 创建具体 `AbstractWfNode`；先创建 `WorkflowRuntimeNode` 并发送 `[NODE_RUN_uuid]`。
4. `AbstractWfNode.process` 把状态设为 DOING；`initInput` 对 Start 使用工作流输入，对其他节点复制最近上游输出，并按 `WfNodeParamRef(nodeUuid,nodeParamName)` 从已完成节点取值，默认 output 重命名为 input；把 input 持久化并逐项发 `[NODE_INPUT_uuid]`。
5. 具体节点 `onProcess` 执行业务：普通转换/HTTP/检索/LLM；Switcher 依据引用变量和 AND/OR 条件返回 next；Classifier 调 LLM 后返回分类目标 next；流式 LLM 节点把 generator 放到 `nodeToStreamingGenerator`。成功写 outputs、duration/类型化 metrics、SUCCESS 和 completedNodes；失败写 FAIL 并抛出带节点标题的错误。
6. 输出回调更新 runtime node output，发送 `[NODE_OUTPUT_uuid]` 与非流式 metrics。`streamingResult` 对 `StreamingOutput` 发送 `[NODE_CHUNK_uuid]`；流式节点完成后才更新最终 output/token metrics。LangGraph4j 根据普通边、并行子图或 `resultMap.next` 条件边继续。
7. 执行流结束后读取 checkpoint snapshot 的 `nextNode`。若 next 非空且非 END，说明在 HumanFeedback 前被 `interruptBefore` 暂停：发送 `[NODE_WAIT_FEEDBACK_BY_uuid]` 与提示；把 `runtimeUuid -> WorkflowEngine` 放进进程内 `InterruptedFlow`；runtime 状态更新为 WAITING_INPUT 并保存当前 output/metrics。
8. 前端调用 `POST /workflow/runtime/resume/{runtimeUuid}`。`WorkflowStarter.resumeFlow` 必须从 `InterruptedFlow` 找到同一个内存 engine；找不到则 `A_WF_RESUME_FAIL`。找到后 `app.updateState(... HUMAN_FEEDBACK_KEY=userInput)`，再调用 `app.stream(null, invokeConfig)`；HumanFeedbackNode 把输入转为默认 output，后续节点继续。若再次遇到人工反馈则继续保留；完全结束才移除映射。
9. 正常结束：更新 runtime output、wall-clock duration 与各 LLM 节点 token 汇总；先发 runtime metrics，再 `[DONE]` 返回最终 ObjectNode；移除 InterruptedFlow。失败：best-effort 更新 runtime FAIL/错误/metrics，即使数据库更新失败也必须发送 `[ERROR]` 并 complete。

在侧边画 blocking API 的小分支：`WorkflowStarter.blocking` 使用 dummy completed SSE，调用 `blockingRun`/`app.invoke`，主动 drain streaming generators 后返回 task_id/status/outputs；blocking compile 不设置人工反馈 interrupt，且不应画成支持跨请求 resume。

边界卡必须说明：checkpoint 是当前 JVM 的 `MemorySaver`，InterruptedFlow 也是进程内映射，当前实现的恢复依赖原进程仍存活；数据库保存 runtime/node 元数据、输入输出和指标，但不是用来重建 LangGraph 状态。并行分支的总耗时采用 wall-clock，而不是节点耗时求和。

源码依据：`WorkflowController`、`WorkflowRuntimeController`、`WorkflowStarter`、`WorkflowEngine.run/exe/resume/runNode/streamingResult`、`AbstractWfNode`、`WfState`、`InterruptedFlow`、`WorkflowRuntimeService`、`WorkflowRuntimeNodeService`。
