# RAG 系统四维量化实验执行指南

> 适用项目：`zhimesh`  
> 适用知识库：第二轮多 Chunk 知识库  
> 适用实验：E1 Vector、E2 Graph、E3 Hybrid、E4 Hybrid + Rerank  
> 数据集：30 篇文档、121 条标准问答  
> 文档目标：指导后续采集可复现、可解释、可写入简历的工程指标。

## 1. 为什么要做四维量化

RAGAS 主要回答“检索和答案质量是否更好”，但一套完整 RAG 系统还需要回答四类问题：

1. **接口性能：** 用户需要等待多久，系统能承受多少并发？
2. **图谱质量：** 图谱建了多少有效节点和关系，是否存在大量重复或孤立节点？
3. **检索成本与收益：** Hybrid 和 Rerank 提升质量的同时增加了多少耗时、Token 和调用成本？
4. **工程质量：** 系统能否稳定构建、回归、迁移和保护敏感数据？

最终简历不应只写“准确率提升 XX%”，而应同时给出质量、性能、成本和工程可靠性。

## 2. 实验基本原则

### 2.1 一次只改变一个变量

E1～E4 必须保持以下条件一致：

- 使用同一个知识库 UUID；
- 使用相同的30篇原始文档和分块结果；
- 使用同一个回答模型 ID；
- `temperature=0`；
- 使用相同的121条问题及执行顺序；
- 保持 TopK、Token Budget、超时和重试参数一致；
- E1～E4 之间只改变 `retrieval_mode` 和 `use_reranker`。

### 2.2 区分质量实验和压力实验

- **质量实验：** 并发固定为1，避免并发和上游限流干扰 E1～E4 对比。
- **真实延迟实验：** 使用实际回答模型，统计真实用户等待时间。
- **系统容量实验：** 并发5/10/20，可能产生较多模型费用，应限制请求数量；如改用本地或免费模型，只能说明系统容量，不能与真实模型延迟混写。

### 2.3 固定实验环境

每轮实验开始前记录：

- Git commit 或代码快照时间；
- 后端 JAR 版本；
- Java、Python、RAGAS 版本；
- 数据库与图数据库版本；
- 回答模型、Embedding 模型、图谱抽取模型、Rerank 模型；
- 模型供应商与 API 中转平台；
- 测试机器 CPU、内存和操作系统；
- 实验日期、网络类型及是否经过代理。

### 2.4 预热与异常样本处理

- 正式计时前执行5次预热请求，预热数据不进入统计。
- 成功请求和失败请求分别统计。
- 延迟分位数只使用成功请求，但必须同时报告成功率。
- 重试后的成功请求不得伪装成首次成功；单独记录 `collection_attempts`。
- 不手工删除失败记录，只追加重试结果并按问题 ID 取最新有效记录。

## 3. 当前系统已经提供的观测字段

`POST /admin/rag-evaluation/ask/{kbUuid}` 已在每条预测结果中返回：

| 字段 | 含义 | 可用于计算 |
|---|---|---|
| `timingMs.retrieval` | 整体检索耗时 | 检索 P50/P95、模式开销 |
| `timingMs.generation` | 回答模型生成耗时 | 生成 P50/P95 |
| `timingMs.total` | 后端总耗时 | 端到端阻塞延迟 |
| `collector_elapsed_ms` | Python 客户端观测总耗时 | 网络加后端端到端延迟 |
| `routes[].durationMs` | Vector/Graph 单路耗时 | 路由对比 |
| `routes[].candidateCount` | 单路召回候选数量 | 平均候选数 |
| `routes[].status` | 路由成功、超时或失败 | 路由成功率 |
| `candidates[]` | 合并去重后的候选 | 去重率、来源与排名分析 |
| `candidates[].selected` | 是否进入最终上下文 | 上下文筛选率 |
| `candidates[].tokenCount` | 候选 Token 数 | 上下文 Token 消耗 |
| `rerank.durationMs` | Rerank 耗时 | Rerank 性能开销 |
| `rerank.successful` | Rerank 是否成功 | Rerank 成功率 |
| `usage.inputTokens` | 回答模型输入 Token | 回答成本 |
| `usage.outputTokens` | 回答模型输出 Token | 回答成本 |
| `usage.totalTokens` | 回答模型总 Token | 总 Token 对比 |
| `configSnapshot` | 实际生效配置 | 防止实验配置串组 |

