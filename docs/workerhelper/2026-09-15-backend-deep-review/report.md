# ZhiMesh 后端深度检查报告：知识库链路 + 角色 Agentic 循环

日期：2026-09-15
方法：两个只读走查代理分别覆盖「上传→切分→三索引构建」与「检索融合→Agentic 工具循环」，主线程对全部头条结论逐一读码复核，并用 dev 库数据交叉验证。本文所有 file:line 均经核实或直接引用走查报告原文。

---

## 0. 总体判断

架构成熟度高于"demo"水位：canonical chunk 让三个索引共享同一切块快照（CAS 围栏防 stale 发布）、BM25 是版本化自研倒排（analyzer 版本强制重建）、图谱走原子 release 切换、检索层有 RRF 融合 + 三层去重 + 相关门 + token 预算装填、工具循环有迭代上限/优雅收尾轮/超时/截断/递归防护/轨迹落库/跨轮 token 入账。**真正的问题集中在五类：状态机恢复不对称、计量漏账、错误语义错位、删除清理不彻底、多消费者配置不一致。**

## 1. 先澄清三个 DB 异象（都不是 bug）

| 异象 | 结论 |
|---|---|
| 35 个角色全部 `is_agentic=true`（设计文档承诺默认关） | 迁移 `042_character_agentic_default_on.sql` 主动翻转：注释写明"无工具角色行为与普通对话一致"。**设计文档没同步更新**，属文档漂移而非实现错误 |
| `adi_character_message_tool_call` 0 行（消息表 1711 行） | 升级后仅 5 轮问答，全部无需工具：2 轮 KB 问题预检索已命中（`is_ref_embedding/is_ref_bm25=true`，上下文已备好，模型无需再调 `search_knowledge`）、2 轮"你是什么模型"、1 轮"你能实现什么功能"。落库链路 `saveToolCallTraces`（CharacterChatService:1056）在主路径上且被测试覆盖，接线正确 |
| `index_build` 表只有 FULLTEXT 行 | 不是缺失：BM25 用它做构建版本化，图谱有 `graph_release` 原子发布、向量有 chunk_set 指针机制，三者各有版本化方案 |

注意：**:9999 后端当前未运行**（无 java 进程、端口无监听）。最新构建是否含 agentic 代码无法从进程确认——建议重启后用财务报销助手实测一轮（如"帮我查报销制度"，该角色 kb 为空、mcp=17，政策问题必然走工具），`tool_call` 表应出现轨迹行，同时验证端到端。

## 2. A 类：缺失逻辑 / 正确性缺陷（建议修）

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| A1 | **向量/全文索引 DOING 无崩溃恢复**。恢复 Job 只查 `graphical_status=DOING`（failTimedOutGraphIndexing），其 javadoc 自己论证了"DOING 守卫会永久卡死"的必要性，但 embedding/fulltext 没有同等恢复；重启/崩溃后这两类条目被 planIndexRequests 的 DOING 守卫永久跳过，只能手工改库 | KnowledgeBaseItemService:744-762（仅图谱）vs :432-436（守卫）；GIRJ 每分钟跑，模式现成 | 直接违背"正常稳定使用"底线 |
| A2 | **run_workflow 内部 LLM 消耗不入用户账本**。WorkflowUtil.streamingInvokeLLM 只 cacheTokenUsage + saveLLMCallRecord(WORKFLOW_NODE)，全仓 `appendCostToUser` 8 处调用无工作流路径；且工作流跑在 dummy sseUuid 下，token 也不会聚进外层聊天的 answer meta。设计验收标准 4 明确承诺"计入发起用户 quota" | WorkflowUtil.streamingInvokeLLM；grep 验证 | 用户经角色触发长工作流 = 配额白嫖 |
| A3 | **阻塞路径工具到上限抛 `B_LLM_SERVICE_DISABLED`**。明明是"迭代上限"，报的却是"服务禁用"；流式路径同条件走优雅收尾轮（剥工具规格+指令再答一轮）。LocalAgentService（工作流 AgentNode）、blockingAsk 等走阻塞路径 | AbstractLLMService:486-489 vs :320-359 | 错误语义错位 + 双路径行为不一致 |
| A4 | **条目内容编辑后旧向量残留**。内容变化立即删 BM25（注释明说"must stop serving immediately"），但旧向量要等下次索引开始才删（indexingEmbedding:463）——编辑到重建索引的窗口期内，向量通道返回旧内容、BM25 通道静默，混合检索两通道不一致 | KnowledgeBaseItemService:152-170 | 正确性：用户改了文档却检索出旧话 |
| A5 | **删库不清物理数据**。softDelete 只删 KB 行 + 路由画像 + 图谱（尽力清理），items / pgvector 向量 / BM25 postings / canonical chunks 全部残留为孤儿（检索不可达但占存储） | KnowledgeBaseService:797-833 | 数据卫生，长期累积 |

