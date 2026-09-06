# 角色聊天误检索：意图路由、相关性门控与长期记忆治理

## 1. 文档状态

- 事件日期：2026-08-21
- 影响范围：角色聊天中的知识库向量检索、图谱检索、BM25 检索、语义记忆、情景记忆及回答引用标签
- 修复阶段：P0、P1、P2 在本轮实施；P3 可观察性持久化后续实施
- 核心原则：意图决定“允许尝试哪些来源”，相关性门控决定“哪些结果可以进入回答”，引用标签只表示“最终被接受并进入 Prompt 的证据”

## 2. 用户可见症状

### 2.1 普通常识问题命中无关知识库

问题：

> 火影忍者里面的面具男是谁？

回答正确识别为卡卡西，但界面同时显示“记忆、引用、关键词”。数据库审计确认回答消息 `id=1745`：

| 通道 | 命中数 | 分数 |
| --- | ---: | --- |
| 知识库向量 | 3 | 0.69、0.68、0.68 |
| 长期记忆 | 3 | 0.65、0.65、0.64 |
| 图谱 | 0 | - |
| BM25 | 1 | 3.219736 |

BM25 实际命中内容是《技术文档的信息架构与版本治理》。问题中的低信息短语“是谁”和文档中的“读者是谁”发生了词法碰撞，导致完全无关的技术文档进入 Prompt。

### 2.2 纯问候触发长期记忆并污染回答

问题：

> 你好

数据库审计确认回答消息 `id=1747`：知识库向量、图谱和 BM25 均未命中，只有长期记忆命中 3 条，分数为 0.72、0.71、0.71。

召回内容：

1. 请求抓取网页内容，但尚未提供页面链接
2. 请求抓取牛客网链接上的内容
3. 请求抓取牛客网链接中的内容

最终回答主动提到“如果你想抓取网页内容，直接告诉我链接”，证明错误记忆不仅点亮标签，还真实进入 Prompt 并改变了回答。

## 3. 根因分析

### 3.1 知识库意图与记忆意图没有统一

`CharacterChatHelper.retrieve` 在执行知识库意图路由前，先无条件创建语义记忆和情景记忆检索器。`NO_RAG` 因而只关闭知识库，不关闭长期记忆。

### 3.2 `NO_RAG` 规则覆盖范围有限

规则可以识别“你好、谢谢、再见”等强会话模式，但无法仅凭问题文本判断一个普通事实问题是否属于当前绑定知识库。普通问题进入 `UNCERTAIN` 或原型路由后，会按保守基线继续检索。

### 3.3 不确定意图采用“尽量全查”的回退

当前回退偏向召回率。只要知识库相关通道可用，向量、图谱或 BM25 就可能被执行。这个策略缺少检索后的“全部拒绝”能力。

### 3.4 角色聊天没有接入已有重排器

项目已有 `DeduplicatingContentRetriever` 和 `BgeReranker`，知识库 QA 路径也能传入 reranker；角色聊天创建 `RetrieverCreateParam` 时没有配置 reranker，候选只按原始分数和 RRF 进入上下文。

### 3.5 现有重排默认至少保留一条

即使接入 reranker，`rerankMinCandidates=1` 仍会保证最少一条结果。这适合严格知识库问答，却不适合开放聊天：开放聊天必须允许“知识库没有相关证据”。

### 3.6 BM25 保留了低信息中文 n-gram

当前 CJK tokenizer 生成连续二元和三元片段。“是谁、什么、怎么、里面的”等疑问和结构短语也进入查询，使无关文档因偶然短语重叠获得分数。

### 3.7 引用标志根据原始命中而非最终证据设置

回答标签和引用表读取各源 retriever 的原始命中缓存。候选即使在融合、去重或重排后未被选择，仍可能被记录为引用。

### 3.8 情景记忆 append-only，近重复事件占满 Top-K

“抓取牛客网页面”被保存成多条近似事件。重复事件在召回时互相强化，并占满前三名，降低记忆多样性。

### 3.9 每一轮都触发长期记忆抽取

长期记忆抽取 Prompt 虽然规定 `Hi` 应返回空列表，但纯问候仍会产生一次额外 LLM 调用。仅依赖模型遵守 Prompt 不是可靠的写入门控。

## 4. 修复后的目标链路