因此，**第三维“检索成本与收益”的大多数指标可直接使用已经完成的 E1～E4 `predictions.jsonl` 计算，不需要重新调用回答模型。**

---

# 第一维：接口性能

## 4. 测量目标

需要回答：

- 用户多久看到第一个 Token？
- 用户多久拿到完整答案？
- 不同检索模式本身耗时多少？
- 并发5/10/20时成功率、吞吐量和延迟如何变化？
- SSE 是否会自然中断？客户端主动断开后系统能否正确释放资源并接受下一次请求？

## 5. 指标定义

### 5.1 首 Token 延迟 TTFT

```text
TTFT = 客户端收到第一条非空模型 Token 的时间 - 请求发出时间
```

注意：`/admin/rag-evaluation/ask` 是阻塞接口，不能测 TTFT。TTFT 必须通过 `/chat/process` SSE 接口测量。

### 5.2 完整回答延迟

```text
FullLatency = 收到 SSE complete 事件的时间 - 请求发出时间
```

对于阻塞评测接口，可使用：

- 服务端：`timingMs.total`；
- 客户端：`collector_elapsed_ms`；
- 网络及客户端开销：`collector_elapsed_ms - timingMs.total`。

### 5.3 纯检索耗时

```text
RetrievalLatency = timingMs.retrieval
VectorRouteLatency = routes[route=vector].durationMs
GraphRouteLatency = routes[route=graph].durationMs
RerankLatency = rerank.durationMs
```

Hybrid 的整体检索耗时不能简单写成 Vector 与 Graph 耗时之和，因为后端可能并行执行多路检索。应直接使用 `timingMs.retrieval`。

### 5.4 成功率、QPS 与中断率

```text
成功率 = 成功完成请求数 / 发出请求总数 × 100%
QPS = 成功完成请求数 / 压测墙钟时间（秒）
SSE自然中断率 = 非人为中断且未收到 complete 的请求数 / SSE请求总数 × 100%
异常后恢复率 = 注入中断后，下一次新请求成功完成数 / 注入中断次数 × 100%
```

当前系统没有实现 SSE 断点续传，因此不得把“重新发起一个新请求成功”描述成“原流自动恢复”。

## 6. 执行步骤

### 6.1 阶段A：利用 E1～E4 现有结果统计阻塞延迟

1. 确认四个预测文件各有121条最新成功记录。
2. 过滤 `collection_status != success` 的记录，并单独统计失败率。
3. 分别提取：
   - `timingMs.retrieval`；
   - `timingMs.generation`；
   - `timingMs.total`；
   - `collector_elapsed_ms`；
   - Vector/Graph 路由耗时；
   - Rerank 耗时。
4. 对每组计算 count、mean、median、P50、P90、P95、max。
5. 按 `question_type` 和 `difficulty` 分组，确认高延迟是否集中在某类问题。

P95 建议使用最近秩定义：

```text
将 N 个样本升序排列，P95 取第 ceil(0.95 × N) 个值。
```

### 6.2 阶段B：测量真实 SSE TTFT

1. 从121题中固定选择30题：
   - 不同 `question_type` 均有覆盖；
   - 简单、中等、困难问题均有覆盖；
   - 包含事实型、关系型、不可回答问题。
2. 固定使用最终准备发布的检索配置，例如 E4。
3. 先执行5次预热，不记录。
4. 逐题串行请求 `/chat/process`，客户端记录：
   - `request_started_at`；
   - `first_token_at`；
   - `complete_at`；
   - `ttft_ms`；
   - `full_latency_ms`；
   - 是否收到 `complete`；
   - 是否收到 `error`；
   - 服务端返回的 `conversationUuid` 是否等于请求值。
