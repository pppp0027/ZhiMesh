# 检索 Query Embedding 复用与远程 Embedding 模型接入优化计划

> 文档状态：第一阶段已实施并通过测试；第二阶段待实施  
> 适用范围：`server/zhimesh-common`、`server/zhimesh-bootstrap`、模型管理配置、向量存储及评测脚本  
> 核心原则：两次优化严格分阶段实施；第一阶段不换模型、不迁数据，第二阶段才接入远程模型并重建向量  
> 当前默认基线：`local:bge-small-zh-v1.5` + PostgreSQL/pgvector + Apache AGE

## 1. 文档目的

当前一次角色聊天最多会触发三条向量检索路线：

1. 角色绑定知识库的文档向量检索；
2. 角色语义记忆向量检索；
3. 角色情景记忆向量检索。

三条路线使用同一个全局 `EmbeddingModel`，查询文本也都是本次用户问题，但每个
`ZhiMeshEmbeddingStoreContentRetriever` 都会独立执行一次：

```java
Embedding embeddedQuery = embeddingModel.embed(query.text()).content();
```

因此，当前实现复用了检索代码，却没有复用同一次请求已经计算出的 Query Embedding。
使用本地模型时，这会重复消耗 CPU；切换到远程模型后，则会形成三次内容相同的 HTTP
请求，并放大延迟、费用、限流和失败概率。

本计划将改造拆成两个边界明确、可独立验收的阶段：

| 阶段 | 名称 | 是否更换模型 | 是否修改向量表 | 是否重建向量 | 主要收益 |
| --- | --- | --- | --- | --- | --- |
| 第一阶段 | 单次请求 Query Embedding 复用 | 否 | 否 | 否 | 将一次聊天的三次问题向量化降为一次 |
| 第二阶段 | 远程 Embedding 模型接入与迁移 | 是 | 是 | 是 | 支持 SiliconFlow 等远程模型，并安全迁移向量空间 |

两阶段不得合并上线。第一阶段属于等价执行优化；第二阶段属于检索模型和数据版本变更，
风险、验证方法和回滚方式完全不同。

---

# 第一阶段：单次请求 Query Embedding 复用

> 实施状态：已完成（2026-08-04）。本阶段未更换模型、未修改数据库、未重建向量。

## 2. 第一阶段目标

在不改变以下内容的前提下，消除同一个问题的重复向量化：

- 不改变当前 Embedding 模型；
- 不改变知识库、语义记忆和情景记忆中的任何已有向量；
- 不改变 `Top-K`、`minScore` 和元数据过滤条件；
- 不改变知识库向量与图谱混合检索逻辑；
- 不改变最终 Prompt 的内容结构；
- 不新增数据库迁移。

目标执行结构：

```text
用户问题 queryText
        │
        ├─ EmbeddingModel.embed(queryText)（只执行一次）
        │                         │
        │                         ├─ 知识库向量检索
        │                         ├─ 语义记忆向量检索
        │                         └─ 情景记忆向量检索
        │
        └─ 原始文本继续供知识图谱检索使用
```

## 3. 当前执行链与重复点

当前入口位于：

```text
CharacterChatService.executeChat()
  └─ CharacterChatHelper.retrieve()
       ├─ semantic RetrieverWrapper
       ├─ episodic RetrieverWrapper
       └─ knowledge-base RetrieverWrapper
```

外层通过 `mainExecutor` 并行运行三个 Wrapper。知识库 Wrapper 内部再通过
`ragRetrievalExecutor` 并行运行向量和图谱路线。

重复不是发生在向量数据库搜索阶段，而是发生在每个向量 Retriever 的入口：

```text
semantic retriever  ── embed(queryText) ── semantic store.search(...)
episodic retriever  ── embed(queryText) ── episodic store.search(...)
knowledge retriever ── embed(queryText) ── knowledge store.search(...)
```

由于三者当前共享同一个 Spring `EmbeddingModel`，三次计算得到的是同一向量空间中的同一
Query Embedding，具备安全复用的前提。

## 4. 第一阶段设计

### 4.1 新增请求级检索上下文

建议新增不可变对象：

```java
public record RetrievalQueryContext(
        String text,
        Embedding embedding
) {
}
```

该对象只在一次 `retrieve()` 调用期间存在，不写数据库、不进入全局缓存、不跨用户共享。

设计约束：