```text
用户问题
  ↓
知识库范围前置门控：RELATED / UNCERTAIN / UNRELATED
  ├─ UNRELATED（正式模式）→ 跳过文档意图识别与全部知识库路由
  └─ RELATED / UNCERTAIN → 继续文档意图识别
  ↓
请求级来源路由
  ├─ knowledge: OFF / AUTO / REQUIRED
  └─ memory: NONE / SEMANTIC / EPISODIC / BOTH / AUTO
  ↓
仅创建被允许的检索器
  ↓
产生原始候选 raw candidates
  ↓
BM25 查询词降噪 + 候选去重
  ↓
BGE cross-encoder 重排
  ↓
绝对相关性门槛 + 相对尾部裁剪
  ↓
失败时使用保守词法/高置信向量门控
  ↓
最终证据 accepted evidence（允许为空）
  ├─ 进入 Prompt
  ├─ 保存引用明细
  └─ 点亮回答标签
```

## 5. P0：正确性止血

### 5.1 统一来源路由

增加高召回优先的长期记忆查询策略：

- `NONE`：不检索长期记忆
- `SEMANTIC`：只检索稳定偏好、身份和长期事实
- `EPISODIC`：只检索时间相关的历史事件
- `BOTH`：两类都检索
- `AUTO`：没有明确记忆信号、但问题包含有效信息时，以受限 Top-1 同时探测两类记忆

`NONE` 只用于空文本以及能够确定不需要记忆的强问候、感谢、确认和告别。出现明确个人记忆信号时，按“我之前、上次、你还记得、我的偏好、我叫什么”等线索选择窄通道；其余有信息量的问题进入 `AUTO`。因此前门只拦截确定无意义的请求，不会因为规则词典没有覆盖某一种自然表达就漏掉真正需要记忆的问题。

文档知识库与长期记忆是两套独立路由。文档检索的 `NO_RAG` 只表示“不查文档知识库”，不能覆盖个人记忆决策。因此“你还记得我之前说过什么”即使被文档意图识别为 `NO_RAG`，仍会开启长期记忆；“火影忍者里的面具男是谁”这类普通事实问题可以执行一次受限 `AUTO` 记忆探测，但不相关候选必须在后门被全部拒绝。

### 5.2 问候短路

空文本、纯问候、感谢、确认、告别以及没有任何有效主题词的普通疑问，在生成查询向量前短路，不创建外部检索器，直接使用角色设定、短期会话和模型自身能力回答。明确的“我是谁”等个人记忆请求不受低信息短路影响。

### 5.3 引用改为最终证据语义

检索器必须在融合选择结束后清理原始 provenance 缓存，只保留最终选中的 embedding ID、BM25 chunk UUID 和图谱通道。标签与引用表均从同一份最终证据派生。

验收：

- “你好”无记忆、无引用、无图谱、无关键词。
- 被重排拒绝的原始候选不得写入任何引用表。

## 6. P1：相关性门控与 BM25 降噪

### 6.1 接入角色聊天 reranker

优先使用绑定知识库配置的 rerank 模型；未单独配置时，使用系统中第一个启用的 `rerank` 模型。多个知识库必须使用同一个请求级 reranker，避免重复调用。

### 6.2 允许全部拒绝

开放聊天不再保证至少保留一条。增加绝对重排分数阈值，在相对分数裁剪之前淘汰低相关候选。全部候选低于门槛时返回空上下文，而不是强行选择 Top-1。

### 6.3 重排失败时保守降级

远程 reranker 失败或熔断时：

- 有明确实体/标识符词法重叠的候选可保留；
- 向量分数达到高置信阈值的候选可保留；
- 仅因疑问词、连接词重叠的候选全部拒绝；
- 不再无条件 fail-open 到原始 RRF 顺序。

### 6.4 BM25 查询词过滤

查询阶段过滤低信息词及其 n-gram，例如“是谁、什么、怎么、为什么、这个、那个、里面、里面的”。如果过滤后没有有效词，BM25 返回空，不访问仓储。

索引 analyzer 维持不变，避免为查询降噪强制重建全部索引；本次只改变在线 query terms。

### 6.5 低延时前置门控

为避免用更高延时换取相关性，本轮增加的前置逻辑全部是本地确定性计算：