5. 输出 P50、P95、最大值与成功率。

建议至少执行两轮。两轮差异超过20%时，增加第三轮并报告中位轮结果。

### 6.3 阶段C：并发5/10/20容量测试

为控制模型费用，采用固定请求数，而不是长时间无限压测：

| 并发 | 每轮请求数 | 轮数 | 总请求数 |
|---:|---:|---:|---:|
| 5 | 20 | 3 | 60 |
| 10 | 20 | 3 | 60 |
| 20 | 20 | 3 | 60 |

执行顺序：5 → 10 → 20，每轮之间等待60秒，避免上游限流窗口相互影响。

每个并发级别统计：

- 请求总数与成功数；
- HTTP 2xx 但业务失败数；
- 超时数、429数、5xx数；
- TTFT P50/P95；
- 完整延迟 P50/P95；
- QPS；
- 上游限流错误率；
- 后端 CPU、内存、数据库连接池峰值。

如果使用本地模型或 Mock 模型做容量测试，报告标题必须写“后端容量测试”，不能写成真实大模型端到端性能。

### 6.4 阶段D：SSE 异常测试

执行20次正常请求和10次主动中断请求：

1. 正常请求必须全部等待 `complete`。
2. 主动中断请求在收到第一个 Token 后关闭客户端连接。
3. 每次中断后立即发起一个新的、带新 Conversation UUID 的请求。
4. 检查：
   - 新请求是否正常完成；
   - 中断请求是否产生半条错误答案；
   - 中断请求是否污染新 Conversation；
   - SSE emitter、线程和数据库连接是否持续增长；
   - 服务端是否记录了敏感正文。

## 7. 第一维输出表

| 模式 | 成功率 | 检索P50 | 检索P95 | 生成P50 | 总耗时P95 | TTFT P95 |
|---|---:|---:|---:|---:|---:|---:|
| E1 Vector |  |  |  |  |  | 不单独测/填写 |
| E2 Graph |  |  |  |  |  | 不单独测/填写 |
| E3 Hybrid |  |  |  |  |  | 不单独测/填写 |
| E4 Hybrid+Rerank |  |  |  |  |  |  |

简历候选句：

> 在10并发条件下，问答接口成功率达到 XX%，P95 首 Token 延迟为 XX ms，P95 完整响应时间为 XX s。

只有完成对应并发和 SSE 实验后才能使用这句话。

---

# 第二维：图谱构建质量

## 8. 测量目标

图谱质量不能只看“30篇文档全部图谱化成功”，还要检查：

- 图谱规模是否合理；
- 是否存在大量同名重复实体；
- 是否存在大量孤立节点；
- 每个节点和关系是否能追踪到来源文档/Chunk；
- Graph/Hybrid 是否确实改善关系类问题。

## 9. 指标定义

### 9.1 基础规模

```text
文档数 = 当前知识库有效文档数量
Chunk数 = 当前知识库有效向量分段数量
实体节点数 = AGE图中该知识库的实体节点数量
关系数 = AGE图中该知识库的边数量
平均每篇实体数 = 实体节点数 / 成功图谱化文档数
平均每篇关系数 = 关系数 / 成功图谱化文档数
平均节点度 = 2 × 关系数 / 实体节点数（无向统计口径）
```

### 9.2 重复与孤立

```text
同名重复率 = (实体节点数 - 归一化名称去重后的实体数) / 实体节点数 × 100%
孤立节点比例 = 度为0的实体节点数 / 实体节点数 × 100%
```

名称归一化口径必须固定，例如：去除首尾空格、统一大小写、统一全半角；不要为了降低重复率进行未经验证的模糊合并。

### 9.3 来源可追踪性

```text
节点来源覆盖率 = 有来源Chunk记录的实体节点数 / 实体节点数 × 100%
关系来源覆盖率 = 有来源Chunk记录的关系数 / 关系数 × 100%
失效来源率 = 来源记录指向不存在或已删除Chunk的数量 / 来源记录总数 × 100%
```