## 3. B 类：冗余 / 不一致

| # | 问题 | 位置 |
|---|---|---|
| B1 | 四处手写 RetrieverCreateParam 拼装（KB QA 异步/阻塞、角色聊天、工作流节点各自成型）；且角色聊天 KB 检索 `maxResults` 写死 3，不复用 `kb.retrieveMaxResults` 或自适应——**同一个库在问答页和角色聊天召回行为不同** | KnowledgeBaseService:1585/:1040；CharacterChatHelper:264-280（:272 写死 3）；KnowledgeRetrievalNode |
| B2 | rerank 策略不一致：KB QA 必须显式配 rerankModelId 才启用；角色聊天在未配置时自动选"首个启用的 RERANK 模型" | KnowledgeBaseService:1772 vs CharacterChatHelper.resolveReranker |
| B3 | 单文件上传把索引锁忙 `A_DOC_INDEX_DOING` 等一切异常统一包成 `A_UPLOAD_FAIL`——文件与条目实际已落库，用户被告知"上传失败" | KnowledgeBaseService:478-481 |
| B4 | 控制器与 Service 双重权限查询（saveOrUpdate / info 两处） | KnowledgeBaseController:39-64 + KnowledgeBaseItemService:129/:788 |
| B5 | 上下文预算两套常量并存（Retrieval 4000/1800/1000/8% 与 Conversation 8192/2048/8%），语义边界无注释说明 | ZhiMeshProperties 两块 |
| B6 | 死列：`adi_knowledge_base_item.embedding_model_id` 全链路无写入点 | 实体 :78-79 |
| B7 | 设计文档 7 处漂移：默认开关相反、WorkflowStarter 签名不同（无 timeout 形参）、"K 轮降级摘要"未实现（只有单结果截断）、包名 `agent/tool/` 实为 `languagemodel/tool/`、BuiltInToolRegistry 不存在（内联组装）、M2/M4 无实现痕迹 | docs/.../2026-09-13-character-agentic-upgrade/design.md |
| B8 | isAgentic 角色先无条件前置检索、工具循环内 `search_knowledge` 可对同一问题再检索一次（hybrid 设计使然，可辩护）；优化空间是把预检索结果注入工具轮，避免同问双检索 | CharacterChatService:351 + SearchKnowledgeTool:194 |

## 4. C 类：可优化（性能 / 健壮性）