1. 纯问候、确认和无有效主题的问题在生成查询向量之前直接返回，不调用 embedding、知识库检索或 reranker；
2. BM25 在 readiness 检查之前执行查询降噪，清理后没有有效词时不访问数据库；
3. 融合后的弱向量候选在远程 reranker 调用前执行一次 O(n) 过滤，仅拒绝“单一向量来源、低于阈值、且无有效词法重合”的候选；
4. 图谱候选、多个通道共同命中的候选以及高置信向量候选不在前置阶段激进删除；
5. 重排后的绝对相关性门槛继续保留，作为最终证据安全线。

前置门控不新增任何模型调用、HTTP 请求或数据库往返。知识库候选被提前缩减到一条时仍可跳过 reranker；但 `AUTO` 长期记忆的单候选不能使用合成满分，必须调用 cross-encoder 做相关性判定，因为此处重排承担的是“是否相关”的验证职责，而不只是排序职责。

### 6.6 BM25 通用噪声与文档频率治理

BM25 不再只处理“是谁”，而是统一执行：

- 查询级短语清理：覆盖疑问词、礼貌词、指代词、会话填充词及其常见近义表达；
- 分词后信息量过滤：再次清理遗漏的二元、三元噪声片段，同时保留接口路径、错误码和配置键；
- 文档频率过滤：在当前授权知识库范围内，出现在过高比例文档中的词不再贡献 BM25 分数。

文档频率已经是现有 Okapi BM25 SQL 计算 IDF 的中间结果，新过滤条件直接合并进同一次 SQL，不增加第二次统计查询。小于配置文档数量的知识库默认不启用高频过滤，避免小语料中每个有效词都因占比过高被误删。

验收：

- “火影忍者里面的面具男是谁”不能因“是谁”命中技术文档。
- “根据知识库说明版本治理”仍可命中对应文档。
- 精确接口名、错误码、路径和配置键仍可走 BM25。

### 6.7 当前会话知识库范围前置门控

在 query embedding 生成后、BM25 readiness 与文档意图识别之前增加三态范围门控：

- `RELATED`：明确知识库请求、严格知识库、精确标识符、标题/描述词法命中或范围向量高相关；
- `UNCERTAIN`：上下文改写失败、索引不完整、探测异常或分数处于灰区；
- `UNRELATED`：所有当前会话知识库的最高范围向量分数仍低于保守下限，并且没有任何保护信号。

只有正式模式下的 `UNRELATED` 会跳过 BM25 readiness、知识库意图识别、向量/BM25/图谱路由。长期记忆路由保持独立，知识库被拦截不等于记忆被关闭。

门控读取当前授权知识库在 Redis 中的有界多主题画像，执行一次批量 MGET 和 JVM 本地余弦比较，不扫描真实知识分块：

- 无关请求只支付一次有界画像比较，随后跳过更昂贵的意图、BM25、图谱和重排链路；
- 完整问题复用同一个查询向量；上下文依赖问题使用改写查询检索，并额外保留原问题向量防止误拦；
- 显式知识库请求、严格模式、错误码/API/配置键等精确标识符直接放行，不提前执行探测；
- 空候选、无有效分数、索引不完整、向量库异常全部 fail-open 为 `UNCERTAIN`。

Shadow 分支已经移除，完整 READY 画像的 `UNRELATED` 会直接跳过知识库链路；画像更新或 Generation 切换时直接默认 `RELATED`，Redis 缺失、版本不兼容或探测异常仍 fail-open 为 `UNCERTAIN`。紧急回滚使用 `ZHIMESH_KB_SCOPE_GATE_ENABLED=false`。

## 7. P2：长期记忆治理

### 7.1 高召回记忆查询意图

长期记忆采用“明确无用才关闭、不确定则受限探测”的策略：

- 语义：偏好、身份、长期目标、关于用户的稳定信息；
- 情景：之前、上次、曾经、我们讨论过、历史操作；
- 显式“记得/回忆”可以同时开启两类。
- 空文本和确定性的会话寒暄使用 `NONE`；
- 未命中上述规则但包含有效主题的请求使用 `AUTO`，避免规则词典造成假阴性。

### 7.2 记忆读取阈值

显式语义或情景请求使用独立的 `0.65` 读取阈值，优先保证真正的个人回忆可以成为候选；`AUTO` 使用更高的 `0.72` 探测阈值。两类模式都必须通过最终相关性门控。

### 7.3 AUTO 探测与延时控制

`AUTO` 同时允许语义和情景记忆，但执行成本有明确上限：