当前架构仍处于 Canonical Chunk 关闭态，因此本轮按现有 Graph Segment 和 Graph Element Source 统计；不要把未启用的标准 Chunk 表数据混入结果。

### 9.4 构建稳定性

```text
首轮成功率 = 首次图谱化成功文档数 / 30 × 100%
最终成功率 = 重试后成功文档数 / 30 × 100%
失败重试成功率 = 重试后成功的失败文档数 / 首轮失败文档数 × 100%
平均重试次数 = 总重试次数 / 发生过失败的文档数
```

需要同时保留首轮和最终成功率。只写最终100%会掩盖模型或网络稳定性问题。

### 9.5 关系类问题收益

从数据集筛出关系类、多跳类问题，计算：

```text
Graph收益 = E2关系类指标 - E1关系类指标
Hybrid收益 = E3关系类指标 - E1关系类指标
Rerank收益 = E4关系类指标 - E3关系类指标
```

优先比较：

- Context Recall；
- Context Precision；
- Answer Accuracy；
- Graph 路由候选命中率。

## 10. 执行步骤

### 10.1 冻结图谱快照

1. 确认30篇文档均无 `doing` 状态。
2. 记录每篇文档最终状态、首次状态、重试次数和使用的图谱模型 ID。
3. 记录知识库 UUID、AGE graph name 和统计时间。
4. 在统计完成前不重新图谱化，不修改文档，不改变实体抽取模型。

### 10.2 统计文档与 Chunk

从现有知识库条目、Embedding Segment 表统计：

- 有效文档总数；
- 每篇文档 Chunk 数；
- Chunk 总数；
- Chunk Token 数的 min、mean、median、P95、max；
- 只有1个 Chunk 的文档数；
- Chunk 来源缺失数。

### 10.3 统计 AGE 节点与关系

在 PostgreSQL/AGE 会话中先加载 AGE，再对实际 graph name 执行统计。以下为模板，执行前替换 `<graph_name>`：

```sql
LOAD 'age';
SET search_path = ag_catalog, "$user", public;

SELECT * FROM cypher('<graph_name>', $$
  MATCH (n)
  RETURN count(n)
$$) AS (entity_count agtype);

SELECT * FROM cypher('<graph_name>', $$
  MATCH ()-[r]->()
  RETURN count(r)
$$) AS (relation_count agtype);

SELECT * FROM cypher('<graph_name>', $$
  MATCH (n)
  WHERE NOT (n)--()
  RETURN count(n)
$$) AS (isolated_count agtype);

SELECT * FROM cypher('<graph_name>', $$
  MATCH (n)
  WITH toLower(trim(n.name)) AS normalized_name, count(*) AS c
  WHERE c > 1
  RETURN normalized_name, c
  ORDER BY c DESC
$$) AS (normalized_name agtype, duplicate_count agtype);
```

如果 AGE 版本不支持某个字符串函数，应导出 `name` 后在 Python 中按相同规则统计，不要临时改变归一化口径。

### 10.4 检查来源覆盖

1. 从图谱节点和关系提取 `graph_element_id`。
2. 与现有 Graph Element Source 表进行关联。
3. 检查每个来源是否指向有效知识库、有效文档和有效 Graph Segment。
4. 输出无来源节点、无来源关系、失效文档来源、失效 Segment 来源四张清单。
5. 随机抽查至少20个实体和20条关系，人工核对原文是否支持该事实。

### 10.5 统计构建成功率

需要保留每次图谱化执行记录。若当前数据库只保留最终状态，则：

- 最终成功率可以直接统计；
- 首轮成功率和重试成功率只能从历史日志还原；
- 无历史日志时写“无可靠数据”，不得根据记忆填写。

### 10.6 统计关系类问题收益

1. 固定关系类问题 ID 清单并保存，不能看完分数后再挑题。
2. 从四组 RAGAS 结果中按同一 ID 取有效分数。
3. 只比较四组均有有效值的共同样本。
4. 同时报告共同样本数，避免某组失败样本较多导致均值虚高。
5. 对每题计算 E2-E1、E3-E1、E4-E3 的差值，再统计均值和中位数。