1. `text` 保留给图谱检索、日志长度统计和 LangChain4j `Query` 使用；
2. `embedding` 供所有使用同一 Embedding 模型的向量路线复用；
3. 对象不可变，允许多个并发 Retriever 安全读取；
4. 不在对象中保存用户 ID、原始附件或其他无关上下文；
5. 请求结束后由 JVM 正常回收，不形成长期敏感数据缓存。

### 4.2 在并行分叉前计算一次

推荐由一个 Spring 管理的检索协调器负责，而不是继续扩大静态工具类职责：

```java
@Service
public class CharacterRetrievalCoordinator {

    private final EmbeddingModel embeddingModel;

    public List<RetrieverWrapper> retrieve(...) {
        Embedding embedding = embeddingModel.embed(queryText).content();
        RetrievalQueryContext context =
                new RetrievalQueryContext(queryText, embedding);

        // 创建 semantic、episodic、knowledge-base retriever
        // 并将同一个 context/embedding 传给三条向量路线
    }
}
```

若第一轮只追求最小改动，也可以暂时保留 `CharacterChatHelper`，在方法参数中显式传入
`EmbeddingModel` 或预计算的 `Embedding`。不建议通过 `SpringUtil.getBean()` 在静态方法中
临时获取模型，因为这会继续隐藏依赖并降低单元测试可控性。

### 4.3 扩展 RetrieverCreateParam

建议在 `RetrieverCreateParam` 中增加可选字段：

```java
private Embedding queryEmbedding;
```

三个向量 Retriever 使用同一个值：

```java
RetrieverCreateParam.builder()
        .queryEmbedding(queryContext.embedding())
        .filter(...)
        .maxResults(3)
        .minScore(RAG_RETRIEVE_MIN_SCORE_DEFAULT)
        .breakIfSearchMissed(false)
        .build();
```

图谱 Retriever 忽略该字段，继续使用原始 `Query.text()`。

### 4.4 ZhiMeshEmbeddingStoreContentRetriever 兼容改造

`ZhiMeshEmbeddingStoreContentRetriever` 增加可选的预计算向量：

```java
private final Embedding queryEmbedding;
```

执行时优先使用预计算结果：

```java
Embedding embeddedQuery = queryEmbedding != null
        ? queryEmbedding
        : embeddingModel.embed(query.text()).content();
```

必须保留回退分支。原因是该 Retriever 可能被聊天链路以外的知识库查询、评测或工作流调用；
未传 `queryEmbedding` 的旧调用仍应维持原行为。

### 4.5 保持现有并发结构

优化后仅将 Query Embedding 提前到并行分叉之前，数据库检索仍保持并发：

```text
同步执行：一次 query embedding
                │
                ├─ mainExecutor：semantic vector search
                ├─ mainExecutor：episodic vector search
                └─ mainExecutor：knowledge hybrid search
                                      ├─ ragRetrievalExecutor：vector
                                      └─ ragRetrievalExecutor：graph
```

`CountDownLatch`、向量/图谱独立超时、重试、去重、RRF、BGE rerank 和上下文 Token 预算均保持
现状。

## 5. 不采用全局 Query 缓存的原因

第一阶段不应增加 `Map<String, Embedding>` 形式的全局缓存。主要风险包括：

- 用户问题可能包含隐私，长期驻留内存不符合最小留存原则；
- 缓存没有自然上限，容易造成内存增长；
- 模型切换后可能错误复用旧模型生成的向量；
- 相同文本可能跨用户、跨租户共享缓存生命周期；
- 失效、并发去重和指标统计会使简单优化复杂化。

本阶段只做请求级复用：一次请求创建、一次请求消费、请求结束释放。

## 6. 第一阶段涉及文件

预计修改范围：

| 文件/包 | 修改内容 |
| --- | --- |
| `common/vo/RetrieverCreateParam.java` | 增加可选的 `queryEmbedding` |
| `common/vo/` 或 `common/rag/` | 新增 `RetrievalQueryContext` |
| `common/rag/ZhiMeshEmbeddingStoreContentRetriever.java` | 优先使用预计算向量，保留原逻辑兜底 |
| `common/rag/EmbeddingRag.java` | 将 `queryEmbedding` 传入 Retriever |
| `common/util/CharacterChatHelper.java` | 在并行检索前建立共享上下文，或迁移到协调器 |
| `common/service/CharacterChatService.java` | 若采用协调器，改为注入并调用协调器 |
| `common/service/LocalAgentService.java` | 同步使用新的共享检索入口 |
| 相关单元测试 | 验证一次向量化、多路搜索及兼容回退 |