1. AUTO 每个记忆库召回 Top-3 候选，经重排与门控后每路最多进入 Prompt 1 条；
2. 显式记忆请求每路召回 Top-5 候选，最终最多保留 3 条，避免规则正确但向量 Top-1 偶然偏移造成漏召回；
3. 两路复用意图路由已经生成的同一份 query embedding，不重复调用 embedding 模型；
4. 语义、情景以及知识库检索器由现有请求执行器并发执行，不把两次向量查询串行叠加；
5. 每一路候选在一次 reranker 请求中批量评分，不按候选逐条发起 HTTP 请求；
6. 向量库没有返回候选时不调用 reranker；所有记忆模式返回单候选时也强制执行 cross-encoder 验证；
7. cross-encoder 低于绝对相关性阈值时整路返回空，不允许候选进入 Prompt、引用表或前端“记忆”标签；
8. reranker 不可用时沿用保守的高置信向量/有效词法重合兜底，不无条件放行原始候选。

### 7.4 情景记忆的相关性与时间排序

情景记忆不能简单改成“始终取最近 N 条”。用户询问很久以前的某个明确事件时，纯时间窗口会直接漏掉正确内容。本轮采用两阶段策略：

1. 先从独立情景向量库召回相关候选，并通过 cross-encoder 绝对相关性门槛；
2. 只有问题包含“上次、最近、刚才、最新、昨天”等时间信号时，才按 `75% 相关性 + 20% 事件时间新鲜度 + 5% 重要性` 重新排序；
3. 时间分数按 30 天半衰期衰减，并且权重硬性限制在不超过 50%，避免近期弱相关事件压过旧的强相关事件；
4. 不含近期信号的问题维持纯相关性排序。

该排序是候选通过相关性后执行的本地 O(n log n) 计算，不新增数据库、embedding 或 HTTP 调用。

### 7.5 记忆写入短路

纯问候、感谢、确认、告别和空文本不调用长期记忆抽取模型。其他对话继续由现有结构化抽取 Prompt 判断是否有可记忆信息。

### 7.6 情景记忆幂等与近重复去重

写入前执行两级检查：

1. 同一 `characterMsgId` 已写入则跳过，保证重试幂等；
2. 同角色下存在高相似度近重复事件则跳过，避免同类事件占满 Top-K。

用户 ID 同时写入 metadata，为后续用户级隔离和迁移提供依据。

验收：

- 同一消息重试不会新增重复情景记忆。
- 近似“请求抓取牛客网页面”不会连续保存多条等价事件。
- “我之前让你抓取过哪个网站？”能够召回情景记忆。

### 7.7 语义记忆原子更新与并发保护

语义记忆 `UPDATE` 不再删除旧记录后复用旧向量。新文本必须重新生成 embedding，并使用相同 `embeddingId` 原子 upsert 文本、向量和 metadata：pgvector 走 `INSERT ... ON CONFLICT DO UPDATE`，Neo4j 走 `MERGE`。写入失败时旧记录仍然完整存在，已有引用 ID 也不会变化。

从旧记忆候选检索、LLM 动作判断到动作落库的完整区间使用按 `characterId` 隔离的 Redis 可续租分布式锁。锁丢失或 Redis 不可用时 fail-closed 跳过本次语义修改，禁止两个实例并发修改同一角色记忆。每条语义记忆同时记录最后来源消息 ID；较早的异步任务即使后完成，也不能覆盖或删除较新消息已经更新的记忆。

### 7.8 情景记忆双时间轴

情景 metadata 同时保存：

- `occurred_at`：事件实际发生时间；
- `create_time`：来源聊天消息写入时间；
- `time_precision`：`DATETIME / DAY / MONTH / YEAR / APPROXIMATE / UNKNOWN`；
- `raw_time_expression`：用户原话中的时间短语；
- `time_source`：时间来源。

抽取 Prompt 在每次请求时根据来源消息时间动态生成，不再把应用启动日期固化为“今天”。模型返回的 `raw_time_expression` 必须真实存在于用户原话，且 `occurred_at` 必须能够解析，否则整组时间字段降级为 `UNKNOWN`。没有可靠发生时间的新事件在“最近”排序中使用中性分，不能因为今天才被讲述就伪装成今天发生；历史旧数据仅保留有限的 `create_time` 兼容权重。

这些字段写入现有向量表的 JSONB/Neo4j metadata，不增加关系表列，因此不需要额外 DDL。

### 7.9 新增配置与默认值