## 11. 第二维输出表

| 指标 | 结果 |
|---|---:|
| 文档数 | 30 |
| Chunk总数 |  |
| 实体节点数 |  |
| 关系数 |  |
| 平均每篇实体数 |  |
| 同名重复率 |  |
| 孤立节点比例 |  |
| 节点来源覆盖率 |  |
| 关系来源覆盖率 |  |
| 首轮图谱化成功率 |  |
| 最终图谱化成功率 |  |
| 失败重试成功率 |  |
| Hybrid关系类Answer Accuracy提升 |  |

简历候选句：

> 从30篇文档的 XX 个 Chunk 中抽取 XX 个实体和 XX 条关系，实体同名重复率为 XX%、孤立节点比例为 XX%；Hybrid 在关系类问题上的 Context Recall 相比 Vector 提升 XX%。

---

# 第三维：不同检索方案的成本与收益

## 12. 测量目标

需要回答：

- 每种模式召回多少候选？
- Hybrid 合并后去掉了多少重复内容？
- 最终有多少候选进入 LLM 上下文？
- Rerank 增加多少耗时？
- 每题消耗多少输入和输出 Token？
- 质量提升是否值得额外延迟和费用？

## 13. 指标定义与计算方式

### 13.1 召回与去重

对每道题：

```text
路由原始候选数 = sum(routes[].candidateCount)
合并候选数 = len(candidates)
估算重复候选数 = 路由原始候选数 - 合并候选数
候选去重率 = 估算重复候选数 / 路由原始候选数 × 100%
最终上下文候选数 = count(candidates[selected=true])
候选筛选率 = 最终上下文候选数 / 合并候选数 × 100%
```

E1/E2 只有一条路由时，“路由原始候选数－合并候选数”主要反映单路内部重复；E3/E4 才能体现跨路由重复。

### 13.2 上下文 Token

```text
最终上下文Token = sum(candidates[selected=true].tokenCount)
候选池Token = sum(candidates[].tokenCount)
上下文压缩率 = 1 - 最终上下文Token / 候选池Token
```

`usage.inputTokens` 还包括系统提示词、用户问题等内容，不能等同于检索上下文 Token。

### 13.3 耗时开销

```text
Graph相对Vector检索开销 = E2 retrieval P50/P95 - E1 retrieval P50/P95
Hybrid相对Vector检索开销 = E3 retrieval P50/P95 - E1 retrieval P50/P95
Rerank增量开销 = E4 retrieval P50/P95 - E3 retrieval P50/P95
Rerank自身耗时 = E4 rerank.durationMs
```

E3 与 E4 必须使用同一批共同成功样本进行成对比较。

### 13.4 Token 与费用

```text
单题回答模型成本 = 输入Token/1,000,000 × 输入单价
                 + 输出Token/1,000,000 × 输出单价

总实验回答模型成本 = 所有问题单题成本之和
```

模型价格必须记录供应商、币种和价格快照日期。

当前 `usage` 可靠覆盖回答模型 Token，但不一定覆盖：

- Graph 查询实体抽取模型调用；
- Embedding 调用；
- Rerank 平台计费；
- RAGAS 评审模型调用。

在这些调用没有独立计量前，只能写“回答模型成本”，不得写“RAG 单题总成本”。

### 13.5 API 调用次数

应分别统计：

- 回答模型调用次数；
- Graph 查询抽取模型调用次数；
- Embedding 调用次数；
- Rerank 调用次数；
- RAGAS Judge 调用次数。

不能仅凭检索模式猜测调用次数。建议以后在统一模型调用记录中增加 `experiment_id`、`question_id`、`call_stage`，再按阶段聚合。

### 13.6 质量收益

每个成本指标应与以下质量指标共同展示：

- Context Precision；
- Context Recall；
- Faithfulness；
- Answer Relevancy；
- Answer Accuracy。

推荐增加两个易解释指标：

