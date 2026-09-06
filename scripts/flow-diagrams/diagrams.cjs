const s = (id, lane, row, type, title, detail = '') => ({ id, lane, row, type, title, detail });
const e = (from, to, label = '', kind = 'main', side) => ({ from, to, label, kind, side });
const withNumbers = (diagram) => ({
  ...diagram,
  steps: diagram.steps.map((step, index) => ({ ...step, number: String(index + 1).padStart(2, '0') }))
});

module.exports = [
withNumbers({
  code: 'ZM-01', project: 'ZhiMesh / 智枢', file: 'zhimesh-chat-process.html',
  title: '完整聊天过程',
  subtitle: '从 Vue 乐观插入、SSE 建连、会话边界解析，到混合 RAG、MCP 工具循环、消息落库和长期记忆提取的完整主链。',
  tags: ['POST /api/chat/process', 'SSE streaming', 'RAG + Memory', 'MCP tools ≤ 5', 'transactional persistence'],
  lanes: [
    { name: '用户 / Vue 前端', role: 'InputEditor · Pinia', color: '#34d399' },
    { name: 'Controller / SSE', role: 'ChatController · SseManager', color: '#22d3ee' },
    { name: '聊天编排', role: 'CharacterChatService · Helper', color: '#a78bfa' },
    { name: 'RAG / MCP / LLM', role: 'Retriever · Model · Tools', color: '#c084fc' },
    { name: '持久化与记忆', role: 'PostgreSQL · MapDB · Vector', color: '#fbbf24' }
  ],
  phases: [
    { row: 0, title: 'A · 客户端建连与上下文解析', stroke: '#22d3ee' },
    { row: 8, title: 'B · 模型选择、检索与推理', stroke: '#a78bfa' },
    { row: 18, title: 'C · 落库、记忆与完成事件', stroke: '#34d399' }
  ],
  steps: [
    s('input',0,0,'manual','输入文本与附件','createChatTask · 生成 questionUuid / answerUuid'),
    s('front-check',0,1,'decision','前端请求可发送？','登录 · 会话 · 请求中 · 非空'),
    s('optimistic',0,2,'process','乐观插入两条消息','USER 问题 + loading 空回答'),
    s('fetch',0,3,'integration','fetchEventSource 建立 SSE','Authorization · AskReq · AbortController'),
    s('ask',1,4,'start','ChatController.ask','sseAsk 创建 sseUuid / SseEmitter'),
    s('limit',1,5,'decision','SSE 限流通过？','checkOrComplete · 并发与频率'),
    s('async',1,6,'process','startSse + @Async','HTTP 线程立即返回 emitter'),
    s('resolve',2,7,'process','ChatContextResolver.resolve','反查会话角色或创建默认会话'),
    s('ownership',2,8,'decision','会话 / 角色归属有效？','当前用户必须拥有资源'),
    s('memory-key',4,9,'storage','确定 conversation 记忆边界','默认会话可迁移并双写旧角色 key'),
    s('regenerate',2,10,'decision','是否重新生成？','复用原 prompt / 附件 / 问题 UUID'),
    s('model',3,11,'process','选择实际可用模型','getServiceOrDefault · 健康降级并回写'),
    s('quota',3,12,'decision','最终模型额度可用？','只校验实际选中的收费模型'),
    s('has-kb',3,13,'decision','有关联知识库？','filterEnableKb · 发送检索状态'),
    s('retrieve',3,14,'process','一次 embedding · 三路并发检索','语义记忆 / 情景记忆 / HYBRID 知识库'),
    s('prompt',2,15,'process','构造 processedPrompt','Stable knowledge · Past events · knowledge'),
    s('params',3,16,'process','装配 ChatRequestParams','system · memoryId · 图片 · MCP · web search'),
    s('llm',3,17,'integration','AbstractLLMService.streamingChat','发现 tools · token window · 调用模型'),
    s('stream',1,18,'process','SSE 增量事件','[THINKING] / chunk / [STATE_CHANGED]'),
    s('front-stream',0,19,'manual','Pinia 增量刷新答案','按事件类型更新 UI'),
    s('tool?',3,20,'decision','模型请求工具？','ToolExecutionRequest'),
    s('tool',3,21,'integration','执行 MCP tool','发送 [TOOL_CALL] · 封装 result message'),
    s('persist',2,22,'process','saveAfterAiResponse 事务','新建/复用 USER · 插入 ASSISTANT · touch 会话'),
    s('db',4,23,'storage','保存消息与引用','message · conversation · LLM record · ref tables'),
    s('short?',3,24,'decision','开启短期记忆？','角色 enableContext'),
    s('short',4,25,'storage','ShortTermMemoryWindow.append','MapDB conversation 窗口 · 可双写旧 key'),
    s('long',3,26,'process','异步提取长期记忆','JSON 不支持则尝试免费模型，否则跳过'),
    s('done',1,27,'end','sendComplete · [DONE]','meta · 引用标志 · 用量 · conversationUuid'),
    s('front-done',0,28,'end','覆盖临时 UUID 并结束 loading','首轮同步更新会话标题'),
    s('error',1,29,'decision','统一异常出口','[ERROR] → complete'),
    s('front-error',0,30,'note','前端展示系统提示','Abort 仅终止客户端连接，不代表 DB 回滚')
  ],
  edges: [
    e('input','front-check'),e('front-check','optimistic','可发送','success'),e('optimistic','fetch'),e('fetch','ask'),e('ask','limit'),
    e('limit','async','通过','success'),e('async','resolve'),e('resolve','ownership'),e('ownership','memory-key','有效','success'),e('memory-key','regenerate'),
    e('regenerate','model','复用或新建'),e('model','quota'),e('quota','has-kb','可用','success'),e('has-kb','retrieve','有','success'),e('has-kb','prompt','无'),e('retrieve','prompt'),
    e('prompt','params'),e('params','llm'),e('llm','stream'),e('stream','front-stream'),e('stream','tool?'),e('tool?','tool','是','success'),e('tool','llm','depth < 5','async','right'),
    e('tool?','persist','否'),e('persist','db'),e('db','short?'),e('short?','short','是','success'),e('short?','long','否'),e('short','long'),e('long','done','异步后不阻塞','async'),e('done','front-done'),
    e('front-check','front-error','拒绝','error'),e('limit','error','拒绝','error'),e('ownership','error','无效','error'),e('quota','error','不足','error'),e('tool','error','depth = 5 / 异常','error'),e('error','front-error','[ERROR]','error')
  ],
  cards: [
    { kicker:'STATE & EVENTS', title:'核心对象与 SSE 协议', tone:'data', items:['输入 AskReq；上下文 ChatContext / SseAskParam / ChatModelRequest','事件：[START] [STATE_CHANGED] [THINKING] chunk [TOOL_CALL] [DONE] [ERROR]','引用标志来自 retriever 的实际向量、图谱、记忆命中'] },
    { kicker:'FACT BOUNDARY', title:'当前实现边界', tone:'boundary', items:['ASR / TTS 当前停用，不进入聊天主链','用户 AbortController 只断开客户端，不等价于服务端事务回滚','MCP 子流程详见 ZM-04；RAG 检索内核详见 ZM-03'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['InputEditor.vue · user-web/src/api/index.ts · ChatController','CharacterChatService · ChatContextResolver · CharacterChatHelper','AbstractLLMService · ShortTermMemoryWindow · LongTermMemoryService'] }
  ]
}),

withNumbers({
  code: 'ZM-02', project: 'ZhiMesh / 智枢', file: 'zhimesh-rag-indexing.html',
  title: 'RAG 索引建立',
  subtitle: '知识库文件从上传、解析、创建条目，到向量索引与知识图谱索引并行构建、状态回写和 Redis 任务计数清理。',
  tags: ['upload ≠ indexing', 'embedding branch', 'graphical branch', 'Redis task counter', 'provenance cleanup'],
  lanes: [
    { name:'用户 / API', role:'KnowledgeBaseController', color:'#34d399' },
    { name:'文档与索引编排', role:'KnowledgeBaseService · ItemService', color:'#22d3ee' },
    { name:'向量分支', role:'EmbeddingRag · Ingestor', color:'#a78bfa' },
    { name:'图谱分支与存储', role:'GraphRag · GraphStore', color:'#fbbf24' }
  ],
  phases: [
    { row:0,title:'A · 上传与解析阶段',stroke:'#22d3ee' },
    { row:7,title:'B · 调度与任务计数',stroke:'#a78bfa' },
    { row:12,title:'C · 向量 / 图谱独立分支',stroke:'#fbbf24' },
    { row:24,title:'D · 汇合与完成检测',stroke:'#34d399' }
  ],
  steps: [
    s('entry',0,0,'start','四类索引入口','批量上传 · 单文件 · 全库 · 指定 items'),
    s('permission',0,1,'decision','知识库写权限通过？','indexAfterUpload 默认补 embedding'),
    s('save',1,2,'integration','FileService.saveFile','先保存文件记录'),
    s('parse',1,3,'process','loadDocument 并归一化','支持格式 · 文本非空 · 去除 \\u0000'),
    s('parsed?',1,4,'decision','解析成功且非空？','否则不创建 KnowledgeBaseItem'),
    s('item',1,5,'storage','创建 KnowledgeBaseItem','uuid · kbUuid · sourceFileId · title / brief'),
    s('batch',1,6,'process','收集成功 item UUID','批量上传结束后仅 indexItems 一次'),
    s('scope',0,7,'decision','索引入口类型？','指定 items / 上传后 或 全库分页'),
    s('preflight',1,8,'decision','USER_INDEXING 已存在？','indexItems 有预检；全库入口无同样预检'),
    s('schedule',1,9,'process','checkAndIndexing / oneByOne','代理对象提交 @Async asyncIndex'),
    s('counter+',1,10,'storage','Redis 任务计数 +1','刷新 10 分钟 TTL'),
    s('types',1,11,'decision','选择索引类型','仅 embedding / graphical'),
    s('emb-check',2,12,'decision','embedding 且非 DOING？','向量状态独立'),
    s('graph-check',3,12,'decision','graphical 且非 DOING？','图谱状态独立'),
    s('emb-clean',2,13,'process','删除 item 旧向量','Document metadata: KB_UUID / KB_ITEM_UUID'),
    s('graph-clean',3,13,'process','cleanupDocument 旧图数据','共享元素保留；独占边后点 + provenance 清理'),
    s('emb-lock',2,14,'process','获取 embedding semaphore','状态 DOING · started'),
    s('graph-lock',3,14,'process','获取 graph semaphore','状态 DOING · 抽取模型 ID · started'),
    s('emb-split',2,15,'process','DocumentSplitterFactory 切块','strategy · max size · overlap · separator · token'),
    s('graph-split',3,15,'process','切块并保存 segment 原文','KnowledgeBaseGraphSegment + 来源 UUID'),
    s('embed',2,16,'integration','EmbeddingStoreIngestor','embedding model 生成向量'),
    s('extract',3,16,'decision','segment 非空且额度可用？','GraphExtractPrompt 调低温 ChatModel'),
    s('vector-store',2,17,'storage','写 kbEmbeddingStore','PgVector 维度表 或 Neo4j vector index'),
    s('graph-ingest',3,17,'process','解析实体 / 关系','累计 token · 旧格式兼容'),
    s('emb-state',2,18,'decision','向量写入成功？','DONE / FAIL；finally 释放 semaphore'),
    s('graph-critical',3,18,'integration','GraphStoreIngestor 短临界区','按 KB_UUID 合并 vertex / edge 并追加来源'),
    s('provenance',3,19,'storage','写 graph provenance','graph element source · segment 引用'),
    s('graph-state',3,20,'decision','图谱写入成功？','失败再次 cleanupDocument'),
    s('states',1,22,'process','独立状态时间线','未开始 → DOING → DONE / FAIL'),
    s('finally',1,24,'process','asyncIndex finally','写重算 signal · Redis 计数 -1'),
    s('zero',1,25,'decision','任务计数归零？','归零删除 USER_INDEXING key'),
    s('check',0,26,'end','GET /indexing/check','仅以 Redis key 是否存在判断完成'),
    s('parse-error',0,27,'note','上传结果标记失败','UNSUPPORTED_OR_EMPTY / UPLOAD_OR_PARSE_FAILED')
  ],
  edges: [
    e('entry','permission'),e('permission','save','通过','success'),e('save','parse'),e('parse','parsed?'),e('parsed?','item','是','success'),e('parsed?','parse-error','否','error'),e('item','batch'),
    e('batch','scope'),e('scope','preflight','指定 items'),e('scope','schedule','全库分页'),e('preflight','schedule','无冲突','success'),e('preflight','parse-error','已有任务','error'),e('schedule','counter+'),e('counter+','types'),
    e('types','emb-check','embedding'),e('types','graph-check','graphical'),e('emb-check','emb-clean','执行','success'),e('graph-check','graph-clean','执行','success'),e('emb-clean','emb-lock'),e('graph-clean','graph-lock'),
    e('emb-lock','emb-split'),e('graph-lock','graph-split'),e('emb-split','embed'),e('graph-split','extract'),e('embed','vector-store'),e('extract','graph-ingest','有效','success'),e('vector-store','emb-state'),
    e('graph-ingest','graph-critical'),e('graph-critical','provenance'),e('provenance','graph-state'),e('emb-state','states'),e('graph-state','states'),e('graph-state','graph-clean','失败清部分写入','error','right'),
    e('states','finally'),e('finally','zero'),e('zero','check','归零','success'),e('zero','check','仍有任务','async')
  ],
  cards: [
    { kicker:'STORAGE', title:'索引涉及的持久化', tone:'data', items:['文件记录 · knowledge_base_item · 独立 embedding / graphical 状态','PgVector 或 Neo4j vector index','graph store · knowledge_base_graph_segment · graph element source · Redis 任务计数'] },
    { kicker:'FACT BOUNDARY', title:'调度与失败语义', tone:'boundary', items:['上传解析与索引是两个阶段；解析失败不能进入索引','批量上传只发起一次批量索引，避免文件间争抢任务锁','任一分支失败仍进入 asyncIndex finally；图谱失败清理部分写入'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['KnowledgeBaseController · KnowledgeBaseService','KnowledgeBaseItemService.asyncIndex / indexingEmbedding / indexingGraph','EmbeddingRag · GraphRag · GraphStoreIngestor · PgVector/Neo4j configs'] }
  ]
}),

withNumbers({
  code: 'ZM-03', project: 'ZhiMesh / 智枢', file: 'zhimesh-rag-retrieval.html',
  title: 'RAG 在线检索',
  subtitle: '角色聊天与知识库专用 QA 双入口，共享向量/图谱并行召回、容错融合、RRF/BGE 重排、上下文预算装箱与引用追踪。',
  tags: ['dual entry', 'HYBRID parallel routes', 'RRF + rerank', 'token budget packing', 'reference trace'],
  lanes: [
    { name:'请求编排', role:'Chat / KB QA', color:'#34d399' },
    { name:'向量路由', role:'Embedding retriever', color:'#a78bfa' },
    { name:'图谱路由', role:'Graph retriever', color:'#fbbf24' },
    { name:'融合 / 重排', role:'Deduplicating retriever', color:'#22d3ee' },
    { name:'LLM / 引用记录', role:'Prompt · answer · refs', color:'#c084fc' }
  ],
  phases: [
    { row:0,title:'A · 双入口与检索配置',stroke:'#34d399' },
    { row:8,title:'B · HYBRID 并行检索内核',stroke:'#a78bfa' },
    { row:17,title:'C · 融合、重排与预算装箱',stroke:'#22d3ee' },
    { row:25,title:'D · 增强回答与引用落库',stroke:'#fbbf24' }
  ],
  steps: [
    s('chat-entry',0,0,'start','入口 A · 角色聊天','CharacterChatHelper.retrieve'),
    s('qa-entry',4,0,'start','入口 B · 知识库 QA','POST /knowledge-base/qa/process/{recordUuid}'),
    s('chat-embed',0,1,'process','原问题只 embedding 一次','供三个 wrapper 复用'),
    s('qa-check',4,1,'decision','次数 / 归属 / 问题长度有效？','strict 过长直接报错'),
    s('chat-filter',0,2,'process','构造三类检索 filter','语义记忆 · 情景记忆 · enabled KB'),
    s('qa-mode',4,2,'decision','loose 模式跳过检索？','问题过长时可直接问 LLM'),
    s('chat-wrapper',0,3,'process','三 wrapper 并发','单路失败软降级 · 最多等待 1 分钟'),
    s('qa-config',4,3,'process','计算专用 QA 配置','maxResults · minScore · strict · hop · rerank · token'),
    s('kernel',3,5,'integration','CompositeRag / Deduplicating','默认 HYBRID · 两路同时开始'),
    s('vector',1,8,'process','向量路由开始','优先复用预计算 query embedding'),
    s('graph',2,8,'process','图谱路由开始','按 filter 读取候选 vertex'),
    s('search-req',1,9,'process','EmbeddingSearchRequest','maxResults · minScore · filter'),
    s('anchor',2,9,'decision','问题直接匹配实体名？','无 anchor 才调用 ChatModel 抽实体'),
    s('vector-db',1,10,'storage','搜索向量索引','PgVector 或 Neo4j'),
    s('traverse',2,10,'process','anchors 双向遍历','1~2 hop · 收集 vertex / edge'),
    s('vector-meta',1,11,'process','附加向量 trace','route · vectorScore · rank · embeddingId'),
    s('provenance',2,11,'process','图元素回溯来源原文','解析 KnowledgeBaseGraphSegment'),
    s('graph-fallback',2,12,'decision','存在 provenance？','旧图才回退关系 / 实体 description'),
    s('graph-rank',2,13,'process','词法相关性 + 文档多样性','来源原文优先 · 少量关系描述'),
    s('route-status',3,15,'process','记录每路状态','completed / timeout / error · retry'),
    s('candidate?',3,17,'decision','存在候选？','无候选且路由失败 → 无可用上下文'),
    s('strict?',3,18,'decision','仅无命中且 strict？','strict 抛 B_BREAK_SEARCH；loose 返回空'),
    s('dedupe',3,19,'process','规范化文本精确去重','合并来源排名并计算 RRF'),
    s('rerank?',3,20,'decision','配置 BGE reranker？','候选池重排'),
    s('rerank',3,21,'process','BGE 重排或回退 RRF','失败/熔断回退；保护明显领先向量证据'),
    s('near',3,22,'process','近重复后移 + cutoff','相对 rerank 截止'),
    s('pack',3,23,'process','上下文预算装箱','预留输出/历史/安全 token · 句边界截断'),
    s('chat-prompt',0,25,'process','角色聊天增强提示词','Stable knowledge / Past events / knowledge'),
    s('qa-prompt',4,25,'process','CompositeRag.ragChat','两条 memory · query transformer · 流式回答'),
    s('chat-save',0,26,'storage','保存聊天引用','向量 · 图谱 · 两类记忆 ref tables'),
    s('qa-save',4,26,'storage','更新 QA 与引用','answer · token · LLM record · embedding/graph refs'),
    s('done',3,28,'end','检索增强回答完成','证据内容实线 · trace 元数据点划线'),
    s('retrieval-error',3,29,'decision','检索错误出口','无可用上下文 / B_BREAK_SEARCH')
  ],
  edges: [
    e('chat-entry','chat-embed'),e('chat-embed','chat-filter'),e('chat-filter','chat-wrapper'),e('chat-wrapper','kernel'),
    e('qa-entry','qa-check'),e('qa-check','qa-mode','有效','success'),e('qa-mode','qa-config','需要检索'),e('qa-config','kernel'),e('qa-mode','qa-prompt','跳过','async'),
    e('kernel','vector','并行','async'),e('kernel','graph','并行','async'),e('vector','search-req'),e('search-req','vector-db'),e('vector-db','vector-meta'),
    e('graph','anchor'),e('anchor','traverse','直接 / 抽取后'),e('traverse','provenance'),e('provenance','graph-fallback'),e('graph-fallback','graph-rank','来源或回退'),
    e('vector-meta','route-status'),e('graph-rank','route-status'),e('route-status','candidate?'),e('candidate?','strict?','无命中'),e('candidate?','dedupe','有','success'),
    e('strict?','retrieval-error','strict / 路由失败','error'),e('strict?','chat-prompt','loose 空上下文','async'),e('strict?','qa-prompt','loose 空上下文','async'),
    e('dedupe','rerank?'),e('rerank?','rerank','配置'),e('rerank?','near','未配置'),e('rerank','near'),e('near','pack'),e('pack','chat-prompt'),e('pack','qa-prompt'),
    e('vector-meta','chat-save','embeddingId → score','trace'),e('provenance','qa-save','graph source refs','trace'),e('chat-prompt','chat-save'),e('qa-prompt','qa-save'),e('chat-save','done'),e('qa-save','done')
  ],
  cards: [
    { kicker:'HYBRID SEMANTICS', title:'并行召回，不是 fallback', tone:'data', items:['HYBRID = vector 与 graph 同时执行，再融合；不是向量失败才查图谱','角色聊天三 wrapper 形式一致，但语义记忆、情景记忆、知识库使用不同 filter / store','图谱证据优先回到来源 segment 原文'] },
    { kicker:'FAILURE POLICY', title:'容错与严格模式', tone:'boundary', items:['vector / graph 有独立 timeout、retry 和状态记录','无候选且存在路由失败：无可用检索上下文','纯无命中：strict 抛 B_BREAK_SEARCH；loose 可空上下文继续'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['CharacterChatHelper · KnowledgeBaseService.retrieveAndPushToLLM','CompositeRag · ZhiMeshEmbeddingStoreContentRetriever · GraphStoreContentRetriever','DeduplicatingContentRetriever · BgeReranker'] }
  ]
}),

withNumbers({
  code: 'ZM-04', project: 'ZhiMesh / 智枢', file: 'zhimesh-mcp-lifecycle.html',
  title: 'MCP 完整生命周期',
  subtitle: '从管理员定义、用户参数校验和 Character 绑定，到每次聊天临时建 client、发现工具、递归调用外部 MCP Server 并在所有出口关闭。',
  tags: ['configuration phase', 'runtime validation', 'SSE / Streamable HTTP / STDIO', 'first provider wins', 'close on every exit'],
  lanes: [
    { name:'管理员', role:'Admin MCP API', color:'#34d399' },
    { name:'用户 / Character', role:'UserMcp · mcpIds', color:'#22d3ee' },
    { name:'MCP 服务层', role:'Validator · Client factory', color:'#a78bfa' },
    { name:'LangChain4j / LLM', role:'Tool registry · streamingChat', color:'#c084fc' },
    { name:'外部 MCP Server', role:'HTTP / process transport', color:'#fbbf24' }
  ],
  phases: [
    { row:0,title:'A · 配置期',stroke:'#34d399' },
    { row:7,title:'B · 每次聊天初始化与工具发现',stroke:'#22d3ee' },
    { row:17,title:'C · LLM ↔ Tool 循环与资源关闭',stroke:'#a78bfa' }
  ],
  steps: [
    s('admin-save',0,0,'manual','保存系统 Mcp 定义','enabled · transport · URL/command · timeout · params'),
    s('preset',2,1,'process','处理 preset params','requireEncrypt 的 value 用 AES 加密'),
    s('mcp-db',2,2,'storage','Mcp 定义入库','不在图中展示任何真实敏感值'),
    s('user-save',1,3,'manual','保存 UserMcp 参数','/user/mcp/saveOrUpdate · isEnable'),
    s('storage-valid',2,4,'decision','storage validator 合法？','缺失 / 未知 / 重复 / 伪 encrypted / preset 冲突'),
    s('user-db',2,5,'storage','敏感自定义参数 AES 入库','合法 UserMcp'),
    s('bind',1,6,'manual','Character 保存 mcpIds','只保存要使用的定义 ID'),
    s('triple',2,7,'decision','三个启用条件同时满足？','Mcp enabled + UserMcp enabled + Character 绑定'),
    s('build',2,8,'start','buildChatRequestParams','enableMcp 且 mcpIds 非空'),
    s('create',2,9,'process','createMcpClients(userId,mcpIds)','查已启用 UserMcp 与系统 Mcp'),
    s('runtime-valid',2,10,'decision','单个配置运行时有效？','无效 / 初始化异常只跳过当前 MCP'),
    s('environment',2,11,'process','解密并 createEnvironment','preset + customized；cliArg 不进环境'),
    s('transport',2,12,'decision','transport 类型？','SSE / Streamable HTTP / STDIO'),
    s('http',4,13,'integration','HTTP transport','环境参数 URL encode 追加 query'),
    s('stdio',4,14,'integration','STDIO transport','executable + args + CLI + env；Windows npx → cmd /c'),
    s('clients',2,15,'process','创建 DefaultMcpClient','每次聊天临时创建'),
    s('discover',3,16,'process','McpToolRegistry.discover','每个 client.listTools() · 稳定顺序'),
    s('duplicate',3,17,'decision','工具名重复？','保留第一个 provider 并警告'),
    s('budget',3,18,'process','工具定义计入 token budget','ToolSpecification 随 ChatRequest 发给 LLM'),
    s('llm',3,19,'integration','LLM 流式推理','普通内容或 ToolExecutionRequest'),
    s('tool?',3,20,'decision','返回工具请求？','无工具则完成答案'),
    s('parse',3,21,'process','parseToolRequest','缺 id/name 时从 arguments 补齐'),
    s('client?',3,22,'decision','按工具名找到 client？','找不到则构造错误 result message'),
    s('execute',4,23,'integration','executeTool(req)','外部 Server 返回结果或错误'),
    s('event',2,24,'process','记录 duration 与 SSE','[TOOL_CALL] {toolName,durationMs,success}'),
    s('append',3,25,'process','追加 AiMessage + ToolResult','结果文本 / 错误均回填对话'),
    s('depth',3,26,'decision','递归深度 < 5？','继续调用；达到 5 层硬失败'),
    s('close',2,28,'end','关闭全部 MCP clients','完成、streaming error、同步异常均 close'),
    s('soft',2,29,'note','软降级记录','单个 MCP 初始化失败不让整次聊天失败'),
    s('hard',3,29,'decision','硬失败出口','递归过深 / 全局 streaming 异常')
  ],
  edges: [
    e('admin-save','preset'),e('preset','mcp-db'),e('mcp-db','user-save'),e('user-save','storage-valid'),e('storage-valid','user-db','合法','success'),e('storage-valid','soft','拒绝','error','left'),e('user-db','bind'),e('bind','triple'),
    e('triple','build','满足','success'),e('build','create'),e('create','runtime-valid'),e('runtime-valid','environment','有效','success'),e('runtime-valid','soft','无效 / 初始化失败','async','left'),e('environment','transport'),
    e('transport','http','SSE / Streamable'),e('transport','stdio','STDIO'),e('http','clients'),e('stdio','clients'),e('clients','discover'),e('discover','duplicate'),e('duplicate','budget','首个 provider'),e('budget','llm'),
    e('llm','tool?'),e('tool?','close','普通内容'),e('tool?','parse','工具请求','success'),e('parse','client?'),e('client?','execute','找到','success'),e('client?','event','未找到','error'),e('execute','event'),e('event','append'),e('append','depth'),
    e('depth','llm','是 · 再次调用','async','right'),e('depth','hard','否','error'),e('hard','close','finally','error'),e('soft','close','若无可用 client 仍可纯 LLM','async')
  ],
  cards: [
    { kicker:'SECURITY & TRANSPORT', title:'参数传递边界', tone:'data', items:['敏感 preset / custom value 加密存储，本图不展示真实值','HTTP 当前把环境参数编码到 URL query；STDIO 使用 environment / CLI','Windows npx / npx.cmd 归一为 cmd /c npx'] },
    { kicker:'FAILURE POLICY', title:'软降级与硬失败', tone:'boundary', items:['单个配置无效或 client 初始化失败：跳过当前 MCP','同名工具稳定保留第一个 provider，不随机选择','递归工具调用达到 5 层：硬失败；所有完成与异常出口都 close'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['McpService · UserMcpService · McpRuntimeConfigValidator','CharacterChatHelper.buildChatRequestParams · McpToolRegistry','AbstractLLMService.streamingChat / createToolExecutionMessages'] }
  ]
}),

withNumbers({
  code: 'ZM-05', project: 'ZhiMesh / 智枢', file: 'zhimesh-workflow-compile.html',
  title: '工作流定义与编译',
  subtitle: '可视化节点/边定义如何持久化，并在运行前递归转换为普通节点、并行子图和条件边，最终编译为带 MemorySaver 与人工中断点的 LangGraph4j 图。',
  tags: ['WorkflowNode / Edge', 'recursive compile tree', 'parallel ≠ conditional', 'nested StateGraph', 'interruptBefore'],
  lanes: [
    { name:'Vue 工作流编辑器', role:'nodes · edges · configs', color:'#34d399' },
    { name:'WorkflowService / DB', role:'definition persistence', color:'#22d3ee' },
    { name:'WorkflowEngine 编译树', role:'CompileNode · GraphCompileNode', color:'#a78bfa' },
    { name:'LangGraph4j StateGraph', role:'nodes · edges · checkpoint', color:'#fbbf24' }
  ],
  phases: [
    { row:0,title:'A · 定义与事务持久化',stroke:'#34d399' },
    { row:7,title:'B · 运行前校验与编译树',stroke:'#a78bfa' },
    { row:17,title:'C · StateGraph 注册与 compile',stroke:'#fbbf24' }
  ],
  steps: [
    s('add',0,0,'start','POST /workflow/add','插入 Workflow'),
    s('start-node',1,1,'storage','自动创建唯一 Start','工作流初始定义'),
    s('component-list',0,2,'integration','加载可用组件列表','/workflow/public/component/list'),
    s('edit',0,3,'manual','编辑节点、输入引用与边','nodeConfig · userInputs / refInputs · sourceHandle'),
    s('update',1,4,'process','POST /workflow/update 事务','create/update + 删除用户移除的 nodes/edges'),
    s('model',1,5,'storage','持久化定义三层对象','Workflow · WorkflowNode · WorkflowEdge'),
    s('enabled-list',1,6,'note','当前已注册组件','Start/End/Answer/提取/检索/分支/模板/反馈/邮件/HTTP 等'),
    s('async-run',2,7,'start','WorkflowStarter.asyncRun','加载 enabled components / nodes / edges'),
    s('engine',2,8,'process','创建 WorkflowEngine','进入运行前编译'),
    s('find',2,9,'decision','恰好一个 Start 且存在终点？','显式 End + 有入边无出边节点'),
    s('input',2,10,'decision','Start.userInputs 合法？','必填 · 类型 · 值 → NodeIOData'),
    s('visit',2,11,'decision','node UUID 访问次数 > 10？','可能存在环 → 编译失败'),
    s('upstream',2,12,'decision','节点上游 / 出边形态？','决定普通、并行、汇聚、条件'),
    s('normal',2,13,'process','普通 CompileNode','通常为单一上游'),
    s('parallel',2,14,'process','GraphCompileNode(parallel_rootUuid)','同一上游多条无 sourceHandle 出边'),
    s('join',2,15,'process','并行分支 tail 汇聚','多上游节点接回主图'),
    s('conditional',2,16,'process','标记 conditional 节点','多 next 且非并行；resultMap.next 决定目标'),
    s('state',3,17,'process','创建 StateGraph<WfNodeState>','准备注册节点与边'),
    s('node-async',3,18,'process','node_async(state → runNode)','普通节点 + START / END 边'),
    s('subgraph',3,19,'process','并行编译为嵌套 StateGraph','GraphCompileNode 子图'),
    s('cond-edges',3,20,'process','addConditionalEdges','next → target mapping'),
    s('human',3,21,'decision','组件是 HumanFeedback？','nodeUuid 加入 interruptNodes'),
    s('checkpoint',3,22,'storage','配置 MemorySaver','CompileConfig.checkpointSaver + interruptBefore'),
    s('compile',3,23,'end','mainStateGraph.compile','得到 CompiledGraph<WfNodeState>'),
    s('compile-error',2,24,'decision','编译失败出口','无 Start/End · 环 · 输入非法 · 定义异常')
  ],
  edges: [
    e('add','start-node'),e('start-node','component-list'),e('component-list','edit'),e('edit','update'),e('update','model'),e('model','enabled-list'),e('enabled-list','async-run'),e('async-run','engine'),e('engine','find'),
    e('find','input','有效','success'),e('find','compile-error','失败','error'),e('input','visit','有效','success'),e('input','compile-error','无效','error'),e('visit','upstream','≤ 10','success'),e('visit','compile-error','> 10','error'),
    e('upstream','normal','普通'),e('upstream','parallel','并行'),e('parallel','join'),e('join','conditional'),e('normal','conditional'),e('conditional','state'),e('state','node-async'),e('node-async','subgraph'),e('subgraph','cond-edges'),e('cond-edges','human'),e('human','checkpoint','是 / 否'),e('checkpoint','compile')
  ],
  cards: [
    { kicker:'THREE STATE LAYERS', title:'定义、编译、运行三层', tone:'data', items:['持久化定义：WorkflowNode / WorkflowEdge','中间编译结构：CompileNode / GraphCompileNode','运行状态：WfState / WfNodeState'] },
    { kicker:'FACT BOUNDARY', title:'并行与条件的真实判定', tone:'boundary', items:['同一上游多条无 sourceHandle 出边才识别为并行分叉','普通节点多 next 且不是并行子图时才是 conditional','Agent / 图像相关源码未在当前 enum/factory 注册，不画成可执行组件'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['WorkflowController · WorkflowService.update · Node/Edge services','WfComponentNameEnum · WfNodeFactory','WorkflowEngine.findStartAndEndNode / buildCompileNode / buildStateGraph'] }
  ]
}),

withNumbers({
  code: 'ZM-06', project: 'ZhiMesh / 智枢', file: 'zhimesh-workflow-runtime.html',
  title: '工作流运行与人工反馈恢复',
  subtitle: '已编译工作流如何通过 SSE 逐节点执行、解析引用输入、记录输出与指标，并在 HumanFeedback 前暂停后依赖当前 JVM 内存状态恢复。',
  tags: ['SSE node events', 'WfNodeState', 'streaming generator', 'HumanFeedback resume', 'in-process checkpoint'],
  lanes: [
    { name:'前端', role:'workflowRun · resume', color:'#34d399' },
    { name:'WorkflowStarter / SSE', role:'streaming · asyncRun', color:'#22d3ee' },
    { name:'LangGraph4j / Engine', role:'stream · checkpoint', color:'#a78bfa' },
    { name:'节点执行器', role:'AbstractWfNode', color:'#c084fc' },
    { name:'运行记录数据库', role:'runtime · runtime_node', color:'#fbbf24' }
  ],
  phases: [
    { row:0,title:'A · 建连、编译与 runtime 初始化',stroke:'#22d3ee' },
    { row:7,title:'B · 节点执行与流式输出',stroke:'#a78bfa' },
    { row:17,title:'C · 人工反馈暂停 / 恢复',stroke:'#fbbf24' },
    { row:24,title:'D · 完成、失败与 blocking 边界',stroke:'#34d399' }
  ],
  steps: [
    s('run',0,0,'manual','POST /workflow/run/{wfUuid}','SSE 发送 workflow UUID + inputs'),
    s('register',1,1,'decision','SSE 并发与工作流有效？','只 register emitter；此时不能先发 START'),
    s('async',1,2,'process','@Async asyncRun','加载定义并执行 ZM-05 编译'),
    s('runtime',2,3,'process','WorkflowEngine.run','创建 WorkflowRuntime'),
    s('start-event',1,4,'start','此时才发送 [START]','payload 为 runtime DTO'),
    s('wf-state',2,5,'process','建立 WfState','user · input · runtimeUuid'),
    s('runtime-db',4,6,'storage','持久化 runtime input','初始运行记录'),
    s('stream',2,7,'integration','app.stream(Map.of(), config)','LangGraph4j 逐节点产出'),
    s('factory',3,8,'process','WfNodeFactory 创建节点','创建 WorkflowRuntimeNode'),
    s('node-run',1,9,'process','发送 [NODE_RUN_uuid]','节点进入执行'),
    s('doing',3,10,'process','AbstractWfNode.process','状态 DOING'),
    s('init-input',3,11,'process','initInput 解析输入','Start 用 workflow input；其他复制最近上游输出'),
    s('refs',3,12,'process','解析 WfNodeParamRef','从已完成节点取值；默认 output 重命名 input'),
    s('input-event',1,13,'process','持久化并逐项发送输入','[NODE_INPUT_uuid]'),
    s('on-process',3,14,'process','具体 onProcess','转换 / HTTP / 检索 / LLM / Switcher / Classifier'),
    s('node-ok',3,15,'decision','节点执行成功？','outputs · duration · metrics · completedNodes'),
    s('streaming?',3,16,'decision','存在 StreamingOutput？','generator 放入 nodeToStreamingGenerator'),
    s('output',1,17,'process','输出与 metrics 事件','[NODE_OUTPUT_uuid] / [NODE_CHUNK_uuid]'),
    s('save-node',4,18,'storage','保存 runtime node','input · output · SUCCESS/FAIL · typed metrics'),
    s('next',2,19,'decision','checkpoint nextNode 非空且非 END？','表示 interruptBefore HumanFeedback'),
    s('wait',1,20,'process','发送等待反馈事件','[NODE_WAIT_FEEDBACK_BY_uuid] + prompt'),
    s('memory-map',2,21,'storage','保存进程内暂停态','InterruptedFlow: runtimeUuid → WorkflowEngine'),
    s('waiting-db',4,22,'storage','runtime = WAITING_INPUT','保存当前 output / metrics'),
    s('resume',0,23,'manual','POST /runtime/resume/{runtimeUuid}','提交 HUMAN_FEEDBACK_KEY=userInput'),
    s('found?',2,24,'decision','InterruptedFlow 找到同一 engine？','找不到 → A_WF_RESUME_FAIL'),
    s('continue',2,25,'process','updateState + app.stream(null)','HumanFeedbackNode 输出默认 output 后继续'),
    s('again?',2,26,'decision','再次遇到人工反馈？','是则继续保留映射'),
    s('complete',1,27,'end','runtime metrics → [DONE]','最终 ObjectNode · 移除 InterruptedFlow'),
    s('complete-db',4,28,'storage','保存最终 runtime','output · wall-clock duration · LLM token 汇总'),
    s('fail',1,29,'decision','失败终态','best-effort DB FAIL；始终 [ERROR] + complete'),
    s('blocking',0,30,'note','blocking API 旁路','dummy completed SSE · app.invoke · drain generators · 不支持 resume')
  ],
  edges: [
    e('run','register'),e('register','async','有效','success'),e('register','fail','无效','error'),e('async','runtime'),e('runtime','start-event'),e('start-event','wf-state'),e('wf-state','runtime-db'),e('runtime-db','stream'),
    e('stream','factory'),e('factory','node-run'),e('node-run','doing'),e('doing','init-input'),e('init-input','refs'),e('refs','input-event'),e('input-event','on-process'),e('on-process','node-ok'),e('node-ok','streaming?','成功','success'),e('node-ok','fail','失败','error'),
    e('streaming?','output','流式 / 非流式'),e('output','save-node'),e('save-node','next'),e('next','wait','是','success'),e('next','complete','END'),e('wait','memory-map'),e('memory-map','waiting-db'),e('waiting-db','resume'),e('resume','found?'),
    e('found?','continue','找到','success'),e('found?','fail','未找到','error'),e('continue','again?'),e('again?','wait','再次暂停','async','right'),e('again?','complete','结束'),e('complete','complete-db'),e('fail','complete-db','best-effort','error'),e('run','blocking','blocking 入口','async')
  ],
  cards: [
    { kicker:'EVENT MODEL', title:'节点级 SSE 与持久化', tone:'data', items:['[NODE_RUN_uuid] · [NODE_INPUT_uuid] · [NODE_OUTPUT_uuid] · [NODE_CHUNK_uuid]','流式 generator drain 完成后才写最终 output / token metrics','并行分支总耗时使用 wall-clock，不把节点耗时简单相加'] },
    { kicker:'RECOVERY BOUNDARY', title:'恢复依赖当前 JVM', tone:'boundary', items:['checkpoint 是 MemorySaver；InterruptedFlow 也是进程内 Map','数据库保存 runtime/node 元数据、输入输出与指标，但不能重建 LangGraph 状态','原进程丢失后 resume 会 A_WF_RESUME_FAIL；blocking compile 不支持跨请求恢复'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['WorkflowController · WorkflowRuntimeController · WorkflowStarter','WorkflowEngine.run / exe / resume / runNode / streamingResult','AbstractWfNode · WfState · InterruptedFlow · Runtime services'] }
  ]
}),

withNumbers({
  code: 'GBM-01', project: '以牌惠友 / group-buy-market', file: 'group-buy-market-call-chain.html',
  title: '分层调用链路',
  subtitle: '四条真实入站链按请求方向穿过 API 契约、Trigger、Domain、依赖倒置的 Repository/Port 边界及 Infrastructure，最终落到 MySQL、Redis、RabbitMQ 与 HTTP。',
  tags: ['hexagonal layering', '4 inbound chains', 'domain-owned ports', 'MySQL + Redis + MQ', 'no Controller → DAO shortcut'],
  lanes: [
    { name:'浏览器 / 上游系统', role:'client / mall', color:'#34d399' },
    { name:'API 契约', role:'DTO · Response · interfaces', color:'#22d3ee' },
    { name:'Trigger 适配器', role:'Controller · listener · job', color:'#38bdf8' },
    { name:'Domain 领域层', role:'service · rules · aggregate · ports', color:'#a78bfa' },
    { name:'Infrastructure', role:'repository impl · DAO · gateway', color:'#c084fc' },
    { name:'外部基础设施', role:'MySQL · Redis · MQ · HTTP', color:'#fbbf24' }
  ],
  phases: [
    { row:0,title:'MODULE MAP · Maven 模块职责',stroke:'#64748b' },
    { row:3,title:'A · 首页查询链',stroke:'#22d3ee' },
    { row:11,title:'B · 锁单链',stroke:'#a78bfa' },
    { row:18,title:'C · 支付结算与通知链',stroke:'#34d399' },
    { row:27,title:'D · 退单与库存恢复链',stroke:'#fb7185' }
  ],
  steps: [
    s('client-role',0,0,'note','入站调用者','浏览器 / 上游商城 / 定时触发'),
    s('api-role',1,0,'note','group-buy-market-api','IMarket*Service · DTO · Response'),
    s('trigger-role',2,0,'note','group-buy-market-trigger','REST · Rabbit listener · scheduled job'),
    s('domain-role',3,0,'note','group-buy-market-domain','策略树 · 责任链 · 聚合 · Repository/Port'),
    s('infra-role',4,0,'note','group-buy-market-infrastructure','Repository 实现 · MyBatis · DCC · gateway'),
    s('resource-role',5,0,'note','app + types + resources','启动装配 / mapper XML / 通用枚举事件'),

    s('index-http',0,3,'start','首页 POST query config','/api/v1/gbm/index/query_group_buy_market_config'),
    s('index-dto',1,4,'integration','请求 / 响应契约','MarketProductEntity → TrialBalanceEntity'),
    s('index-controller',2,5,'process','MarketIndexController','限流 · 参数校验 · 返回组装'),
    s('index-domain',3,6,'process','indexMarketTrial 策略树','Root → Switch → Market → Tag → End/Error'),
    s('activity-port',3,7,'integration','IActivityRepository','Domain 定义的 Port'),
    s('activity-impl',4,8,'process','ActivityRepository','缓存 / DCC / BitSet / DAO'),
    s('index-store',5,9,'storage','Redis + MySQL','配置缓存 · crowd tag · 活动/折扣/SKU'),
    s('index-more',2,10,'process','继续查进行中队伍与统计','组装 GoodsMarketResponseDTO'),

    s('lock-http',0,11,'start','上游商城锁单','POST lock_market_pay_order'),
    s('lock-dto',1,12,'integration','锁单 API 契约','GroupBuyOrderAggregate / MarketPayOrderEntity'),
    s('lock-controller',2,13,'process','MarketTradeController','幂等查询 + 再次营销试算'),
    s('lock-domain',3,14,'process','TradeLockOrderService','活动可用 · 用户限次 · 团队名额预占规则'),
    s('trade-port',3,15,'integration','ITradeRepository','Domain Port'),
    s('trade-impl',4,16,'process','TradeRepository','Redis 名额 + MyBatis 事务'),
    s('lock-store',5,17,'storage','Redis + order tables','group_buy_order / group_buy_order_list'),

    s('settle-http',0,18,'start','支付结算请求','POST settlement_market_pay_order'),
    s('settle-dto',1,19,'integration','结算 API 契约','GroupBuyTeamSettlementAggregate / NotifyTaskEntity'),
    s('settle-controller',2,20,'process','MarketTradeController','进入 settlement service'),
    s('settle-domain',3,21,'process','TradeSettlementOrderService','SC → OutTradeNo → Settable → End filters'),
    s('settle-tx',4,22,'process','TradeRepository 事务','状态/计数更新 + notify_task'),
    s('settle-db',5,23,'storage','MySQL 本地消息','order/list + notify_task'),
    s('task',3,24,'process','TradeTaskService → ITradePort','立即异步通知'),
    s('port-impl',4,25,'process','TradePort','Redisson lock · OkHttp / MQ publisher'),
    s('notify',5,26,'integration','HTTP callback / RabbitMQ','失败由 GroupBuyNotifyJob 扫表补偿'),

    s('refund-entry',0,27,'start','HTTP 退单 / 超时任务','TimeoutRefundJob 每分钟扫描'),
    s('refund-dto',1,28,'integration','逆向对象','GroupBuyRefundAggregate / TeamRefundSuccess'),
    s('refund-trigger',2,29,'process','Controller / scheduled job','加载可退订单'),
    s('refund-domain',3,30,'process','TradeRefundOrderService','Data → UniqueRefund → RefundOrder 责任链'),
    s('refund-strategy',3,31,'process','三种 refund strategy','未支付 / 已支付未成团 / 已成团'),
    s('refund-tx',4,32,'process','TradeRepository 事务','订单/团队更新 + notify_task'),
    s('refund-mq',5,33,'integration','RabbitMQ 退单事件','TeamRefundSuccess'),
    s('refund-listener',2,34,'process','RefundSuccessTopicListener','restoreTeamLockStock'),
    s('redis-recover',5,35,'storage','Redis 恢复计数','未成团两类恢复；已成团不恢复')
  ],
  edges: [
    e('index-http','index-dto'),e('index-dto','index-controller'),e('index-controller','index-domain'),e('index-domain','activity-port','MarketProductEntity'),e('activity-port','activity-impl'),e('activity-impl','index-store'),e('index-store','index-more'),
    e('lock-http','lock-dto'),e('lock-dto','lock-controller'),e('lock-controller','lock-domain'),e('lock-domain','trade-port','GroupBuyOrderAggregate'),e('trade-port','trade-impl'),e('trade-impl','lock-store'),
    e('settle-http','settle-dto'),e('settle-dto','settle-controller'),e('settle-controller','settle-domain'),e('settle-domain','settle-tx','SettlementAggregate'),e('settle-tx','settle-db'),e('settle-db','task','NotifyTaskEntity','async'),e('task','port-impl'),e('port-impl','notify'),e('notify','task','失败重试 / job','async','right'),
    e('refund-entry','refund-dto'),e('refund-dto','refund-trigger'),e('refund-trigger','refund-domain'),e('refund-domain','refund-strategy'),e('refund-strategy','refund-tx','GroupBuyRefundAggregate'),e('refund-tx','refund-mq','TeamRefundSuccess','async'),e('refund-mq','refund-listener','消费','async'),e('refund-listener','redis-recover','最终一致恢复','async')
  ],
  cards: [
    { kicker:'DEPENDENCY INVERSION', title:'Domain 拥有接口，Infra 提供实现', tone:'data', items:['Repository / Port 接口位于 Domain 边界；ActivityRepository / TradeRepository / TradePort 位于 Infrastructure','跨层对象：MarketProductEntity / TrialBalanceEntity · OrderAggregate / PayOrderEntity · SettlementAggregate / NotifyTaskEntity · RefundAggregate / TeamRefundSuccess','Controller 不直接连接 DAO'] },
    { kicker:'DATA & EXTERNALS', title:'数据资源与旁路边界', tone:'boundary', items:['MySQL：sku/sc_sku_activity/activity/discount/order/order_list/notify_task/crowd_tags*','Redis：配置缓存、DCC、BitSet、团队名额计数/幂等锁；RabbitMQ：成团与退单恢复；OkHttp：callback','Dify proxy 是 MarketIndexController 旁路，不属于拼团核心链'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['根 pom 与各模块 pom · MarketIndexController · MarketTradeController','IndexGroupBuyMarketServiceImpl · Lock/Settlement/Refund services','ActivityRepository · TradeRepository · TradePort'] }
  ]
}),

withNumbers({
  code: 'GBM-02', project: '以牌惠友 / group-buy-market', file: 'group-buy-market-market-trial.html',
  title: '首页营销配置查询与优惠试算',
  subtitle: '从接口限流和 DCC 开关，经 FutureTask 并行加载活动/折扣/SKU、折扣策略与人群标签判断，到队伍/统计补充并组装 GoodsMarketResponseDTO。',
  tags: ['pure query / calculation', 'XFG wrench strategy tree', 'FutureTask × 2', 'cache-aside + BitSet', 'no stock deduction'],
  lanes: [
    { name:'浏览器 / Controller', role:'request · validation · response', color:'#34d399' },
    { name:'策略树', role:'Root · Switch · Market · Tag · End', color:'#a78bfa' },
    { name:'仓储与并行查询', role:'FutureTask · cache · DB', color:'#fbbf24' },
    { name:'返回组装', role:'teams · stats · DTO', color:'#22d3ee' }
  ],
  phases: [
    { row:0,title:'A · 入口限流与 DCC',stroke:'#34d399' },
    { row:7,title:'B · MarketNode 并行加载与折扣计算',stroke:'#a78bfa' },
    { row:17,title:'C · 标签约束、队伍统计与响应',stroke:'#22d3ee' }
  ],
  steps: [
    s('request',0,0,'start','POST query_group_buy_market_config','userId · source · channel · goodsId'),
    s('rate',0,1,'decision','RateLimiter 通过？','1 req/s · blacklistCount=1'),
    s('params',0,2,'decision','核心参数合法？','非法 → ILLEGAL_PARAMETER'),
    s('entity',0,3,'process','构建 MarketProductEntity','调用 indexMarketTrial'),
    s('root',1,4,'process','RootNode','再次校验并路由'),
    s('switch',1,5,'decision','DCC 是否放行？','downgradeSwitch / cutRange(userId)'),
    s('market',1,7,'process','MarketNode.multiThread','当前实际 bean：FutureTask 版本'),
    s('activity-task',2,8,'process','FutureTask A · 活动与折扣','必要时 source+channel+goodsId → activityId'),
    s('sku-task',2,9,'process','FutureTask B · SKU','goodsId 直查 SKU'),
    s('cache',2,10,'storage','cache-aside / MySQL','活动与折扣可缓存；SKU 当前直查 DB'),
    s('timeout',1,11,'decision','两个任务 5000ms 内完成？','结果写 DynamicContext'),
    s('exists',1,12,'decision','活动 / 折扣 / SKU 齐全？','缺失 → ErrorNode E0002'),
    s('tag-discount',2,13,'decision','discountType = TAG？','先查 Redis RBitSet 人群'),
    s('strategy',1,14,'decision','折扣策略存在？','ZJ / ZK / MJ / N'),
    s('calculate',1,15,'process','计算优惠与支付价','非目标人群 payPrice = originalPrice'),
    s('context',1,16,'storage','写 DynamicContext','activityDiscount · sku · deductionPrice · payPrice'),
    s('tag-node',1,17,'process','TagNode 可见 / 可参与','configuredVisible/Enable || isWithin'),
    s('end-node',1,18,'end','EndNode → TrialBalanceEntity','visible · enable'),
    s('owner-team',2,19,'process','查当前用户进行中队伍','ownerCount=1'),
    s('random-team',2,20,'process','查其他进行中队伍','先查 2 倍 → shuffle → 截 randomCount=2'),
    s('team-filter',2,21,'storage','队伍查询与过滤','list → order；status=0 · 未满 · 未过期'),
    s('stat',2,22,'process','按 activityId 汇总','allTeamCount · complete count · lock_count 总人数'),
    s('response',3,23,'end','组装 GoodsMarketResponseDTO','价格 · team + countdown · TeamStatistic'),
    s('fallback',0,24,'decision','统一失败响应','RATE_LIMITER / E0001~4 / UN_ERROR')
  ],
  edges: [
    e('request','rate'),e('rate','params','通过','success'),e('rate','fallback','拒绝','error'),e('params','entity','合法','success'),e('params','fallback','非法','error'),e('entity','root'),e('root','switch'),e('switch','market','放行','success'),e('switch','fallback','E0003 / E0004','error'),
    e('market','activity-task','并行','async'),e('market','sku-task','并行','async'),e('activity-task','cache'),e('sku-task','cache'),e('cache','timeout'),e('timeout','exists','完成','success'),e('timeout','fallback','超时','error'),e('exists','tag-discount','齐全','success'),e('exists','fallback','E0002','error'),
    e('tag-discount','strategy','人群价 / 原价'),e('strategy','calculate','命中','success'),e('strategy','fallback','E0001','error'),e('calculate','context'),e('context','tag-node'),e('tag-node','end-node'),e('end-node','owner-team'),e('owner-team','random-team'),e('random-team','team-filter'),e('team-filter','stat'),e('stat','response')
  ],
  cards: [
    { kicker:'DYNAMIC CONTEXT', title:'策略树共享状态', tone:'data', items:['activityDiscount · sku · deductionPrice · payPrice · visible · enable','AbstractMultiThreadStrategyRouter.apply 负责 multiThread / doApply / router','MarketNode 使用两个 FutureTask，超时 5000ms'] },
    { kicker:'FACT BOUNDARY', title:'纯查询与计算，不扣名额', tone:'boundary', items:['首页试算不会占 Redis 团队名额，也不会创建 group_buy_order/list','MarketNode2CompletableFuture 未启用 @Service，只是替代示例','活动/折扣 cache-aside；SKU 当前直查 DB；标签读取 Redis RBitSet'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['MarketIndexController.queryGroupBuyMarketConfig · DefaultActivityStrategyFactory','RootNode · SwitchNode · MarketNode · TagNode · EndNode / ErrorNode','四个 DiscountCalculateService · ActivityRepository · mapper XML'] }
  ]
}),

withNumbers({
  code: 'GBM-03', project: '以牌惠友 / group-buy-market', file: 'group-buy-market-trade-lifecycle.html',
  title: '拼团交易全生命周期',
  subtitle: '锁单（开团/参团）、支付结算与成团、本地消息通知、三类逆向退单及超时补偿的端到端状态时间线。',
  tags: ['idempotent lock', 'lock_count ≠ complete_count', 'transactional outbox', '3 refund strategies', 'scheduled compensation'],
  lanes: [
    { name:'上游商城', role:'lock · settle · refund', color:'#34d399' },
    { name:'Controller / Domain', role:'validation · rules · aggregates', color:'#a78bfa' },
    { name:'MySQL 事务', role:'team · order list · notify_task', color:'#fbbf24' },
    { name:'Redis', role:'team slot reservation · locks', color:'#22d3ee' },
    { name:'通知任务 / MQ', role:'HTTP · RabbitMQ · jobs', color:'#c084fc' }
  ],
  phases: [
    { row:0,title:'A · 锁单：开团 / 参团',stroke:'#22d3ee' },
    { row:14,title:'B · 支付结算与成团通知',stroke:'#34d399' },
    { row:25,title:'C · 逆向退单与超时补偿',stroke:'#fb7185' },
    { row:38,title:'STATE MACHINES · 三套独立状态',stroke:'#fbbf24' }
  ],
  steps: [
    s('lock-req',0,0,'start','POST lock_market_pay_order','HTTP notify 必须提供 notifyUrl'),
    s('lock-valid',1,1,'decision','参数合法？','userId · outTradeNo · activity / goods'),
    s('idempotent',2,2,'decision','outTradeNo 已有 CREATE 明细？','有则返回已有 order / 价格'),
    s('team-full',1,3,'decision','老团已满？','targetCount == lockCount → E0006'),
    s('trial',1,4,'process','重新执行营销试算','复用 GBM-02 主链'),
    s('allowed',1,5,'decision','visible 且 enable？','任一 false → E0007'),
    s('lock-rules',1,6,'process','锁单规则责任链','活动有效 → 用户 takeLimit → TeamStockOccupy'),
    s('new-team?',1,7,'decision','teamId 为空？','新团不做 Redis 预占'),
    s('reserve',3,8,'integration','预占老团队伍名额','失败 E0008 · 详见 GBM-04'),
    s('aggregate',1,9,'process','构建 GroupBuyOrderAggregate','进入 repository 事务'),
    s('team-write',2,10,'decision','新团还是老团？','新团 insert；老团条件 lock_count + 1'),
    s('team-db',2,11,'storage','写 group_buy_order','新团 lock_count=1 / 老团 where lock_count<target'),
    s('list-db',2,12,'storage','插入 group_buy_order_list','status=CREATE · orderId · outTradeNo · bizId'),
    s('lock-result',1,13,'decision','DB 锁单成功？','唯一键 / update / insert 异常'),
    s('sync-recover',3,13,'process','同步 recoveryCount +1','仅已预占老团；抵消失败占位'),

    s('settle-req',0,14,'start','POST settlement_market_pay_order','携带 outTradeTime'),
    s('settle-rules',1,15,'process','结算责任链','SC 黑名单 → 单据存在未关闭 → outTradeTime < validEnd'),
    s('snapshot',1,16,'process','EndFilter 返回 team snapshot','进入事务前 targetCount / completeCount'),
    s('list-complete',2,17,'storage','明细 CREATE → COMPLETE','写 outTradeTime'),
    s('complete-inc',2,18,'storage','团队 complete_count + 1','where complete_count < target_count'),
    s('last?',1,19,'decision','最后一名支付者？','snapshot: targetCount - completeCount == 1'),
    s('team-complete',2,20,'storage','团队 PROGRESS → COMPLETE','查本团所有 COMPLETE outTradeNo'),
    s('notify-task',2,21,'storage','同事务插入 notify_task','category=trade_settlement · WAIT(0)'),
    s('notify-now',4,22,'process','TradeTaskService 立即异步','task lockKey · HTTP / MQ'),
    s('notify-ok',4,23,'decision','通知成功？','成功 1；失败 2；notifyCount>4 → 3'),
    s('notify-job',4,24,'process','GroupBuyNotifyJob','扫描 WAIT / RETRY 再补偿'),

    s('refund-entry',0,25,'start','HTTP 退单 / TimeoutRefundJob','定时锁 + 最多 10 条超时 CREATE'),
    s('refund-load',1,26,'process','加载 PayOrder + Team','DataNodeFilter'),
    s('closed?',1,27,'decision','明细已 CLOSE？','直接返回 REPEAT'),
    s('choose-refund',1,28,'decision','团队状态 + 明细状态？','选择三种 refund strategy'),
    s('unpaid',1,29,'process','Unpaid2Refund','PROGRESS + CREATE'),
    s('paid-unformed',1,30,'process','Paid2Refund','PROGRESS + COMPLETE'),
    s('paid-formed',1,31,'process','PaidTeam2Refund','COMPLETE / COMPLETE_FAIL + COMPLETE'),
    s('refund-tx',2,32,'storage','退单 MySQL 事务','明细 CLOSE；团队计数/状态；写 notify_task'),
    s('refund-notify',4,33,'process','立即执行或任务补偿','MQ TeamRefundSuccess'),
    s('refund-listener',4,34,'process','RefundSuccessTopicListener','restoreTeamLockStock'),
    s('restore?',3,35,'decision','退单发生在未成团阶段？','unpaid_unlock / paid_unformed 才恢复'),
    s('redis-restore',3,36,'storage','Redis recoveryCount +1','幂等 refund_lock_{orderId}'),
    s('formed-no',3,37,'note','paid_formed 不恢复 Redis','队伍生命周期已结束，不再接纳成员'),

    s('list-state',1,38,'note','明细状态机','CREATE(0) → COMPLETE(1) → CLOSE(2) 或 CREATE → CLOSE'),
    s('team-state',2,39,'note','团队状态机','PROGRESS(0) → COMPLETE(1) → COMPLETE_FAIL(3) / FAIL(2)'),
    s('task-state',4,40,'note','notify_task 状态机','WAIT(0) → SUCCESS(1) / RETRY(2) → ERROR(3)'),
    s('business-error',1,41,'decision','业务错误出口','E0005/6/7/8 · E0104/5/6 · INDEX_EXCEPTION')
  ],
  edges: [
    e('lock-req','lock-valid'),e('lock-valid','idempotent','合法','success'),e('lock-valid','business-error','非法','error'),e('idempotent','team-full','不存在'),e('idempotent','lock-result','已有 CREATE · 幂等成功','success'),e('team-full','trial','未满','success'),e('team-full','business-error','E0006','error'),
    e('trial','allowed'),e('allowed','lock-rules','允许','success'),e('allowed','business-error','E0007','error'),e('lock-rules','new-team?'),e('new-team?','aggregate','新团'),e('new-team?','reserve','老团'),e('reserve','aggregate','预占成功','success'),e('reserve','business-error','E0008','error'),e('aggregate','team-write'),e('team-write','team-db'),e('team-db','list-db'),e('list-db','lock-result'),e('lock-result','sync-recover','失败且已预占','error'),e('sync-recover','business-error','抛错','error'),
    e('settle-req','settle-rules'),e('settle-rules','snapshot'),e('settle-rules','business-error','E0104/5/6','error'),e('snapshot','list-complete'),e('list-complete','complete-inc'),e('complete-inc','last?'),e('last?','team-complete','是','success'),e('last?','list-state','否 · 只完成本名额'),e('team-complete','notify-task'),e('notify-task','notify-now','事务提交后','async'),e('notify-now','notify-ok'),e('notify-ok','task-state','成功'),e('notify-ok','notify-job','失败','async'),e('notify-job','notify-now','重试','async','right'),
    e('refund-entry','refund-load'),e('refund-load','closed?'),e('closed?','choose-refund','否'),e('closed?','list-state','是 · REPEAT'),e('choose-refund','unpaid','PROGRESS+CREATE'),e('choose-refund','paid-unformed','PROGRESS+COMPLETE','main','right'),e('choose-refund','paid-formed','已成团+COMPLETE','main','left'),e('unpaid','refund-tx','未支付','main','right'),e('paid-unformed','refund-tx','已支付未成团','main','right'),e('paid-formed','refund-tx','已成团'),e('refund-tx','refund-notify','事务提交后','async'),e('refund-notify','refund-listener','MQ','async'),e('refund-listener','restore?'),e('restore?','redis-restore','未成团','success'),e('restore?','formed-no','已成团'),e('redis-restore','list-state'),e('formed-no','team-state')
  ],
  cards: [
    { kicker:'COUNT SEMANTICS', title:'两个团队计数不能混用', tone:'data', items:['lock_count：已成功创建锁单明细的名额数；锁单时增加，退单时减少','complete_count：已完成支付的名额数；结算时增加，已支付退单时减少','是否最后支付者使用事务前 team snapshot 判断'] },
    { kicker:'CONSISTENCY', title:'事务、本地消息与补偿', tone:'boundary', items:['团队/明细状态与 notify_task 同一 MySQL 事务','通知先立即异步尝试，失败由 GroupBuyNotifyJob 扫 WAIT/RETRY','TimeoutRefundJob 处理超时未支付 CREATE；未成团退单才最终恢复 Redis 名额'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['MarketTradeController · 三个 Trade service / factory / filter','TradeRepository · TradeTaskService · TradePort','GroupBuyNotifyJob · TimeoutRefundJob · 三个 mapper XML'] }
  ]
}),

withNumbers({
  code: 'GBM-04', project: '以牌惠友 / group-buy-market', file: 'group-buy-market-team-stock.html',
  title: '团队名额库存占用与恢复',
  subtitle: '团队容量、Redis 老团并发预占序号、MySQL lock_count 最终确认和 recoveryCount 补偿模型之间的真实关系；这不是 SKU 实物库存。',
  tags: ['team slots ≠ SKU stock', 'occupy = INCR + 1', 'MySQL final guard', 'recovery expands ceiling', 'MQ eventual recovery'],
  lanes: [
    { name:'锁单规则 / 业务服务', role:'TeamStockOccupyRuleFilter', color:'#34d399' },
    { name:'Redis 预占与恢复', role:'teamStockKey · recovery key', color:'#22d3ee' },
    { name:'MySQL 最终确认', role:'group_buy_order / order_list', color:'#fbbf24' },
    { name:'本地消息 / MQ', role:'notify_task · listener', color:'#c084fc' }
  ],
  phases: [
    { row:0,title:'DEFINITION · 三个量与核心公式',stroke:'#64748b' },
    { row:4,title:'A · 首次开团与老团占用',stroke:'#22d3ee' },
    { row:16,title:'B · 同步失败补偿',stroke:'#fb7185' },
    { row:20,title:'C · 业务退单后的最终一致恢复',stroke:'#a78bfa' },
    { row:31,title:'EXAMPLE · target = 3 时间线',stroke:'#34d399' }
  ],
  steps: [
    s('target',0,0,'note','target_count','团队目标人数 / 容量'),
    s('lock-count',2,0,'note','MySQL lock_count','成功创建锁单明细的名额数；新团初始 1'),
    s('keys',1,1,'storage','Redis 两个 key','teamStockKey 与 teamStockKey_recovery'),
    s('formula',1,2,'note','核心公式','occupy = INCR(teamStockKey) + 1'),
    s('ceiling',1,3,'note','允许条件','occupy ≤ target + recoveryCount'),

    s('team-id',0,4,'decision','teamId 为空？','首次开团 vs 加入老团'),
    s('new-pass',0,5,'process','首次开团直接通过','不操作 Redis'),
    s('new-db',2,6,'storage','插入新团队','lock_count = 1'),
    s('read-recovery',1,7,'process','读取 recoveryCount','key 不存在按 0'),
    s('incr',1,8,'integration','原子 INCR teamStockKey','源码随后额外 +1 得 occupy'),
    s('over?',1,9,'decision','occupy 超过上限？','occupy > target + recoveryCount'),
    s('over-error',0,10,'decision','E0008 超限','INCR 已发生，源码不回减'),
    s('lock-key',1,11,'process','构造唯一兜底 lockKey','teamStockKey_{occupy}'),
    s('setnx',1,12,'decision','SETNX 成功？','TTL = validTime + 60 分钟'),
    s('mysql-update',2,13,'storage','条件更新 lock_count + 1','where team_id=? and lock_count<target_count'),
    s('affected',2,14,'decision','影响行数 = 1？','MySQL 是最终并发防线'),
    s('insert-list',2,15,'storage','插入锁单明细','DB 最终确认完成'),

    s('db-fail',0,16,'decision','update / insert / 唯一键失败？','Redis 已预占，DB 未确认'),
    s('sync-recovery',1,17,'process','recoveryTeamStock +1','TradeLockOrderService.catch 同步补偿'),
    s('new-skip',0,18,'note','新团跳过恢复 key','首次开团没有 Redis 预占'),
    s('lock-success',0,19,'end','锁单成功','lock_count 已确认；名额不是支付时才扣'),

    s('refund-tx',2,20,'storage','退单 MySQL 事务','明细 CLOSE；lock_count -1；必要时 complete_count -1'),
    s('outbox',3,21,'storage','同事务写 notify_task','发布 TeamRefundSuccess'),
    s('publish',3,22,'integration','TradeTaskService → RabbitMQ','失败由本地消息表补偿'),
    s('listener',3,23,'process','RefundSuccessTopicListener','restoreTeamLockStock'),
    s('refund-type',0,24,'decision','退款类型？','unpaid_unlock / paid_unformed / paid_formed'),
    s('formed',0,25,'note','paid_formed','只改 MySQL；不恢复 Redis'),
    s('refund-lock',1,26,'decision','refund_lock_{orderId} SETNX？','消费幂等；异常删除锁并让 MQ 重试'),
    s('ttl-audit',1,27,'note','TTL 需要单独审计','意图 30 天；实际传值 / TimeUnit 不标成已修复'),
    s('async-recovery',1,28,'storage','recoveryCount +1','未成团两类最终恢复'),
    s('consistent',0,29,'end','名额最终一致','DB 已减；Redis 可接受上限扩大'),

    s('example-1',0,31,'note','① 开团者','DB lock=1'),
    s('example-2',1,32,'note','② 第一位参团者','INCR=1 → occupy=2 → DB lock=2'),
    s('example-3',1,33,'note','③ 第二位参团者','occupy=3 → DB lock=3'),
    s('example-4',1,34,'note','④ 下一请求','occupy=4 > 3 → 拒绝'),
    s('example-5',1,35,'note','⑤ 若前一 DB 失败','recovery=1 → 后续上限变为 4'),
    s('example-6',3,36,'note','⑥ 未成团退单','DB lock-1；outbox + MQ → recovery+1')
  ],
  edges: [
    e('target','formula'),e('lock-count','formula'),e('keys','formula'),e('formula','ceiling'),e('ceiling','team-id'),
    e('team-id','new-pass','是'),e('new-pass','new-db'),e('new-db','lock-success'),e('team-id','read-recovery','否 · 老团'),e('read-recovery','incr'),e('incr','over?'),e('over?','over-error','超限','error'),e('over?','lock-key','未超限','success'),e('lock-key','setnx'),e('setnx','mysql-update','成功','success'),e('setnx','over-error','失败','error'),e('mysql-update','affected'),e('affected','insert-list','= 1','success'),e('affected','db-fail','≠ 1','error'),e('insert-list','db-fail'),e('db-fail','lock-success','成功'),e('db-fail','sync-recovery','失败且老团','error'),e('db-fail','new-skip','失败且新团','error'),
    e('sync-recovery','lock-success','补偿后抛错','async'),e('new-skip','lock-success','抛错','error'),e('lock-success','refund-tx','后续发生业务退单','async'),e('refund-tx','outbox'),e('outbox','publish','事务提交后','async'),e('publish','listener','MQ','async'),e('listener','refund-type'),e('refund-type','formed','paid_formed'),e('refund-type','refund-lock','未成团两类','main','right'),e('refund-lock','ttl-audit','SETNX 成功','success'),e('ttl-audit','async-recovery'),e('async-recovery','consistent'),e('formed','consistent'),
    e('example-1','example-2'),e('example-2','example-3'),e('example-3','example-4'),e('example-4','example-5','若失败补偿','async'),e('example-5','example-6','若业务退单','async')
  ],
  cards: [
    { kicker:'FORMULA', title:'Redis 计数不是数据库库存镜像', tone:'data', items:['occupy = INCR(teamStockKey) + 1；+1 对应已占首位的开团者','允许 occupy ≤ target + recoveryCount','recoveryCount 不回滚 INCR，而是扩大可接受预占上限'] },
    { kicker:'CONSISTENCY & RISK', title:'两道防线与恢复边界', tone:'boundary', items:['Redis 只保护老团并发入口；MySQL 条件更新是最终防线','notify_task 保证 MQ 可补偿；refund_lock_{orderId} 防止重复恢复','paid_formed 不恢复；SETNX TTL 的传值 / TimeUnit 按源码标记为待审计'] },
    { kicker:'SOURCE', title:'源码依据', tone:'source', items:['TeamStockOccupyRuleFilter · TradeLockRuleFilterFactory','TradeRepository.occupyTeamStock / recoveryTeamStock / refund2AddRecovery','group_buy_order_mapper.xml · refund strategies · RefundSuccessTopicListener · TradeTaskService'] }
  ]
})
];