| 配置项 | 环境变量 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `retrieval.rerank-min-candidates` | `ZHIMESH_RAG_RERANK_MIN_CANDIDATES` | `0` | 开放聊天允许最终证据为空 |
| `retrieval.rerank-absolute-score-threshold` | `ZHIMESH_RAG_RERANK_ABSOLUTE_SCORE_THRESHOLD` | `0.30` | cross-encoder 绝对相关性门槛 |
| `retrieval.relevance-gate-fail-open` | `ZHIMESH_RAG_RELEVANCE_GATE_FAIL_OPEN` | `false` | reranker 异常时是否无条件放行；生产默认关闭 |
| `retrieval.fallback-high-confidence-vector-score` | `ZHIMESH_RAG_FALLBACK_HIGH_CONFIDENCE_VECTOR_SCORE` | `0.82` | reranker 不可用时允许保留的高置信向量分数 |
| `retrieval.pre-rerank-gate-enabled` | `ZHIMESH_RAG_PRE_RERANK_GATE_ENABLED` | `true` | 是否启用无 I/O 的重排前候选门控 |
| `retrieval.pre-rerank-vector-score-floor` | `ZHIMESH_RAG_PRE_RERANK_VECTOR_SCORE_FLOOR` | `0.70` | 低于该值的单路向量候选需要有效词法重合 |
| `retrieval.episodic-recency-weight` | `ZHIMESH_EPISODIC_RECENCY_WEIGHT` | `0.20` | 含近期信号时情景记忆排序中的事件时间权重 |
| `retrieval.episodic-recency-half-life-days` | `ZHIMESH_EPISODIC_RECENCY_HALF_LIFE_DAYS` | `30` | 情景记忆时间分数的半衰期天数 |
| `retrieval.episodic-importance-weight` | `ZHIMESH_EPISODIC_IMPORTANCE_WEIGHT` | `0.05` | 时间型问题中情景重要性的轻量平分权重 |
| `retrieval.bm25.max-document-frequency-ratio` | `ZHIMESH_BM25_MAX_DOCUMENT_FREQUENCY_RATIO` | `0.85` | BM25 高频词最大文档占比 |
| `retrieval.bm25.document-frequency-filter-min-documents` | `ZHIMESH_BM25_DF_FILTER_MIN_DOCUMENTS` | `20` | 启用文档频率过滤所需的最小文档数 |
| `knowledge-scope-gate.enabled` | `ZHIMESH_KB_SCOPE_GATE_ENABLED` | `true` | 是否执行知识库范围前置判断 |
| `knowledge-scope-gate.unrelated-max-score` | `ZHIMESH_KB_SCOPE_GATE_UNRELATED_MAX_SCORE` | `0.60` | 低于该分数才可能判为明确无关 |
| `knowledge-scope-gate.related-min-score` | `ZHIMESH_KB_SCOPE_GATE_RELATED_MIN_SCORE` | `0.82` | 高于该分数判为明确相关，中间区域 fail-open |
| `memory.retrieve-min-score` | `ZHIMESH_MEMORY_RETRIEVE_MIN_SCORE` | `0.65` | 显式记忆查询的最低向量分数，偏向召回率 |
| `memory.auto-probe-min-score` | `ZHIMESH_MEMORY_AUTO_PROBE_MIN_SCORE` | `0.72` | 隐式 AUTO 探测的最低向量分数 |
| `memory.auto-probe-candidate-results` | `ZHIMESH_MEMORY_AUTO_PROBE_CANDIDATE_RESULTS` | `3` | AUTO 每个记忆库重排前的候选数 |
| `memory.auto-probe-max-results` | `ZHIMESH_MEMORY_AUTO_PROBE_MAX_RESULTS` | `1` | AUTO 每个记忆库最终允许进入 Prompt 的数量 |
| `memory.explicit-candidate-results` | `ZHIMESH_MEMORY_EXPLICIT_CANDIDATE_RESULTS` | `5` | 显式记忆请求每路重排前的候选数 |
| `memory.explicit-max-results` | `ZHIMESH_MEMORY_EXPLICIT_MAX_RESULTS` | `3` | 显式记忆请求每路最终允许进入 Prompt 的数量 |
| `memory.episodic-dedup-min-score` | `ZHIMESH_MEMORY_EPISODIC_DEDUP_MIN_SCORE` | `0.92` | 情景记忆近重复写入阈值 |
| `memory.update-lock-wait-ms` | `ZHIMESH_MEMORY_UPDATE_LOCK_WAIT_MS` | `120000` | 后台语义合并等待角色锁的最长时间 |
| `memory.update-lock-lease-ms` | `ZHIMESH_MEMORY_UPDATE_LOCK_LEASE_MS` | `60000` | 语义合并分布式锁的可续租租期 |

