# 项目流程图提示词索引

本目录保存基于当前工作区源码整理的流程图生成提示词；对应的 10 张自包含 HTML + 内联 SVG 流程图已按统一主题生成在 [`outputs/flow-diagrams`](../../outputs/flow-diagrams/index.html)。后续源码变更后，应先复核提示词，再重新生成并预览。

## 提示词清单

### ZhiMesh（智枢）

文件：[zhimesh-prompts.md](./zhimesh-prompts.md)

| 编号 | 图名 | 核心范围 |
|---|---|---|
| ZM-01 | 完整聊天过程 | Vue 输入、SSE、会话边界、RAG/记忆、LLM、消息与引用落库 |
| ZM-02 | RAG 索引建立 | 文档上传解析、异步任务、向量索引、知识图谱索引、状态与清理 |
| ZM-03 | RAG 在线检索 | 向量/图谱双路召回、并行容错、RRF/重排、预算装箱、提示词增强 |
| ZM-04 | MCP 完整生命周期 | 管理端定义、用户配置、客户端初始化、工具发现、递归工具调用、关闭 |
| ZM-05 | 工作流定义与编译 | 节点/边持久化、编译树、并行子图、条件边、中断点、LangGraph4j 编译 |
| ZM-06 | 工作流运行与恢复 | SSE 执行、节点输入输出、运行记录、流式结果、人工反馈暂停/恢复、完成/失败 |

### 以牌惠友（group-buy-market）

文件：[group-buy-market-prompts.md](./group-buy-market-prompts.md)

| 编号 | 图名 | 核心范围 |
|---|---|---|
| GBM-01 | 分层调用链路 | 客户端到 Trigger、Domain、Repository、MySQL/Redis/MQ/HTTP 的真实依赖链 |
| GBM-02 | 首页营销试算 | 限流、策略树、DCC、并行加载、折扣策略、人群标签、队伍与统计查询 |
| GBM-03 | 拼团交易全生命周期 | 幂等锁单、责任链、开团/参团、支付结算、成团通知、退单与定时补偿 |
| GBM-04 | 团队库存占用与恢复 | Redis 预占、MySQL lock_count、失败补偿、退单本地消息、MQ 幂等恢复 |

## 已生成图册

统一入口：[outputs/flow-diagrams/index.html](../../outputs/flow-diagrams/index.html)

| 编号 | HTML 输出 |
|---|---|
| ZM-01 | [zhimesh-chat-process.html](../../outputs/flow-diagrams/zhimesh-chat-process.html) |
| ZM-02 | [zhimesh-rag-indexing.html](../../outputs/flow-diagrams/zhimesh-rag-indexing.html) |
| ZM-03 | [zhimesh-rag-retrieval.html](../../outputs/flow-diagrams/zhimesh-rag-retrieval.html) |
| ZM-04 | [zhimesh-mcp-lifecycle.html](../../outputs/flow-diagrams/zhimesh-mcp-lifecycle.html) |
| ZM-05 | [zhimesh-workflow-compile.html](../../outputs/flow-diagrams/zhimesh-workflow-compile.html) |
| ZM-06 | [zhimesh-workflow-runtime.html](../../outputs/flow-diagrams/zhimesh-workflow-runtime.html) |
| GBM-01 | [group-buy-market-call-chain.html](../../outputs/flow-diagrams/group-buy-market-call-chain.html) |
| GBM-02 | [group-buy-market-market-trial.html](../../outputs/flow-diagrams/group-buy-market-market-trial.html) |
| GBM-03 | [group-buy-market-trade-lifecycle.html](../../outputs/flow-diagrams/group-buy-market-trade-lifecycle.html) |
| GBM-04 | [group-buy-market-team-stock.html](../../outputs/flow-diagrams/group-buy-market-team-stock.html) |

## 使用约定

1. 生成时必须使用 `process-flow-diagram` skill 的深色 HTML + SVG 设计系统，并保留导出工具栏、SRI、右侧防裁切和预览检查要求。
2. 图中文字使用中文，类名、方法名、接口路径、表名和 Redis key 保持源码原文。
3. 实线表示主业务调用；虚线表示异步、补偿、回退或循环；玫红分支表示校验失败/异常；琥珀色表示外部系统或存储。
4. 每张图都要带“源码依据”和“事实边界”信息卡，禁止把设计设想画成已实现事实。
5. 源码基线为 2026-08-17 当前工作区（包含尚未提交的本地改动），后续代码变更后应重新复核提示词。

## 已确认的关键事实边界

- ZhiMesh 的聊天主链是 `InputEditor.vue -> POST /api/chat/process -> ChatController -> CharacterChatService`，会话短期记忆以 `conversationUuid` 为边界。
- ZhiMesh 知识库默认是向量 + 图谱混合检索；角色聊天还会并行检索语义记忆和独立的情景记忆。
- ZhiMesh 语音输入 ASR 和语音输出 TTS 在当前聊天代码中已注释停用，不得画成现行主链。
- ZhiMesh 源码中虽存在 `AgentNode`、图像节点等类/前端文件，但当前 `WfComponentNameEnum` 与 `WfNodeFactory` 没有把它们注册为可执行工作流组件，不得画成当前已启用节点。
- 以牌惠友所谓“库存”是某个拼团队伍的名额库存，不是商品 SKU 实物库存。它由 Redis 预占计数、MySQL `group_buy_order.lock_count` 和恢复计数共同约束。
- `MarketNode2CompletableFuture` 没有启用 `@Service`；当前生产链使用 `MarketNode` + `FutureTask`，只能把 CompletableFuture 版本标成替代示例，不能画进主链。