```text
每增加100ms带来的Accuracy提升 = Accuracy差值 / 检索P50差值 × 100
每增加1000输入Token带来的Accuracy提升 = Accuracy差值 / 输入Token差值 × 1000
```

当分母小于等于0时不计算该指标。

## 14. 执行步骤

### 14.1 校验预测文件

对 E1～E4 分别检查：

1. 唯一成功问题 ID 数是否为121；
2. 是否存在同一 ID 多条成功记录；
3. `configSnapshot.retrievalMode` 是否匹配实验；
4. `configSnapshot.useReranker` 是否匹配实验；
5. E4 的 `rerank.successful` 是否全部为 true；
6. 每组路由名称是否正确；
7. `timingMs`、`usage` 和 `candidates` 是否缺失。

出现重复记录时，以同一 ID 最后一条成功记录为准；原文件保持追加式，不直接覆盖。

### 14.2 构建共同样本集

```text
共同样本集 = E1成功ID ∩ E2成功ID ∩ E3成功ID ∩ E4成功ID
```

所有跨组差异必须使用共同样本集。除此之外，可额外报告各组自身成功样本的总体统计，但不能混为同一结论。

### 14.3 逐题生成运行指标

输出 `operational_metrics_per_sample.csv`，至少包括：

```text
id, experiment_id, question_type, difficulty,
retrieval_ms, generation_ms, total_ms, collector_elapsed_ms,
vector_route_ms, graph_route_ms,
raw_route_candidates, merged_candidates, selected_candidates,
dedup_rate, candidate_tokens, selected_context_tokens,
rerank_ms, rerank_success,
input_tokens, output_tokens, total_tokens,
context_precision, context_recall, faithfulness,
answer_relevancy, answer_accuracy
```

### 14.4 生成汇总

输出 `operational_metrics_summary.csv`，按 experiment_id 汇总：

- 样本数与成功率；
- 各类耗时 mean/P50/P95；
- 候选数量均值与P95；
- 去重率均值；
- 最终上下文 Token mean/P50/P95；
- 回答模型输入/输出 Token 均值；
- Rerank 成功率和耗时；
- 五项 RAGAS 均值及有效样本数。

### 14.5 生成成对差异

输出：

- `e2_minus_e1.csv`；
- `e3_minus_e1.csv`；
- `e4_minus_e3.csv`；
- `e4_minus_e1.csv`。

每张表同时包含质量差值、耗时差值和 Token 差值。

## 15. 第三维输出表

| 指标 | E1 Vector | E2 Graph | E3 Hybrid | E4 Hybrid+Rerank |
|---|---:|---:|---:|---:|
| 平均路由候选数 |  |  |  |  |
| 平均合并候选数 |  |  |  |  |
| 平均去重率 |  |  |  |  |
| 平均上下文Token |  |  |  |  |
| 检索P95 |  |  |  |  |
| Rerank平均耗时 | - | - | - |  |
| 平均输入Token |  |  |  |  |
| 平均输出Token |  |  |  |  |
| Answer Accuracy |  |  |  |  |

简历候选句：

> 通过多路候选去重和 Token Budget 控制，将平均注入上下文压缩 XX%，Hybrid+Rerank 相比 Vector 的 Answer Accuracy 提升 XX%，P95 检索耗时增加 XX ms。

---

# 第四维：工程质量

## 16. 测量目标

工程质量不应只写“项目可以运行”，需要可复查地证明：

- 代码能够一键测试和构建；
- 核心链路有自动化回归；
- 新旧会话迁移没有数据残留；
- 敏感数据没有进入日志；
- 实验和生产配置可以安全启停与回滚。

## 17. 当前已验证基线

截至2026-07-27，已获得以下真实数据：