| # | 问题 | 位置 |
|---|---|---|
| C1 | **MCP 工具无执行超时/截断/取消**——内置工具有 60s+4000 字符+cancel 护栏，MCP 直调裸奔（有测试明示该差异）；stdio MCP 挂起 = 聊天线程无限等待 | AbstractLLMService:787-835 MCP 分支 |
| C2 | MCP 客户端每请求新建；stdio 传输即每请求新子进程（每条消息一次进程启动） | UserMcpService:186-236 |
| C3 | TOOL_EXECUTION_EXECUTOR 静态无界 cachedThreadPool | AbstractLLMService:85-89 |
| C4 | 索引 Redis 锁 10min TTL 提交后不续期；长图谱任务（120s×3 重试×多段）可超 10min，锁先消失（CAS 大部分兜底） | KnowledgeBaseItemService:261-262 |
| C5 | 图 ingest 每个 segment 循环内 `searchVertices(limit=10000)` 拉全库顶点快照 | GraphStoreIngestor:169-173 |
| C6 | GRAPH 路由锚点空时每次请求触发 LLM 实体抽取兜底（检索内嵌 LLM 调用：延迟+费用） | GraphStoreContentRetriever |
| C7 | embedding 全量 `embedAll/addAll` 无分批、无重试（本地 ONNX 无害；换远程模型时单请求体量不受控） | EmbeddingRag:71-75 |
| C8 | softDelete 事务内入队异步清图，不感知外层事务回滚（回滚时图谱贡献已被删） | KnowledgeBaseItemService:684-704 |
| C9 | BgeReranker 熔断器 static 共享（键 baseUrl|model），跨用户跨 KB 一个坏实例连坐 | BgeReranker CIRCUITS |
| C10 | snapshot=null（canonical 关闭或降级）时 CAS 围栏整体失效（默认 canonical 开启，影响面小） | KnowledgeBaseItemService:450-457 等 |
| C11 | scope-gate 预取向量管道（prefetchedVectorContents/scopeEmbeddings）已建未接线，KB QA 主链路探针仍自行 embed | KnowledgeScopePreflightGate |

## 5. D 类：可增强（能力方向）

| # | 方向 | 说明 |
|---|---|---|
| D1 | **Markdown/结构感知切分** | 现状 md/pdf/docx 解析即丢标题层级，切分器（recursive/paragraph/line/sentence/custom 5 种）全部不感知结构——段落腰斩语义。对中文制度文档 QA 质量是最大单项杠杆 |
| D2 | 每库 embedding 模型可配 | 现为全局单例；换模型 = 按维度自动换新表，旧维度表成不可达孤儿（统计也不含）。至少补"旧表清理/导出"工具 |
| D3 | run_workflow 参数投递 | 只取工作流首个 TEXT 参数硬截断，其余参数类型不投递（描述里标注"手动运行"） |
| D4 | 工作流检索节点对齐 | KnowledgeRetrievalNode 完全绕过 scope-gate/意图路由/query 改写，三个检索消费者语义不一致的最小对齐项 |
| D5 | 索引状态可视化 | 三状态列（embedding/graphical/fulltext）前端已有单行 Tag+重试；可补构建历史/build 级进度（index_build 仅 BM25 在用） |

## 6. 建议实施顺序

1. **第一批（正确性，小改动大收益）**：A1（把 GIRJ 模式扩到 embedding/fulltext）→ A3（阻塞路径对齐优雅收尾或至少换正确错误码）→ A2（run_workflow 收尾时 appendCostToUser）→ A4（编辑时同步删向量）
2. **第二批（一致性/体验）**：B1（maxResults 收敛到 kb 配置+抽出公共建参）→ B3（上传错误分流）→ C1（MCP 执行超时）
3. **第三批（健壮性/卫生）**：A5、C2、C4、C7
4. **方向项另立项**：D1 结构感知切分（需要效果评测配套）

## 附：主线程亲自复核过的结论

- A1（读 failTimedOutGraphIndexing + planIndexRequests）、A2（grep appendCostToUser 全仓 8 处）、A3（读 innerChatWithDepth:484-516）、A4（读 saveOrUpdate:145-176）、A5（读 softDelete:797-833）、B1（读 CharacterChatHelper:264-280）
- 工具循环本体（innerStreamingChat:319-434：上限/收尾轮/中间轮 token/MCP 关闭三路径/截断标注入轨迹）
- 落库接线（saveToolCallTraces:1056 调用点在 createRef 之后必经）+ 迁移 041/042 全文
- DB 侧：升级后 5 轮消息明细（含角色、mcp/kb 绑定、ref 标记）、:9999 进程不存在
- 其余条目引自两份走查报告（agent 报告原文未逐条重读，行号以报告为准）