数据库、前端和 API DTO 不需要修改。

## 7. 第一阶段测试方案

### 7.1 单元测试

使用计数型 `EmbeddingModel`：

```java
AtomicInteger embedCalls = new AtomicInteger();
EmbeddingModel countingModel = text -> {
    embedCalls.incrementAndGet();
    return ...;
};
```

至少覆盖：

1. 同时启用知识库、语义记忆、情景记忆时，`embedCalls == 1`；
2. 没有绑定知识库时，两个记忆通道仍然只向量化一次；
3. 只有单一路线时仍可正常检索；
4. 未提供预计算向量的旧 Retriever 调用仍自动执行 `embeddingModel.embed()`；
5. 三个向量搜索收到的 Query Embedding 内容一致；
6. 图谱 Retriever 收到的原始问题文本不变；
7. 某一路线失败时，其他路线和已有失败处理语义不变。

### 7.2 结果等价测试

固定以下输入：

- 同一数据库快照；
- 同一 Embedding 模型；
- 同一问题集合；
- 同一 `Top-K`、`minScore`、rerank 配置；
- 同一线程池配置。

比较优化前后：

- 每条路线召回的 embedding ID；
- 相似度分数和排序；
- 去重前候选、去重后候选；
- 最终进入 Prompt 的文本；
- 引用标记 `isRefEmbedding`、`isRefGraph`、`isRefMemoryEmbedding`。

除并列分数可能引起的稳定排序差异外，结果应保持一致。

### 7.3 性能测试

至少记录：

| 指标 | 基线 | 目标 |
| --- | --- | --- |
| 单次完整聊天 Query Embedding 调用次数 | 最多 3 次 | 固定 1 次 |
| Query Embedding 总耗时 | 三次累计 | 接近单次耗时 |
| 本地模型 CPU 时间 | 基线值 | 明显下降 |
| 检索阶段 P50/P95/P99 | 基线值 | 不劣化，远程模型场景应明显改善 |
| 向量检索结果一致率 | - | 100% |

### 7.4 并发测试

并发请求必须各自拥有独立 `RetrievalQueryContext`。测试两个用户同时提交不同问题时：

- 每个问题各向量化一次；
- 不发生向量串用；
- 不存在静态字段保存“当前 Query Embedding”；
- 线程池饱和和 `CallerRunsPolicy` 下仍保持结果正确。

## 8. 第一阶段发布与回滚

建议增加短期兼容开关：

```yaml
zhimesh:
  retrieval:
    reuse-query-embedding: true
```

发布策略：

1. 测试环境开启，运行固定问题集做结果等价验证；
2. 预发布环境采集 Embedding 调用次数和检索延迟；
3. 生产灰度开启；
4. 观察远程错误率、CPU、P95 和检索空结果比例；
5. 稳定后移除旧路径和临时开关。

回滚只需关闭开关或回退代码，不涉及数据库和向量数据。

## 9. 第一阶段验收标准

- 一次角色聊天最多只调用一次 Query Embedding；
- 知识库、语义记忆和情景记忆复用同一请求级向量；
- 知识图谱仍使用原始问题文本；
- 检索结果、引用信息和最终 Prompt 与基线等价；
- 不新增跨请求缓存；
- 不修改、删除或重建已有向量表；
- `CharacterChatService` 与 `LocalAgentService` 均覆盖新路径；
- 单元测试、并发测试和性能基准通过。

---

# 第二阶段：远程 Embedding 模型接入与向量迁移

> 实施状态：未开始。本阶段不属于本次代码改动范围。

## 10. 第二阶段启动条件

第二阶段必须在第一阶段稳定上线后启动，并满足：

1. Query Embedding 已确认一次请求只调用一次；
2. 已建立固定检索评测集和当前本地 BGE 基线；
3. 已确认远程模型名称、维度、价格、速率限制和服务可用性；
4. 已制定全量重建、灰度切换和回滚方案；
5. 已备份现有知识库、语义记忆和情景记忆向量表。

远程模型是否免费、免费额度和限流策略可能变化，实施时必须以供应商控制台和模型列表为准，
不能把“当前免费”写成系统长期假设。

## 11. 当前远程模型支持现状

项目当前 `BeanConfig.initEmbeddingModel()` 支持：

```text
local:all-minilm-l6-v2
local:bge-small-zh-v1.5
DashScope Embedding
OpenAI Embedding
```