| 指标 | 当前结果 |
|---|---:|
| 后端自动化测试 | 40项通过，0 failure，0 error |
| 用户端 ESLint | 0 error，32 warning |
| 用户端 TypeScript 类型检查 | 通过 |
| 用户端生产构建 | 通过 |
| 管理端 ESLint/Prettier | 0 error |
| 管理端生产构建 | 通过 |
| 已知敏感测试问题/答案日志命中 | 0 |
| 灰度测试活跃数据残留 | 0 |
| Conversation 异常归属消息 | 0 |
| Canonical Chunk 关闭态数据 | 0 |

这些数字可以直接使用，但必须保留测试日期和报告作为证据。

## 18. 执行步骤

### 18.1 后端测试与构建

在 `server` 目录执行：

```powershell
mvn test
mvn package -DskipTests
```

记录：

- tests、failures、errors、skipped；
- Maven 总耗时；
- JAR 是否成功生成；
- 重复执行3次的构建耗时中位数。

测试日志中由“故障降级用例”主动打印的异常堆栈不等于测试失败，应以 Maven Summary 为准。

### 18.2 用户端质量门禁

在 `user-web` 目录执行：

```powershell
npm run lint
npm run type-check
npm run build-only
```

记录 error、warning、类型错误、构建结果和构建耗时。

### 18.3 管理端质量门禁

在 `admin-web` 目录执行：

```powershell
npm run lint
npm run build
```

记录 ESLint/Prettier error、构建结果和构建耗时。

### 18.4 建议补充 JaCoCo 覆盖率

后续为 Maven 增加 JaCoCo，只统计业务源码，不统计 DTO、Entity 和配置类。重点报告：

- 整体行覆盖率；
- `ConversationService` 分支覆盖率；
- `ChatContextResolver` 分支覆盖率；
- 混合检索与降级逻辑分支覆盖率；
- MCP 配置校验分支覆盖率。

覆盖率目标建议：

- 核心 Service 行覆盖率不低于70%；
- 核心权限、归属和降级逻辑分支覆盖率不低于60%。

在真正生成报告前，不要把目标覆盖率写成已完成数据。

### 18.5 敏感日志扫描

扫描范围：HTTP、LLM、RAG、Workflow、MCP、ASR、图片生成和外部 API。

检查日志是否包含：

- 测试问题的唯一标记字符串；
- 模型答案唯一标记字符串；
- `Authorization`、`Bearer`、`api_key`、`rawKey`；
- 完整 request/response body；
- 完整 Prompt；
- 模型原始 JSON 响应。

输出：

```text
sensitive_question_hits
sensitive_answer_hits
authorization_hits
raw_response_hits
```

所有命中必须人工复核。仅出现字段名称但没有值，不应误判为泄露。

### 18.6 Conversation 一致性回归

至少覆盖：

1. 两个用户之间不能读取彼此 Conversation。
2. 同一用户两个 Conversation 的消息不能串写。
3. SSE 返回前切换会话，后续 Token 不得写入新会话。
4. 重新生成必须引用同一 Conversation 的父问题。
5. 删除 Conversation 后，其全部消息均软删除。
6. 旧 Character 消息接口不能查询到已删除 Conversation 的消息。
7. `conversationEnabled=false` 时六类 Conversation API 均不可用。
8. External Blocking/Streaming 返回实际 Conversation UUID。

数据库审计目标：

```text
active_test_users = 0
active_test_characters = 0
active_test_conversations = 0
active_test_messages = 0
active_conversation_assignment_anomalies = 0
```

### 18.7 灰度与回滚计时

以后每次迁移记录：

- 数据库备份耗时；
- Schema 迁移耗时；
- 历史回填行数、批大小、总耗时；
- 回填吞吐量（行/秒）；
- 回填失败数；
- 开启功能开关耗时；
- 回滚耗时；
- 回滚后数据一致性异常数。

这类数据非常适合 Java 后端岗位，但只有真实执行后才能写入简历。

## 19. 第四维输出表