这些默认值是本次止血基线，不应视为跨模型永久常量。更换 embedding 或 rerank 模型后，应使用标注集重新校准绝对阈值；在没有评估证据前，不建议把 `relevance-gate-fail-open` 改为 `true`。

## 8. P3：后续可观察性（本轮不实施）

后续为每条回答持久化：

- 最终意图、置信度、margin、识别器版本；
- 是否发生 fallback 及原因；
- 每个来源的启动状态；
- raw candidate 数、accepted evidence 数；
- 原始分数、重排分数、拒绝原因；
- 最终进入 Prompt 的 token 数。

管理端增加一次回答的检索决策面板，并基于真实标注集评估来源启动准确率、Precision@K、空结果正确率与回答污染率。

## 9. 回归测试矩阵

| 输入 | 预期 |
| --- | --- |
| 你好 | 所有外部来源关闭 |
| 谢谢 | 所有外部来源关闭 |
| 火影忍者里的面具男是谁 + 技术知识库 | 可执行受限 AUTO 记忆探测并产生知识候选，但最终记忆与知识证据均为空 |
| 根据知识库总结版本治理 | 向量或 BM25 有最终证据 |
| 我喜欢吃什么 | 语义记忆开启 |
| 我之前让你抓取过哪个网站 | 情景记忆开启 |
| 还是按老规矩来 | 语义记忆开启 |
| 那个牛客网链接再发我一次 | 情景记忆开启 |
| 上次使用的是哪个网站 | 先通过相关性门槛，再在相关候选中优先较新的事件 |
| 我曾经使用过哪些网站 | 不启用近期加权，按语义相关性排序 |
| A 服务和 B 服务是什么关系 | 图谱路由开启 |
| GET /api/users 返回 E10023 | BM25 精确标识符检索保留 |
| 火影忍者面具男 + 技术知识库 | 范围门控判为 UNRELATED；影子期记录但不拦截，正式期跳过知识库意图与路由 |
| 那个呢 | 范围门控为 UNCERTAIN，继续原知识库链路 |
| 根据知识库说明部署流程 | 显式保护，直接继续原知识库链路 |
| 知识库向量索引未完成或探测异常 | fail-open 为 UNCERTAIN |

### 9.1 本轮自动化验证

已执行：

```powershell
mvn.cmd -pl zhimesh-common '-Dtest=KnowledgeScopePreflightGateTest,PrefetchedVectorContentRetrieverTest,IntentQueryEmbeddingReuseTest,MemoryRetrievalPolicyTest' test
mvn.cmd -pl zhimesh-common test
mvn.cmd -DskipTests compile
pnpm.cmd run build
```

结果：

- 知识库范围门控、向量复用、查询向量复用与记忆独立性聚焦回归：27 个测试通过，0 失败、0 错误；
- 长期记忆原子更新、分布式锁、动态时间、可信时间校验、跨日事件去重与时间排序聚焦回归：17 个测试通过，0 失败、0 错误；
- `zhimesh-common` 全量回归：269 个测试通过，0 失败、0 错误；
- 后端 Reactor 五个模块全部编译成功；
- 用户端 `vue-tsc --noEmit` 与 Vite 生产构建成功。

## 10. 数据兼容与历史记录

- 不修改已有回答的标签：旧标签准确反映当时确实进入 Prompt 的错误证据。
- 不删除已有引用表记录，以保留事故审计能力。
- P2 只阻止新重复记忆；历史近重复记忆的离线清理应单独执行并生成审计报告。
- 所有新阈值通过配置项提供，便于按 embedding/rerank 模型校准。

## 11. 风险与回滚

- 风险：门控过严导致应检索的问题返回空证据。
- 缓解：显式知识库请求使用 `REQUIRED`；开放聊天使用 `AUTO`；严格知识库问答保留原有 strict 行为。
- 回滚：关闭新的 relevance gate 配置可以恢复旧融合行为；记忆查询策略可单独回滚，不影响知识库路由。
- 禁止回滚引用语义：原始候选不得再次冒充最终证据。