虽然项目已经支持 SiliconFlow 的聊天、图像、ASR 和 TTS，但没有
`SiliconflowEmbeddingModelService`，`platform == siliconflow` 会落入“不支持的 Embedding
模型”异常。

SiliconFlow 官方提供 `POST /v1/embeddings`，支持经典文本 Embedding 和部分 Qwen3
可变维度 Embedding。实施时参考：

- [SiliconFlow Create embeddings](https://docs.siliconflow.cn/en/api-reference/embeddings/create-embeddings)
- [SiliconFlow List models](https://docs.siliconflow.cn/en/api-reference/models/get-model-list)

## 12. 第二阶段目标

- 支持通过配置选择 SiliconFlow Embedding 模型；
- 优先复用 OpenAI-compatible 客户端能力，但保留供应商差异处理层；
- 模型名称、平台、维度共同决定向量存储版本；
- 新旧模型向量绝不写入同一物理表或索引；
- 支持知识库、语义记忆和情景记忆的可恢复重建；
- 支持影子评测、灰度切换和快速回滚；
- 远程模型不可用时不静默改用另一个向量空间。

## 13. 模型选择范围

第二阶段第一版建议继续采用“系统级全局 Embedding 模型”：

```yaml
zhimesh:
  embedding-model: siliconflow:Qwen/Qwen3-Embedding-0.6B
```

知识库、语义记忆、情景记忆仍共享同一个模型，以保证第一阶段的 Query Embedding 可以安全
复用。

第一版不建议实现：

- 用户在每次聊天时临时选择 Embedding 模型；
- 每个角色使用不同 Embedding 模型；
- 同一知识库同时混用多个模型；
- 远程失败后自动降级到本地模型并继续搜索旧表。

这些能力会使一个问题必须产生多份 Query Embedding，并引入模型版本路由、数据隔离和结果融合
问题，已经超出本轮目标。

## 14. SiliconFlow 适配设计

### 14.1 新增专用 Service

推荐新增：

```java
public class SiliconflowEmbeddingModelService
        extends AbstractEmbeddingModelService {

    @Override
    public EmbeddingModel buildModel() {
        // 使用 OpenAI-compatible Embedding 客户端
        // baseUrl、apiKey、modelName 来自 ModelPlatform/AiModel
        // dimensions 按模型能力选择性发送
    }
}
```

不建议简单地把所有 SiliconFlow 模型无条件转交给现有
`OpenAiEmbeddingModelService`，因为现有实现总会调用 `.dimensions(...)`，而供应商并非所有
Embedding 模型都接受自定义维度。专用 Service 应根据模型能力决定是否发送 `dimensions`。

### 14.2 BeanConfig 增加平台分支

目标逻辑：

```java
if (DASHSCOPE.equals(aiModel.getPlatform())) {
    return new DashScopeEmbeddingModelService(...).buildModel();
}
if (OPENAI.equals(aiModel.getPlatform())) {
    return new OpenAiEmbeddingModelService(...).buildModel();
}
if (SILICONFLOW.equals(aiModel.getPlatform())) {
    return new SiliconflowEmbeddingModelService(...).buildModel();
}
throw new IllegalArgumentException("Unsupported embedding platform: ...");
```

### 14.3 模型配置

`adi_ai_model` 中至少需要正确配置：

```text
platform = siliconflow
model_type = embedding
name = 供应商模型 ID
properties.dimension = 实际输出维度
input_types 包含 text
is_enable = true
```

`adi_model_platform` 中配置：

```text
base_url = https://api.siliconflow.cn/v1
api_key = 通过安全配置注入
```

API Key 不得写入文档、Git、普通日志或前端响应。

## 15. 修正向量表版本命名

当前远程模型表后缀为：

```text
platform + dimension
```

例如：

```text
siliconflow_1024
```

这不足以隔离模型。两个不同模型即使同为 1024 维，也处于不同向量空间，不能混合搜索。

第二阶段必须改为包含模型身份：

```text
platform + sanitized_model_name + dimension + optional_revision
```

示例：

```text
siliconflow_qwen_qwen3_embedding_0_6b_1024_v1
```

对应表：

```text
adi_knowledge_base_embedding_siliconflow_qwen_qwen3_embedding_0_6b_1024_v1
adi_character_memory_embedding_siliconflow_qwen_qwen3_embedding_0_6b_1024_v1
adi_character_episodic_memory_embedding_siliconflow_qwen_qwen3_embedding_0_6b_1024_v1
```

命名生成器必须：

- 只产生数据库允许的字符；
- 控制 PostgreSQL 标识符长度；
- 对完整模型 ID 生成稳定短 Hash，避免截断碰撞；
- 模型 revision 变化时生成新版本；
- 在日志中记录模型 ID、维度和目标表，但不记录 API Key。

## 16. 向量迁移原则

切换模型不能只修改配置。必须使用新模型重新生成所有向量，因为：

- 模型维度可能不同；
- 即使维度相同，语义空间也不同；
- 新问题向量无法与旧模型生成的文档/记忆向量可靠比较。

需要重建三类数据：

| 数据 | 重建来源 | 是否需要再次调用长期记忆提取 LLM |
| --- | --- | --- |
| 知识库向量 | 原始文档或规范化 Chunk | 否 |
| 语义记忆向量 | 旧 TextSegment 文本及 metadata | 否 |
| 情景记忆向量 | 旧事件摘要及 metadata | 否 |

语义和情景记忆迁移应直接读取旧向量表中保存的 TextSegment 文本和 metadata，再使用新模型
生成向量。不得重新对历史聊天执行记忆提取，否则可能产生内容漂移、重复事件和额外 LLM
费用。

## 17. 迁移实施步骤

### 17.1 冻结基线

记录：

- 当前代码提交或完整补丁；
- 当前 Embedding 模型、维度和表名；
- 三类向量表行数；
- 固定问题集、期望引用和 RAGAS/确定性评测结果；
- 当前检索 P50/P95/P99、空召回率和失败率；
- 数据库快照。

### 17.2 创建新版本影子表

新模型只能写入全新表，不覆盖旧表：

```text
old tables：继续服务线上读取
new tables：后台重建、校验、影子评测
```

### 17.3 分批重建

重建任务必须支持：

- 固定批大小；
- 持久化 checkpoint；
- 失败重试和指数退避；
- 供应商 429/5xx 分类处理；
- 幂等写入；
- 进度、失败数量和 Token/调用量统计；
- 暂停与恢复；
- 单批事务边界，避免一个大事务。

远程接口支持批量输入时，应批量提交多个文本，降低 HTTP 往返和吞吐压力；批量大小必须受模型
单次输入限制和供应商限流约束。

### 17.4 完整性校验

逐类验证：

```text
源记录数 = 成功记录数 + 明确失败记录数
新表中不存在旧模型向量
向量维度全部等于目标 dimension
metadata 中 character_id、kb_uuid、memory_type 等关键字段完整
随机抽样文本与 metadata 一致
```

### 17.5 影子检索评测

线上仍返回旧模型结果，同时对抽样问题异步执行新模型检索，只记录非敏感指标：

- Top-K ID 重合率；
- Recall@K、MRR、nDCG；
- RAGAS faithfulness/relevancy；
- 语义记忆命中率；
- 情景记忆命中率；
- 空召回率；
- 远程模型 P50/P95/P99；
- 429、超时、5xx 比例；
- 单请求调用量和成本。

### 17.6 原子切换

使用一个明确的模型版本配置同时切换三类向量表：

```text
active_embedding_version = siliconflow_qwen..._v1
```

不能分别切换知识库、语义记忆和情景记忆，否则第一阶段共享的 Query Embedding 可能被用于
不同模型空间。

### 17.7 回滚与清理

切换后保留旧表一个观察周期。若新模型出现质量或稳定性问题：

1. 将 active version 指回旧版本；
2. 恢复本地 Embedding 模型；
3. 不需要反向迁移数据；
4. 分析问题后重新生成新的版本表。

旧表只能在观察期结束、备份完成、回滚演练通过后删除。

## 18. 远程模型运行时治理

### 18.1 超时与重试

Embedding 请求必须有独立于聊天 LLM 的：

- 连接超时；
- 响应超时；
- 最大重试次数；
- 仅对明确可重试错误执行退避；
- 429 尊重服务端限流提示；
- 熔断与恢复探测。

不能在远程请求失败时自动使用本地模型查询远程模型生成的表。不同模型空间不兼容，正确行为
应是返回可识别的检索降级状态，或在产品允许时跳过 RAG 后继续回答。

### 18.2 批量与限流

入库和重建优先使用批量 Embedding；在线 Query Embedding 为单文本请求。第一阶段完成后，
一次聊天只需一次远程 Query Embedding 请求。

需要分别限制：

- 在线查询并发；
- 后台重建并发；
- 单批文本数量和总 Token；
- 用户流量与迁移任务的资源配额。

后台迁移不得挤占在线检索额度。

### 18.3 可观测性

至少采集：

```text
embedding_provider
embedding_model
embedding_dimension
operation=query|ingest|rebuild
batch_size
input_count
duration_ms
status
retry_count
rate_limited
```

不得记录完整用户问题、完整文档片段、API Key 或响应向量。

## 19. 第二阶段测试方案

### 19.1 适配测试

- SiliconFlow base URL、API Key、模型 ID 正确传入；
- 支持维度参数的模型按配置发送 `dimensions`；
- 不支持自定义维度的模型不发送该字段；
- 返回向量维度与配置完全一致；
- 401、403、429、5xx、超时和非法响应均能正确分类；
- 日志中不出现 API Key 和完整敏感输入。

### 19.2 向量空间隔离测试

- 不同模型、相同维度生成不同表版本；
- 新模型不会读取旧表；
- 三类 Store 必须绑定同一 active model version；
- 启动时发现模型、维度、表版本不一致应立即失败，而不是带病运行。

### 19.3 迁移测试

- 支持中途中断后从 checkpoint 恢复；
- 重复执行不会产生重复记录；
- metadata 完整保留；
- 失败记录可以单独重试；
- 回滚后旧模型结果恢复；
- 不重新调用长期记忆提取 LLM。

### 19.4 质量门禁

新模型是否“更高级”不能只依据参数规模或供应商描述，需要通过项目数据判断。上线门禁至少包括：

- 核心评测集 Recall@K 不低于基线；
- 最终回答忠实度不低于基线；
- 中文、英文、混合语言问题分别测试；
- 知识库、语义记忆、情景记忆分别统计；
- P95 满足产品延迟预算；
- 远程失败率和限流率可接受；
- 成本满足预算。

## 20. 第二阶段发布与回滚

推荐使用模型版本开关：

```yaml
zhimesh:
  embedding-model: siliconflow:目标模型ID
  embedding-version: 目标版本
```

发布顺序：

1. 上线 SiliconFlow 适配代码，但 active version 仍指向本地模型；
2. 建立新表并完成后台重建；
3. 完成完整性和离线质量验证；
4. 开启影子检索，不影响用户结果；
5. 小流量切换新版本；
6. 扩大流量并持续观察；
7. 保留旧表和旧配置直到观察期结束。

回滚是切回旧模型版本和旧表，不允许把远程模型生成的新向量交给本地模型继续查询。

## 21. 第二阶段验收标准

- SiliconFlow Embedding 可以通过模型平台配置正确创建；
- 一次聊天只产生一次远程 Query Embedding 请求；
- 模型 ID 和维度共同隔离向量表；
- 知识库、语义记忆、情景记忆全部完成新模型重建；
- 新旧模型影子评测和质量门禁通过；
- 429、超时和服务端错误具备可观察、可重试、可熔断行为；
- 可以在不迁移数据的情况下快速切回旧模型和旧表；
- API Key、问题文本和响应向量不进入普通日志。

---

# 两阶段边界与最终决策

## 22. 明确禁止的混合实施方式

以下做法均不可接受：

1. 在第一阶段顺便修改 Embedding 模型；
2. 只修改 `embedding-model` 配置，不重建三类向量；
3. 不同模型共用 `platform_dimension` 表；
4. 远程请求失败后，用本地模型查询远程模型向量表；
5. 知识库已切新模型，但长期记忆仍使用旧模型；
6. 使用全局无限缓存替代请求级 Query Embedding 复用；
7. 重放全部历史聊天重新提取长期记忆；
8. 未经过影子评测直接删除旧表。

## 23. 推荐最终路线

```text
第一阶段
  单次请求 Query Embedding 复用
  ├─ 不换模型
  ├─ 不迁数据
  ├─ 不改结果
  └─ 独立上线并验收
          │
          ▼
第二阶段
  SiliconFlow 远程 Embedding 接入
  ├─ 模型适配
  ├─ 表版本隔离
  ├─ 三类向量重建
  ├─ 影子评测
  └─ 灰度切换与可回滚
```

第一阶段解决重复计算问题，是低风险的执行效率优化；第二阶段解决模型能力和检索质量问题，
属于需要数据迁移与质量评估的基础设施升级。两者目标相关，但不能用同一套发布和回滚策略。