| 类别 | 指标 | 结果 | 证据文件 |
|---|---|---:|---|
| 后端 | 自动化测试 | 40项通过 | Maven日志 |
| 后端 | 核心代码覆盖率 | 待测 | JaCoCo报告 |
| 用户端 | ESLint error | 0 | lint日志 |
| 管理端 | ESLint error | 0 | lint日志 |
| 安全 | 敏感正文命中 | 0 | 扫描报告 |
| 会话 | 跨会话串写 | 0 | 灰度报告 |
| 迁移 | 活跃测试残留 | 0 | 数据库审计 |
| 发布 | 回滚耗时 | 待测 | 发布记录 |

简历候选句：

> 完成40项后端自动化测试及前后端质量门禁，ESLint达到0 error；通过功能开关、消息双写和数据库审计完成 Conversation 灰度迁移，跨会话串写、删除残留与测试数据残留均为0。

---

# 20. 推荐执行顺序

为了避免重复消耗模型额度，严格按以下顺序执行：

1. **冻结现有 E1～E4 预测与评分文件。** 复制到只读备份目录并计算 SHA-256。
2. **校验四组121题完整性。** 失败评分可重试，但不要重新回答已经成功的问题。
3. **离线统计第三维。** 直接从 predictions 和 scores 计算候选、Token、耗时与质量收益。
4. **统计第二维图谱质量。** 读取数据库和 AGE，不调用回答模型。
5. **执行第一维串行 SSE 延迟实验。** 30题、5次预热、至少2轮。
6. **执行第一维并发容量实验。** 先5并发，再10并发，额度充足时再20并发。
7. **执行第四维工程门禁与数据库审计。** 测试、构建、日志扫描、会话灰度和回滚。
8. **生成统一报告。** 所有简历数字都必须能够回溯到 CSV、JSON、SQL 或构建日志。

# 21. 最终交付目录建议

```text
ragas-evaluation/output/quantification/
├─ environment.json
├─ dataset_snapshot.sha256
├─ graph_quality_summary.json
├─ graph_duplicate_entities.csv
├─ graph_isolated_entities.csv
├─ operational_metrics_per_sample.csv
├─ operational_metrics_summary.csv
├─ e2_minus_e1.csv
├─ e3_minus_e1.csv
├─ e4_minus_e3.csv
├─ sse_latency_per_sample.csv
├─ sse_latency_summary.json
├─ concurrency_5.json
├─ concurrency_10.json
├─ concurrency_20.json
├─ engineering_quality_summary.json
└─ final_quantification_report.md
```

# 22. 如何根据岗位 JD 组合项目职责

项目职责不需要在所有岗位使用同一版本，应从同一个真实技术素材库中组合。

## 22.1 大模型应用/RAG 岗

优先顺序：

1. 多路混合检索与 Token Budget；
2. MCP 动态工具编排；
3. 长短期分层记忆；
4. RAGAS 对照实验；
5. 图谱构建质量与关系类问题提升。

## 22.2 Java 后端岗

优先顺序：

1. Character/Conversation 领域模型重构；
2. SSE 异步竞态与消息一致性；
3. 数据库双写、回填、功能开关和回滚；
4. MCP 运行时注册和配置治理；
5. 自动化测试、权限隔离和敏感日志治理。

## 22.3 AI 平台/Agent 工程岗

优先顺序：

1. MCP 工具注册与冲突治理；
2. Workflow Agent 无状态/有状态边界；
3. 长短期记忆分层；
4. 多入口上下文统一；
5. RAG 评测与运行成本观测。

# 23. 最终简历数字模板

完成四维实验后，优先选4～6个最有解释力的数字：

> 基于30篇文档和121条标准问答完成 Vector、Graph、Hybrid、Hybrid+Rerank 四组实验；Hybrid+Rerank 相比 Vector 的 Answer Accuracy 提升 XX%，平均上下文 Token 降低 XX%，P95 检索耗时增加 XX ms。在10并发下接口成功率达到 XX%，P95 TTFT 为 XX ms；图谱包含 XX 个实体和 XX 条关系，孤立节点比例为 XX%。完成40项后端自动化测试，跨会话串写和灰度数据残留均为0。

该模板中的 `XX` 必须来自本指南定义的统一统计口径，不能根据单题结果、日志印象或不同样本集合拼接。
