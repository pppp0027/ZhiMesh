# RAG 意图识别与检索路由四阶段实施计划（历史文档）

> **已被三路主计划取代。** 当前项目只实现文档知识库上的 Vector、Graph、BM25，Wiki、Title Lookup和第四分类头已退出当前范围。后续开发与项目讲解以 `three-route-rag-development-plan.zh-CN.md` 为唯一依据；本文中的Wiki/四输出内容仅保留为历史设计记录，不再执行。

> 文档状态：待实施  
> 适用范围：`server/zhimesh-common`、`server/zhimesh-bootstrap`、RAG 评估接口、数据标注与模型产物  
> 核心目标：在不降低检索召回与回答可信度的前提下，让高置信度普通知识问答跳过图谱路线，降低 RAG P50/P95 延时  
> 核心原则：先评估、后切流；先冻结 Embedding、后训练分类头；任何不确定或异常情况都回退 `HYBRID`  
> 非本计划范围：Wiki 表结构、BM25 索引、知识库权限体系重构、回答模型替换

## 1. 文档目的

当前知识库检索默认使用 `HYBRID`：向量路线与图谱路线并行执行，之后统一完成去重、RRF、Rerank、多样化和 Token 剪裁。图谱路线在无法直接匹配实体时还会调用 LLM 提取实体，因此普通说明、配置、操作步骤等问题也可能等待较慢的图谱路线。

本计划将意图识别拆成四个可独立交付、可独立验收、可随时回退的阶段：

| 阶段 | 名称 | 在线改变检索行为 | 是否训练模型 | 主要产物 |
| --- | --- | --- | --- | --- |
| 第一阶段 | 规则 + Embedding 原型分类 | 否，默认仅本地评估 | 否 | 稳定接口、原型集、独立验证集 |
| 第二阶段 | Shadow 观测与真实数据闭环 | 否，实际仍执行 Hybrid | 否 | 真实观测数据、人工标签、阈值报告 |
| 第三阶段 | 冻结 Embedding + 轻量分类头 | 是，先灰度 | 只训练分类头 | Logistic Regression/小型 MLP 产物 |
| 第四阶段 | 专用意图模型微调 | 是，满足触发条件才实施 | 是，独立意图模型 | 专用 Encoder/ONNX 模型 |

四阶段必须顺序执行。不得在没有独立验证集和 Shadow 证据的情况下直接把线上默认模式从 `HYBRID` 改为 `VECTOR`。

---

## 2. 当前实现基线

### 2.1 当前知识库检索链路

```text
用户问题
  -> RetrieverCreateParam（默认 RetrievalMode.HYBRID）
  -> CompositeRag.createRetriever()
       -> Vector Retriever
       -> Graph Retriever
  -> DeduplicatingContentRetriever（两路并行）
       -> 精确去重
       -> RRF
       -> Rerank 或 RRF fallback
       -> 每文档多样化
       -> 相对分数剪裁
       -> Token Budget 打包
  -> LLM 回答
```

已经具备的基础能力：

1. `CompositeRag` 已支持 `VECTOR / GRAPH / HYBRID` 三种模式，未显式指定时默认 `HYBRID`。
2. `DeduplicatingContentRetriever` 已通过 `ragRetrievalExecutor` 并行执行向量和图谱路线。
3. 每条路线已有状态、耗时、候选和失败信息，可通过 `getRouteResults()` 读取。
4. 图谱检索先尝试直接实体命中，失败时才调用 LLM 做实体抽取。
5. `RetrieverCreateParam.queryEmbedding` 已支持传入预计算查询向量。
6. `ZhiMeshEmbeddingStoreContentRetriever` 会优先使用预计算向量，避免重复调用 Embedding 模型。
7. Character 聊天入口已通过 `RetrievalQueryContext` 在知识库、语义记忆、情景记忆之间复用一次 Query Embedding。
8. `KnowledgeBaseService.evaluateAsk()` 已能显式执行单一检索模式，并返回候选、路线耗时、Rerank、graph trace 和配置快照。

### 2.2 当前主要延时来源

因为向量和图谱并行，Hybrid 检索耗时近似为：

```text
Hybrid Retrieval ≈ max(Vector Route, Graph Route) + Fusion/Rerank/Pack
```

当图谱直接实体匹配失败时：

```text
Graph Route = 图实体候选查询 + LLM 实体抽取 + 图谱漫游 + 原文证据解析
```

意图路由的优化目标不是减少 RRF 或 Rerank，而是对“明确不需要关系推理”的问题安全跳过 Graph Route。

### 2.3 必须覆盖的业务入口

实施时至少检查以下调用点：

1. `KnowledgeBaseService` 普通知识库问答。
2. `KnowledgeBaseService.evaluateAsk()` 评估入口；显式模式不得被意图路由覆盖。
3. `CharacterChatHelper.retrieve()` 中的知识库 Retriever。
4. `KnowledgeRetrievalNode`；工作流显式配置优先于自动意图路由。
5. 其他创建 `RetrieverCreateParam` 的入口。

第一至第三阶段只自动路由 `RetrieveContentFrom.KNOWLEDGE_BASE`。角色语义记忆和情景记忆继续保持向量检索，不因知识库意图分类被关闭。

---

## 3. 目标架构与不可变约束

### 3.1 目标分层

```text
业务入口
  -> RetrievalQueryContext（原始问题 + 一次 Query Embedding）
  -> IntentRecognizer
       -> RuleIntentRecognizer
       -> PrototypeIntentRecognizer / LinearRoutingIntentRecognizer
  -> IntentRoutingPolicy
  -> RetrievalPlan
  -> AuthorizedKnowledgeScope（只保留当前用户有权访问的数据源）
  -> RetrieverCapabilityRegistry（剔除未部署或未就绪的路线）
  -> CompositeRag
  -> 现有融合、重排、剪裁链路
```

从第一阶段开始就把“知识来源”和“检索方法”建模为两个正交维度：

```text
KnowledgeSourceType：去哪里查
  DOCUMENT_KB
  WIKI

RetrievalRoute：怎么查
  VECTOR
  GRAPH
  BM25
  TITLE_LOOKUP
```

当前只启用 `DOCUMENT_KB + VECTOR/GRAPH`。Wiki 表和 BM25 索引仍不在本计划内实施，但公共契约、观测模型、数据集格式和融合元数据必须从第一阶段就允许这些值存在。

### 3.2 意图定义

第一版固定为：

```java
public enum QueryIntent {
    NO_RAG,
    KNOWLEDGE_LOOKUP,
    RELATIONSHIP,
    UNCERTAIN
}
```

定义边界：

| 意图 | 定义 | 示例 |
| --- | --- | --- |
| `NO_RAG` | 问候、感谢、告别等不需要外部知识的问题 | “你好”“谢谢” |
| `KNOWLEDGE_LOOKUP` | 定义、说明、配置、步骤、故障排查、普通文档问答 | “Redis 怎么配置？” |
| `RELATIONSHIP` | 答案核心依赖实体关系、上下游、依赖、调用链、路径或多跳推理 | “哪些服务依赖 Redis？” |
| `UNCERTAIN` | 缺少上下文、代词不明确、类别分数接近或分类器失败 | “它有什么影响？” |

`QueryIntent` 继续用于规则、原型识别和日志解释。第三阶段不再训练一个互斥的三分类 Softmax，而是训练统一的多标签路由分类器；当没有任何能力头达到阈值时产生 `UNCERTAIN`。

第三阶段固定保留四个独立二分类输出：

```text
vectorSufficient
graphRequired
bm25Required
titleLookupRequired
```

`vectorSufficient=true` 表示 Vector 单路已经足够，因此不能与任一 `*Required=true` 同时作为有效标签。其余三个 Required 输出可以同时为 true。当前仅训练并启用前两个输出；BM25/Wiki 索引上线并获得消融标签前，后两个标签保持 `null`，模型头保持 `enabled=false`。

### 3.3 第一版路由策略

```text
NO_RAG             -> 默认 {DOCUMENT_KB} × {VECTOR, GRAPH}；验收后才允许 routes={}
KNOWLEDGE_LOOKUP   -> 达标时 {DOCUMENT_KB} × {VECTOR}，否则 {VECTOR, GRAPH}
RELATIONSHIP       -> {DOCUMENT_KB} × {VECTOR, GRAPH}
UNCERTAIN          -> {DOCUMENT_KB} × {VECTOR, GRAPH}
```

第一版不自动选择 `GRAPH`。图谱证据通常仍需要向量原文补充，且关系意图误判的质量风险高于多执行一次向量检索的延时成本。

### 3.4 优先级

检索模式决策顺序必须固定：

```text
显式评估/工作流模式
  > 权限与 Retriever 可用性
  > 自动意图路由
  > 默认 HYBRID
```

当前 `RetrieverCreateParam.retrievalMode` 使用 `@Builder.Default HYBRID`，无法区分“调用方显式指定 Hybrid”和“调用方没有指定”。第一阶段必须先修正这个契约，二选一：

1. 推荐：移除字段的 Builder 默认值，让 `null` 明确表示没有显式模式；`CompositeRag` 已具备 `null -> HYBRID` fallback，因此功能关闭时行为不变。
2. 备选：保留默认值，但增加独立的 `retrievalModeExplicit` 标记，且所有入口必须正确传递。

评估接口和工作流配置传入显式模式；普通聊天入口传入 `null`，由 `IntentRoutingPolicy` 决定建议模式。不得通过“值是不是 HYBRID”推断它是否显式。

### 3.5 不可变约束

1. 任何分类超时、模型不兼容、产物损坏、解析失败都回退 `HYBRID`。
2. 意图识别不得改变知识库权限过滤条件。
3. 意图识别不得让模型直接输出知识库 ID、Top-K、SQL 或任意底层参数。
4. 同一个请求的 Query Embedding 只计算一次。
5. 第一至第三阶段不得修改现有文档向量空间，也不得要求重建知识库向量。
6. 现有去重、RRF、Rerank、多样化和 Token 剪裁顺序保持不变。
7. 显式 `VECTOR / GRAPH / HYBRID` 评估请求必须绕过自动路由。
8. 路由优化采用非对称风险原则：错走 `HYBRID` 只增加延时，错走 `VECTOR` 可能丢失关系证据，因此优先保证 VECTOR 路由精确率。
9. Wiki 是数据来源，BM25 是检索方法；任何类型、字段、指标和表结构都不得把两者放进同一个枚举维度。
10. 模型只能输出逻辑 `sourceHints` 和语义信号，最终知识库 UUID 必须由服务端权限范围求交得到。
11. 新路线未部署、索引未就绪或超时时，按照确定性 fallback 矩阵降级，不能让意图模型临时生成降级策略。

---

## 4. 公共代码契约

四阶段共享以下接口，后续仅替换实现，不改业务调用方。

### 4.1 IntentDecision

```java
public record IntentDecision(
        QueryIntent intent,
        double confidence,
        double margin,
        Set<IntentSignal> signals,
        Set<KnowledgeSourceType> sourceHints,
        String recognizer,
        String reason,
        Map<RoutingCapability, Double> routeScores,
        Set<RoutingCapability> activatedCapabilities
) {
}
```

`confidence` 在原型阶段表示相似度聚合分数，在分类头阶段表示当前解释意图所对应能力头的概率。业务层不能假设两者天然同尺度。分类器使用模型产物中每个输出各自的阈值；原型识别继续使用配置中的 score/margin 阈值。

`sourceHints` 不是授权结果；例如模型识别出 `WIKI`，服务端仍必须在当前用户已授权且已绑定的 Wiki 知识库中检索。`signals` 用于保持四类意图稳定，同时为未来路线提供细粒度依据：

```java
public enum IntentSignal {
    NO_RAG_PATTERN,
    RELATION_QUERY,
    EXACT_IDENTIFIER,
    EXPLICIT_WIKI,
    AMBIGUOUS_CONTEXT
}
```

例如错误码、类名、配置名仍可归类为 `KNOWLEDGE_LOOKUP`，同时携带 `EXACT_IDENTIFIER`；未来策略据此增加 BM25，而不需要新增一个互斥意图类别。

### 4.2 IntentRecognizer

```java
public interface IntentRecognizer {
    IntentDecision recognize(IntentRoutingContext context);
}
```

### 4.3 IntentRoutingContext

```java
public record IntentRoutingContext(
        String query,
        Embedding queryEmbedding,
        RetrievalMode explicitMode,
        Set<KnowledgeSourceType> authorizedSources,
        Set<RetrievalRoute> availableRoutes,
        String embeddingModelId
) {
}
```

第一阶段不把短期记忆传入分类器。短问题或代词问题通过 `AMBIGUOUS_CONTEXT` 明确拒识为 `UNCERTAIN`；后续若增加最近一条用户消息，也不得把完整短期记忆无界传入分类器。

### 4.4 RetrievalPlan

```java
public record RetrievalPlan(
        IntentDecision decision,
        RetrievalSelection proposed,
        RetrievalSelection effective,
        boolean shadow,
        boolean fallback,
        String reason
) {
}
```

`proposed` 只用于观测，调用方只能执行 `effective`，防止 Shadow 建议被误当作实际切流结果。

相关枚举从第一阶段建立：

```java
public enum KnowledgeSourceType {
    DOCUMENT_KB,
    WIKI,
    CHARACTER_MEMORY,
    EPISODIC_MEMORY,
    WEB
}

public enum RetrievalRoute {
    VECTOR,
    GRAPH,
    BM25,
    TITLE_LOOKUP
}
```

当前 `RetrievalMode` 仅作为兼容适配层，不再作为长期核心契约：

```text
VECTOR -> routes={VECTOR}
GRAPH  -> routes={GRAPH}
HYBRID -> routes={VECTOR, GRAPH}
```

新增 `LegacyRetrievalModeAdapter` 负责双向转换。当 routes 包含 BM25/TITLE_LOOKUP 时，不允许静默转换成旧枚举，必须进入通用多路 Retriever 构建流程。

### 4.5 路线描述与融合契约

当前 `DeduplicatingContentRetriever` 通过具体 Java 类型判断 route 名称，只认识 Vector/Graph。第一阶段应在保持结果不变的前提下改成显式描述：

```java
public record RetrievalRouteKey(
        KnowledgeSourceType sourceType,
        RetrievalRoute route,
        String sourceInstanceId
) {
}

public record RoutedRetriever(
        RetrievalRouteKey key,
        ContentRetriever delegate
) {
}
```

每个候选必须携带：

```text
source_type
route_type
source_instance_id
route_rank
```

这样未来可同时存在：

```text
DOCUMENT_KB + VECTOR
DOCUMENT_KB + BM25
WIKI + TITLE_LOOKUP
WIKI + BM25
WIKI + VECTOR
WIKI + GRAPH
```

RRF、Rerank 和 Token 剪裁继续处理统一 `RetrievedCandidate`。每条路线必须有独立 candidate limit、timeout 和状态，防止新增路线淹没其他证据。路线权重只能由版本化 `IntentRoutingPolicy` 配置，不能由模型任意输出。

`TITLE_LOOKUP` 首先用于解析 Wiki 页面标题/别名。未来 `WikiRetrievalOrchestrator` 在来源内部负责“标题定位 -> 页面范围内 BM25/Vector”的先后依赖，并把三个子步骤继续按独立 route 上报；公共 `RetrievalPlan` 不保存运行时才知道的 page UUID。不得把整篇超长 Wiki 页面直接作为一个候选塞入 Prompt。

### 4.6 能力与降级矩阵

新增 `RetrieverCapabilityRegistry`，按来源实例记录哪些索引已经就绪：

```text
DOCUMENT_KB: VECTOR=true, GRAPH=true, BM25=false
WIKI:        VECTOR=false, GRAPH=false, BM25=false, TITLE_LOOKUP=false
```

`IntentRoutingPolicy` 先生成期望计划，再与权限和能力求交。建议降级矩阵：

| 期望路线 | 不可用时 |
| --- | --- |
| `TITLE_LOOKUP` | 继续 Wiki BM25/Vector；均不可用则移除该来源 |
| `BM25` | 保留同来源 Vector |
| `GRAPH` | 保留同来源 Vector，并记录 `GRAPH_UNAVAILABLE` |
| `VECTOR` | 保留同来源已就绪的 BM25/Graph；无路线时按 strict 语义明确失败或无证据 |

任何 fallback 都必须保留原权限过滤条件。能力为空时不得回退到未授权的其他知识库。

### 4.7 建议包结构

```text
com.pppp.zhimesh.common.rag.intent
  QueryIntent.java
  IntentSignal.java
  IntentDecision.java
  IntentRoutingContext.java
  IntentRecognizer.java
  IntentRoutingPolicy.java
  RetrievalPlan.java
  RetrievalSelection.java
  KnowledgeSourceType.java
  RetrievalRoute.java
  RetrievalRouteKey.java
  RoutedRetriever.java
  RetrieverCapabilityRegistry.java
  AuthorizedKnowledgeScope.java
  LegacyRetrievalModeAdapter.java
  RuleIntentRecognizer.java
  PrototypeIntentRecognizer.java
  IntentPrototypeCatalog.java
  LinearRoutingIntentRecognizer.java     # 第三阶段
  RoutingClassifierModelCatalog.java     # 第三阶段
  IntentObservationService.java          # 第二阶段
```

---

# 第一阶段：规则 + Embedding 原型分类

## 5. 第一阶段目标

完成一个不训练模型、不改变线上检索行为的意图识别实现，并建立独立验证集。

第一阶段结束时应满足：

1. 可以输入问题和预计算 Query Embedding，输出四类意图之一。
2. 原型样本可版本化，不写死在业务代码中。
3. 查询向量不会因为意图识别被重复计算。
4. 分类异常统一返回 `UNCERTAIN`。
5. `IntentRoutingPolicy` 能生成建议模式，但生产默认仍不切流。

## 6. 第一阶段任务

### 6.1 建立原型数据文件

新增：

```text
server/zhimesh-common/src/main/resources/rag/intent-prototypes.zh-CN.json
```

建议格式：

```json
{
  "version": "prototype-v1",
  "embeddingModelIdentity": "local:bge-small-zh-v1.5",
  "intents": {
    "NO_RAG": [
      "你好",
      "谢谢你的回答",
      "再见"
    ],
    "KNOWLEDGE_LOOKUP": [
      "Redis 怎么配置",
      "如何创建知识库",
      "文档上传失败怎么办",
      "短期记忆保存在哪里"
    ],
    "RELATIONSHIP": [
      "哪些服务依赖 Redis",
      "用户和角色之间是什么关系",
      "这个模块的上下游有哪些",
      "A 调用了哪些服务"
    ]
  }
}
```

每个可判定意图先准备 20～50 条高质量原型，优先覆盖表达差异，不用大量添加同义改写。`UNCERTAIN` 不建立中心原型，通过拒识条件产生。

### 6.2 原型向量生命周期

必须记录：

- Embedding 模型身份。
- 向量维度。
- 原型文件版本。
- 原型内容 hash。

原型向量可在启动后首次使用时惰性生成并缓存。生成失败不能阻止应用启动，当前请求回退 `HYBRID`。如果后续使用预计算原型向量，产物必须同时校验模型身份和维度。

### 6.3 相似度聚合算法

推荐算法：

1. 查询向量与每个意图的全部原型计算余弦相似度。
2. 每类取 Top-3 相似度平均，避免单个偶然样本主导结果。
3. 最高类别得分记为 `s1`，第二类别得分记为 `s2`。
4. `margin = s1 - s2`。
5. `s1` 或 `margin` 未达到当前分类器版本阈值时，返回 `UNCERTAIN`。

```text
intentScore = average(top3(cosine(query, prototypes[intent])))
```

第一阶段不要把余弦相似度称为“概率”。

### 6.4 高确定性规则

规则只处理低风险、高确定性情况：

- 空白问题返回 `UNCERTAIN`。
- 明确问候、感谢、告别可以建议 `NO_RAG`。
- 明确出现“依赖哪些、上下游、调用链、谁调用、经过哪些节点”等组合模式时增加 `RELATIONSHIP` reason code，但不因单个“连接”“关系”词直接判定。
- 错误码、类名、配置项、API 路径等只增加 `EXACT_IDENTIFIER` signal；当前仍走 Vector/Hybrid，BM25 上线后由策略增加关键词路线。
- 用户明确说“Wiki/维基/页面标题”时增加 `EXPLICIT_WIKI` 和 `sourceHints={WIKI}`；Wiki 能力未注册时不得伪造结果或绕过现有授权知识库。
- 短问题、明显代词且无可用上文时返回 `UNCERTAIN`。

所有规则必须可单测，并记录 `reasonCodes`；不允许散落在 Controller/Service 中。

### 6.5 请求级向量复用

Character 路径继续使用现有 `RetrievalQueryContext`。

执行顺序应为：

```text
显式模式检查
  -> 高确定性 NO_RAG/空白规则
  -> 如果仍需语义分类，再创建 RetrievalQueryContext
  -> Query Embedding 同时交给 Prototype 和 Vector Retriever
```

这样显式 `GRAPH` 和经批准的高确定性 `NO_RAG` 不会为了意图分类额外生成无用向量；原本需要 Vector/Hybrid 的问题仍然只生成一次向量。

KnowledgeBase 直接问答入口需要调整为：

```text
question
  -> RetrievalQueryContext.create(question, embeddingModel)
       -> embedding() 给 IntentRecognizer
       -> embedding() 给 RetrieverCreateParam.queryEmbedding
```

新增或保留测试，断言一次请求只调用一次 `embeddingModel.embed(question)`。

### 6.6 建立通用来源/路线骨架

第一阶段虽然只实际执行 Vector/Graph，也必须同步完成以下兼容重构：

1. `CompositeRag` 内部把当前 Retriever 包装为 `RoutedRetriever`。
2. `DeduplicatingContentRetriever` 不再通过 `instanceof` 推断 route 名称，而读取 `RetrievalRouteKey`。
3. `RetrievedCandidate` metadata 统一写入 `source_type/route_type/source_instance_id/route_rank`。
4. `LegacyRetrievalModeAdapter` 保证旧三种模式产生与改造前完全一致的 Retriever 集合。
5. 当前 RRF 公式、候选顺序、Rerank、保护向量证据和 Token 剪裁结果保持等价。
6. 先生成最终 `RetrievalPlan`，再构建 Graph ChatModel；只有 routes 包含 `GRAPH` 时才允许调用 `buildChatLLM()` 创建图谱查询模型。
7. `AuthorizedKnowledgeScope` 必须在创建 Retriever filter 前完成，任何 source hint 都只能与授权 KB UUID 集合求交。

本步骤属于为 Wiki/BM25 预留的必要兼容改造，但不得在第一阶段创建 Wiki 表、全文索引或空实现 Retriever。

### 6.7 配置

建议在 `ZhiMeshProperties` 新增：

```java
private IntentRouting intentRouting = new IntentRouting();

@Data
public static class IntentRouting {
    private boolean enabled = false;
    private String executionMode = "SHADOW";
    private String recognizerType = "PROTOTYPE";
    private String fallbackMode = "HYBRID";
    private boolean noRagEnforceEnabled = false;
    private String prototypeResource = "classpath:rag/intent-prototypes.zh-CN.json";
    private double minTopScore = 1.1D;
    private double minScoreMargin = 1.1D;
}
```

初始阈值故意设置为不可切流值，必须通过第二阶段数据校准后才能降低。配置示例：

```yaml
zhimesh:
  intent-routing:
    enabled: false
    execution-mode: SHADOW
    recognizer-type: PROTOTYPE
    fallback-mode: HYBRID
    no-rag-enforce-enabled: false
    prototype-resource: classpath:rag/intent-prototypes.zh-CN.json
    min-top-score: 1.1
    min-score-margin: 1.1
```

### 6.8 第一阶段测试

新增测试：

```text
RuleIntentRecognizerTest
PrototypeIntentRecognizerTest
IntentPrototypeCatalogTest
IntentRoutingPolicyTest
IntentQueryEmbeddingReuseTest
```

至少覆盖：

1. 普通知识问答原型命中。
2. 关系问题原型命中。
3. 第一名与第二名接近时拒识。
4. 原型为空、模型身份不一致、维度不一致时回退。
5. 单个“连接”词不会强制关系意图。
6. 显式 RetrievalMode 优先。
7. 分类失败不影响原有 Hybrid 检索。
8. 同一问题只生成一次 Query Embedding。
9. `RetrievalMode.VECTOR/GRAPH/HYBRID` 与新 routes 集合转换正确。
10. RoutedRetriever 重构前后的 Vector/Graph RRF 和最终 selected 顺序一致。
11. source/route metadata 完整，未来枚举值序列化不会破坏现有字段。
12. 未授权的 source hint 被权限求交移除。
13. 期望路线未就绪时严格按能力降级矩阵执行。

### 6.9 第一阶段验收门槛

- 单元测试全部通过。
- 原型集和独立验证集无重复、近重复泄漏。
- 分类器本地相似度计算开销可忽略；单独记录分类开销，不把已有 Embedding 延时计入新增开销。
- `enabled=false` 时行为与改造前完全一致。
- 旧 RetrievalMode 兼容测试证明输出路线、RRF 分数和选中上下文没有变化。
- 公共计划对象已经能够表达 `WIKI + TITLE_LOOKUP/BM25/VECTOR`，但不会执行尚未注册的能力。
- 尚不得在线启用 VECTOR 自动切流。

---

# 第二阶段：Shadow 观测与真实数据闭环

## 7. 第二阶段目标

线上或预生产环境执行意图预测，但实际检索模式仍保持原有显式模式或 `HYBRID`，收集真实路线贡献、延时和人工标签，用于校准阈值和训练第三阶段分类头。

```text
predictedMode = VECTOR
effectiveMode = HYBRID
```

## 8. Shadow 观测数据

### 8.1 建议新增表

实施时使用 `server/db_migration` 中下一个可用序号，不在计划阶段锁死编号。例如：

```text
server/db_migration/NNN_add_rag_intent_observation.sql
```

建议表：

```sql
CREATE TABLE adi_rag_intent_observation (
    id                       bigserial PRIMARY KEY,
    uuid                     varchar(32) NOT NULL,
    request_uuid             varchar(32) NOT NULL DEFAULT '',
    question_ref_type        varchar(32) NOT NULL DEFAULT '',
    question_ref_uuid        varchar(32) NOT NULL DEFAULT '',
    sanitized_question       text,
    question_hash            varchar(64) NOT NULL,

    predicted_intent         varchar(32) NOT NULL,
    top_score                double precision NOT NULL DEFAULT 0,
    score_margin             double precision NOT NULL DEFAULT 0,
    legacy_proposed_mode     varchar(16) NOT NULL DEFAULT '',
    legacy_effective_mode    varchar(16) NOT NULL DEFAULT '',
    proposed_sources         jsonb NOT NULL DEFAULT '[]'::jsonb,
    proposed_routes          jsonb NOT NULL DEFAULT '[]'::jsonb,
    effective_sources        jsonb NOT NULL DEFAULT '[]'::jsonb,
    effective_routes         jsonb NOT NULL DEFAULT '[]'::jsonb,
    reason_codes             jsonb NOT NULL DEFAULT '[]'::jsonb,

    classifier_type          varchar(32) NOT NULL,
    classifier_version       varchar(64) NOT NULL,
    embedding_model_identity varchar(255) NOT NULL,

    retrieval_latency_ms     bigint NOT NULL DEFAULT 0,

    review_status            varchar(16) NOT NULL DEFAULT 'PENDING',
    expected_intent          varchar(32) NOT NULL DEFAULT '',
    legacy_expected_mode     varchar(16) NOT NULL DEFAULT '',
    expected_sources         jsonb NOT NULL DEFAULT '[]'::jsonb,
    expected_routes          jsonb NOT NULL DEFAULT '[]'::jsonb,
    reviewer_id              bigint NOT NULL DEFAULT 0,
    reviewed_at              timestamp,
    review_note              text NOT NULL DEFAULT '',

    create_time              timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time              timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE adi_rag_intent_route_observation (
    id                       bigserial PRIMARY KEY,
    uuid                     varchar(32) NOT NULL,
    observation_uuid         varchar(32) NOT NULL,
    source_type              varchar(32) NOT NULL,
    route_type               varchar(32) NOT NULL,
    source_instance_id       varchar(64) NOT NULL DEFAULT '',
    status                   varchar(16) NOT NULL DEFAULT '',
    latency_ms               bigint NOT NULL DEFAULT 0,
    candidate_count          integer NOT NULL DEFAULT 0,
    selected_count           integer NOT NULL DEFAULT 0,
    error_type               varchar(128) NOT NULL DEFAULT '',
    create_time              timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (observation_uuid, source_type, route_type, source_instance_id)
);

CREATE UNIQUE INDEX uk_rag_intent_observation_uuid
    ON adi_rag_intent_observation(uuid);
CREATE INDEX idx_rag_intent_observation_review
    ON adi_rag_intent_observation(review_status, create_time);
CREATE INDEX idx_rag_intent_observation_version
    ON adi_rag_intent_observation(classifier_version, create_time);
CREATE UNIQUE INDEX uk_rag_intent_route_observation_uuid
    ON adi_rag_intent_route_observation(uuid);
CREATE INDEX idx_rag_intent_route_observation_parent
    ON adi_rag_intent_route_observation(observation_uuid);
CREATE INDEX idx_rag_intent_route_observation_route
    ON adi_rag_intent_route_observation(source_type, route_type, create_time);

CREATE TRIGGER trigger_rag_intent_observation_update_time
    BEFORE UPDATE ON adi_rag_intent_observation
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
```

是否保存 `sanitized_question` 必须受配置和数据治理控制。默认只保存引用 UUID 和 hash；训练环境需要原文时，应先脱敏，再显式开启保存。

路线明细使用子表而不是 `vector_* / graph_*` 固定列，这是后续接入 Wiki、BM25、TITLE_LOOKUP 时不改主表的关键。`legacy_*_mode` 仅保留当前三模式评估兼容和报表便利，长期真值以 sources/routes 集合为准；新增逻辑不得根据 legacy mode 反推出多路计划。

数据库改造同时更新：

- `server/db_migration/all_ddl.sql`。
- 数据库中英文 README 的版本顺序。
- `verify_schema.sql` 的表、索引和触发器检查。
- 对应 Entity、Mapper、异步 Service 和清理任务。

### 8.2 异步写入

观测写入不得阻塞聊天主链路：

```text
请求结束
  -> 构造 IntentObservationEvent
  -> 有界异步队列
  -> 批量写入 PostgreSQL
```

要求：

- 队列满时丢弃观测并计数告警，不能拖慢用户请求。
- 数据库写入失败不影响回答。
- 不记录提示词、模型密钥或完整用户隐私字段。
- 观测保留周期和清理任务必须配置。

### 8.3 可复用的现有数据

从 `DeduplicatingContentRetriever.getRouteResults()` 获取：

- route 名称。
- route 状态。
- elapsedMs。
- 候选数量。

从候选和最终 selected content 的 metadata 统计：

- 每个 `source_type + route_type + source_instance_id` 的候选/入选数量。
- 当前 Vector/Graph 最终是否贡献回答上下文。
- 后续 Wiki/BM25/TITLE_LOOKUP 接入后使用同一统计逻辑，不新增固定字段。

`KnowledgeBaseService.evaluateAsk()` 继续作为离线对照入口，分别运行 `VECTOR / GRAPH / HYBRID`，不要被自动意图模式覆盖。

## 9. 标注规范

### 9.1 标注记录格式

导出 JSONL：

```json
{"id":"q-001","question":"Redis 怎么配置？","expectedIntent":"KNOWLEDGE_LOOKUP","expectedSources":["DOCUMENT_KB"],"expectedRoutes":["VECTOR"],"routeNecessity":{"GRAPH":false,"BM25":null},"groupId":"redis-config","reviewer":"u1"}
{"id":"q-002","question":"哪些服务依赖 Redis？","expectedIntent":"RELATIONSHIP","expectedSources":["DOCUMENT_KB"],"expectedRoutes":["VECTOR","GRAPH"],"routeNecessity":{"GRAPH":true,"BM25":null},"groupId":"redis-dependency","reviewer":"u1"}
{"id":"q-003","question":"Wiki 中的 A0071 是什么？","expectedIntent":"KNOWLEDGE_LOOKUP","expectedSources":["WIKI"],"expectedRoutes":["TITLE_LOOKUP","BM25","VECTOR"],"routeNecessity":{"GRAPH":false,"BM25":true},"groupId":"wiki-error-code","reviewer":"u1","futureCapability":true}
```

第二阶段即使 Wiki/BM25 尚未上线，也应保留少量 `futureCapability=true` 的评估样本，用于验证数据格式和 RoutingPolicy 不会把来源与方法混淆；它们不参与当前生产切流阈值计算。

### 9.2 标签判定

- 如果答案主要来自说明、步骤、配置或故障文档，标记 `KNOWLEDGE_LOOKUP`。
- 如果答案核心需要实体边、路径、依赖、上下游或多跳，标记 `RELATIONSHIP`。
- 如果不需要外部知识，标记 `NO_RAG`。
- 如果问题在给定上下文下仍无法确定，标记 `UNCERTAIN`，不强行猜测。
- “图谱本次没有返回结果”不等价于“问题不需要图谱”；标签以问题语义和正确证据需求为准。

### 9.3 必须包含的难例

每组至少覆盖下列边界：

| 普通知识问答 | 关系问题 |
| --- | --- |
| Redis 怎么连接？ | 哪些服务连接了 Redis？ |
| 用户角色怎么配置？ | 用户和角色是什么关系？ |
| A 模块怎么部署？ | A 模块依赖哪些服务？ |
| 数据库连接失败怎么办？ | 哪些组件会受数据库故障影响？ |

还要包含：短问题、错别字、口语、英文缩写、类名、配置名、代词追问、包含“关系/连接”但并非关系推理的问题。

### 9.4 数据集划分

按语义问题族 `groupId` 划分，不允许同一问题的改写同时出现在训练集和测试集。

推荐：

```text
train      70%
validation 15%
test       15%
```

第一阶段原型样本也不能进入独立 test 集。

## 10. Shadow 指标

必须输出：

1. 各意图样本数量和类别分布。
2. 混淆矩阵。
3. `KNOWLEDGE_LOOKUP -> VECTOR` 的 precision、recall。
4. `RELATIONSHIP` recall。
5. `UNCERTAIN` 比例。
6. 如果按预测路由，理论 Graph 跳过率。
7. 按 `source_type + route_type` 分组的路线、Rerank 和总 Retrieval P50/P95/P99。
8. 当前 Graph 入选证据比例；未来 Wiki/BM25 使用同一通用路线贡献率指标。
9. 按知识库、语言、问题长度分桶后的结果。

核心安全指标：

```text
VECTOR_SAFE_PRECISION
= 预测可以只走 VECTOR 的问题中，人工确认不需要 Graph 的比例
```

建议进入第三阶段的最低门槛：

- 已审核真实样本不少于 500 条。
- `KNOWLEDGE_LOOKUP` 与 `RELATIONSHIP` 各不少于 100 条。
- 每组主要混淆边界有独立难例。
- 当前原型分类的 VECTOR_SAFE_PRECISION 达到团队批准的安全目标；建议不低于 98%。
- `RELATIONSHIP` recall 建议不低于 95%。
- 所有结论来自独立 test 集，而不是原型或训练集。

样本数量只是最低启动条件，不代表天然达到生产质量。

## 11. 第二阶段配置与发布

```yaml
zhimesh:
  intent-routing:
    enabled: true
    execution-mode: SHADOW
    recognizer-type: PROTOTYPE
    no-rag-enforce-enabled: false
```

发布检查：

1. `effectiveMode` 仍为显式模式或 HYBRID。
2. 预测异常不影响请求。
3. 异步观测队列有容量、丢弃和失败指标。
4. 数据脱敏和保留周期经过确认。
5. Shadow 至少覆盖一个完整业务周期和主要知识库类型。

回滚只需：

```yaml
zhimesh.intent-routing.enabled: false
```

---

# 第三阶段：冻结 Embedding + 轻量分类头

## 12. 第三阶段目标

使用第二阶段审核数据训练一个轻量分类器。知识库 Embedding 模型保持冻结，同一 Query Embedding 同时用于意图分类和向量检索。

```text
EmbeddingModel（冻结）
  -> Query Embedding
       -> Intent Classification Head
       -> Vector Retrieval
```

第一版使用一个统一路由分类器，内部包含四个独立 Logistic Regression 二分类头。只有线性分类在独立测试集上不能达到门槛时，才尝试一层或两层小型 MLP；公共四输出契约不变。

## 13. 训练数据与特征

输入：

- 查询文本生成的固定维度 Embedding。
- 可选的非敏感结构特征：问题字符数、是否包含问号、是否包含关系组合词、是否包含不明确代词。

禁止把以下内容作为训练特征：

- 用户 ID。
- 知识库 ID。
- 请求时间。
- 可能造成业务环境泄漏的固定标识。

训练标签固定为三态多标签：

```text
vectorSufficient: true | false | null
graphRequired: true | false | null
bm25Required: true | false | null
titleLookupRequired: true | false | null
```

`null` 表示当前检索基础设施无法为该能力生成监督信号，不能当作负样本。Vector/Graph 标签来自三次 retrieval-only 消融结果与人工复核；BM25/Wiki 上线前，后两个标签全部保持 `null`。生产训练默认只接受 `labelStatus=REVIEWED`。

## 14. 训练脚本与产物

已新增：

```text
ragas-evaluation/scripts/routing/
  build_vector_graph_labels.py
  validate_routing_dataset.py
  split_routing_dataset.py
  train_routing_classifier.py
```

标准执行顺序：

```powershell
python scripts/routing/build_vector_graph_labels.py --vector vector.jsonl --graph graph.jsonl --hybrid hybrid.jsonl --output review.jsonl
# 人工复核并把可用样本标记为 REVIEWED
python scripts/routing/validate_routing_dataset.py --input review.jsonl
python scripts/routing/split_routing_dataset.py --input review.jsonl --output split.jsonl
python scripts/routing/validate_routing_dataset.py --input split.jsonl --for-training
python scripts/routing/train_routing_classifier.py --input split.jsonl --output retrieval-router-v1.json --model-version retrieval-router-v1
```

分类器产物至少包含：

```json
{
  "schemaVersion": 1,
  "modelVersion": "retrieval-router-v1",
  "embeddingModel": "local:bge-small-zh-v1.5",
  "embeddingDimension": 512,
  "normalization": "l2",
  "trainingDatasetSha256": "...",
  "outputs": {
    "vectorSufficient": {"enabled": true, "weights": [], "bias": 0.0, "threshold": 0.98},
    "graphRequired": {"enabled": true, "weights": [], "bias": 0.0, "threshold": 0.50},
    "bm25Required": {"enabled": false, "weights": [], "bias": 0.0, "threshold": 1.0},
    "titleLookupRequired": {"enabled": false, "weights": [], "bias": 0.0, "threshold": 1.0}
  }
}
```

示例数值只是结构示例，最终阈值必须来自 validation 集并在 test 集冻结验证。

模型产物还要包含权重、偏置和校准参数。运行时加载时必须验证：

- artifact schema version。
- Embedding 模型身份。
- Embedding 维度。
- 四个输出是否完整、当前启用头是否符合版本约束。
- checksum。

任何校验失败都禁用分类头并回退 `HYBRID`。

## 15. 运行时实现

新增 `LinearRoutingIntentRecognizer`：

1. 接收已存在的 Query Embedding。
2. 执行与训练一致的归一化。
3. 为四个输出分别计算 sigmoid 概率。
4. 应用每个输出在 artifact 中独立保存的阈值。
5. 返回 `IntentDecision`。

分类计算不得调用 Python 子进程。推荐：

- Logistic Regression：Java 直接执行点积和 sigmoid。
- MLP/Encoder：使用版本固定的 ONNX Runtime，需独立评估依赖体积和本地线程数。

## 16. 第三阶段灰度策略

### 16.1 Shadow 对比

先同时运行 Prototype 与 Classifier：

```text
primaryDecision   = classifier
compareDecision   = prototype
effectiveMode     = HYBRID
```

确认分类器指标稳定后进入 Enforce。

### 16.2 Enforce 只开放 VECTOR

第一批只允许：

```text
高置信度 KNOWLEDGE_LOOKUP -> VECTOR
其余                         -> HYBRID
```

`NO_RAG` 跳过检索继续关闭。`RELATIONSHIP` 继续 Hybrid。

### 16.3 灰度维度

必须支持至少一种可控灰度方式：

- 按请求 hash 固定分桶。
- 按用户白名单。
- 按知识库白名单。

不能使用每次随机分桶导致同一用户行为反复变化。

建议顺序：

```text
0% Enforce（Shadow）
  -> 5%
  -> 20%
  -> 50%
  -> 100%
```

每一级必须经过完整指标窗口，不能只观察应用是否报错。

### 16.4 自动回退条件

出现以下任一情况立即将 Enforce 降为 Shadow：

- VECTOR 路由的无证据率显著上升。
- 人工抽检发现关系问题被路由到 VECTOR。
- 回答正确率、引用命中率或忠实度低于基线。
- 分类器产物与 Embedding 模型不兼容。
- 意图识别异常率或加载失败率超过告警阈值。

配置回滚：

```yaml
zhimesh.intent-routing.execution-mode: SHADOW
```

完全关闭：

```yaml
zhimesh.intent-routing.enabled: false
```

## 17. 第三阶段验收门槛

离线：

- 独立 test 集 VECTOR_SAFE_PRECISION 达标。
- RELATIONSHIP recall 达标。
- 分类概率经过校准，可靠性曲线可接受。
- 不同问题长度和主要知识库上没有明显偏置。

在线：

- Query Embedding 调用次数未增加。
- 意图分类本地新增耗时满足目标。
- VECTOR 路由比例与 Shadow 预估一致。
- Graph 路线调用量下降。
- 检索 P50/P95 明显改善，且 RAG 质量指标不低于基线。
- 现有 RRF/Rerank/剪裁结果在相同 RetrievalMode 下保持一致。

---

# 第四阶段：专用意图模型微调

## 18. 第四阶段触发条件

第四阶段不是必做项。只有满足以下条件才立项：

1. 已积累数千条经过审核的真实样本，并覆盖主要边界和业务语言。
2. Logistic Regression/小型 MLP 在稳定难例上无法达到门槛。
3. 错误来自语义表示不足，而不是标签定义冲突、数据污染或权限问题。
4. 具备模型训练、评估、部署、监控和回滚能力。
5. 预估收益能够覆盖新增模型运维成本。

## 19. 模型边界

推荐微调独立的意图分类 Encoder：

```text
intent-classifier-encoder-v1
```

不得直接替换知识库检索 Embedding 模型。两者必须独立版本管理：

```text
retrieval-embedding-model  -> 文档与查询向量检索
intent-classifier-model    -> 意图分类
```

因此第四阶段正常情况下也不需要重建知识库向量。

如果未来决定共享并修改 Retrieval Embedding 权重，则必须另立向量模型迁移计划，完成全量文档重嵌入、双索引验证和回滚；不得作为本计划的隐含步骤。

## 20. 数据与训练

训练集必须包含：

- 真实问题为主。
- 人工构造的关系/非关系最小对比对。
- LLM 合成数据只能作为补充，必须审核。
- 错别字、口语、中英文混合、短问题、追问。
- 历史误判形成的 hard negatives。

示例对比对：

```text
“Redis 怎么连接？”          -> KNOWLEDGE_LOOKUP
“哪些服务连接了 Redis？”    -> RELATIONSHIP

“角色权限怎么设置？”        -> KNOWLEDGE_LOOKUP
“角色和权限是什么关系？”    -> RELATIONSHIP
```

数据版本必须记录：来源范围、脱敏版本、标签指南版本、审核人、去重策略和 split hash。

## 21. 模型导出与部署

推荐导出 ONNX，并固定：

- tokenizer 版本与 hash。
- 最大输入长度。
- 模型 opset。
- Runtime 版本。
- CPU/GPU 执行提供者。
- 并发和线程参数。

部署方式二选一：

1. Java 进程内 ONNX：延时低，但增加 JAR、内存和本地线程管理复杂度。
2. 独立推理服务：易于独立扩缩容，但增加网络跳转和服务可用性依赖。

无论哪种方式，都必须设置超时、熔断和 `HYBRID` fallback。

## 22. 第四阶段对照实验

至少比较：

```text
Prototype
Logistic Regression
MLP（如存在）
Fine-tuned Encoder
```

统一使用同一冻结 test 集，比较：

- 各类 precision/recall/F1。
- VECTOR_SAFE_PRECISION。
- RELATIONSHIP recall。
- 拒识率。
- P50/P95 推理延时。
- CPU、内存和吞吐。
- 最终 RAG Recall@K、引用命中率和回答忠实度。

只有专用模型在质量或稳定性上显著优于第三阶段，并且延时满足目标，才允许替换轻量分类头。

---

## 23. Wiki 与关键词检索预留设计

本节只定义兼容边界，不在意图识别四阶段中创建 Wiki 数据表或 BM25 索引。后续实施必须遵守本节契约，避免重写路由、观测和融合层。

### 23.1 两个独立维度

```text
Wiki      = KnowledgeSourceType.WIKI（数据来源）
BM25      = RetrievalRoute.BM25（检索方法）
标题查找  = RetrievalRoute.TITLE_LOOKUP（确定性页面定位方法）
```

不允许创建含义混乱的单一枚举，例如把 `VECTOR/GRAPH/WIKI/BM25` 放在同一个 `RetrievalMode` 中。

### 23.2 未来检索计划示例

普通文档问答：

```json
{
  "sources": ["DOCUMENT_KB"],
  "routes": ["VECTOR"]
}
```

包含错误码或配置名的文档查询：

```json
{
  "sources": ["DOCUMENT_KB"],
  "routes": ["BM25", "VECTOR"]
}
```

明确指定 Wiki 页面或标题：

```json
{
  "sources": ["WIKI"],
  "routes": ["TITLE_LOOKUP", "BM25", "VECTOR"]
}
```

Wiki 中的依赖关系问题：

```json
{
  "sources": ["WIKI"],
  "routes": ["VECTOR", "GRAPH"]
}
```

信息不足时的安全计划：

```json
{
  "sources": ["DOCUMENT_KB", "WIKI"],
  "routes": ["BM25", "VECTOR", "GRAPH"]
}
```

最后一个计划只能在用户同时拥有两类来源权限、对应索引均就绪且候选预算允许时执行。

### 23.3 Wiki 接入边界

后续 Wiki 实施建议继续复用：

- `adi_knowledge_base` 作为 Wiki 空间。
- `adi_knowledge_base_item` 作为 Wiki 页面主内容。
- `adi_knowledge_base_chunk_set/chunk` 作为页面章节切分。
- 现有向量和图谱索引作为页面章节索引。

Wiki 特有页面层级、slug、external page ID、版本、同步状态和别名使用独立扩展表。标题/别名命中后，只把 page UUID 变成后续 BM25/Vector 的过滤范围；不要绕过 Chunk、权限、Rerank 和 Token Budget。

所有 Wiki Retriever 必须接收服务端生成的授权过滤条件。意图模型输出 `EXPLICIT_WIKI` 只表示来源偏好，不构成访问授权。

### 23.4 BM25 接入边界

BM25 可以由 PostgreSQL 全文索引或 Elasticsearch/OpenSearch 实现，但必须实现相同的 `ContentRetriever`/`RoutedRetriever` 契约，并返回：

```text
source_type
route_type=BM25
source_instance_id
route_rank
raw_score
kb_uuid
kb_item_uuid
chunk_uuid
```

中文 BM25 上线前必须独立验证分词方案。无论使用哪种后端，PostgreSQL 中的 canonical Chunk UUID 都是跨索引去重和证据追踪的稳定主键。

BM25 索引必须有 readiness 状态；重建或落后于当前 active chunk set 时不得被 `RetrieverCapabilityRegistry` 标记为可用。

### 23.5 Exact Identifier 与 Title Lookup

`EXACT_IDENTIFIER` 信号用于错误码、类名、配置名、API 路径等精确词，不等于把完整自然语言问题做字符串相等比较。

`TITLE_LOOKUP` 用于标准化后的 Wiki 标题/别名定位，处理流程：

```text
抽取页面名/别名
  -> 标准化
  -> 权限范围内精确查找页面
  -> 得到 page UUID
  -> 在页面 Chunk 内执行 BM25/Vector
```

标题未命中时继续 BM25/Vector；不能因为 Exact Match miss 就返回无答案。

### 23.6 多路融合预算

新增路线后，不能简单把每路 Top-K 全部拼接。后续实施必须配置：

- 每个 route/source 的候选上限。
- 每个 route 的 timeout 和失败状态。
- 全局 Rerank candidate limit。
- 每个来源和文档的多样化上限。
- 可选的版本化 Weighted RRF 权重。

默认先使用无权重 RRF，只有评估证明特定意图下需要提升 BM25 或 TITLE_LOOKUP 时才引入权重。权重来自配置和离线评估，不来自 LLM 临时输出。

### 23.7 后续接入顺序

Wiki/BM25 必须按以下顺序独立上线：

1. 完成数据模型、同步、Chunk 和权限过滤。
2. 建立 BM25/标题索引及 readiness 检查。
3. 使用显式 source/routes 运行离线评估。
4. Shadow 记录意图建议，但不自动选择新来源/路线。
5. 小流量启用高置信度 `EXACT_IDENTIFIER` 和 `EXPLICIT_WIKI`。
6. 观察召回、引用、延时和路线故障后逐步扩大。

意图分类器不需要为了“增加 Wiki 数据源”立即重训；规则可先产生 `EXPLICIT_WIKI`，分类器继续输出稳定的四类意图。只有真实数据证明现有表示无法区分新的路由边界时，再更新原型或分类器版本。

---

## 24. 数据质量与安全要求

1. 优先使用真实问题，其次人工问题，最后才是 LLM 合成问题。
2. 手机号、邮箱、Token、密钥、身份证号、业务私密标识必须脱敏。
3. 原始问题访问权限与生产聊天数据一致。
4. 合成改写必须按语义族分组后再切分，防止训练/测试泄漏。
5. 标签冲突必须由人工仲裁，不能用多数模型投票替代业务定义。
6. 数据集、原型集、分类器产物都必须版本化并可追溯。
7. 删除用户数据时，需要同步处理可直接关联到用户的训练候选数据。

---

## 25. 全阶段测试矩阵

### 25.1 单元测试

- 规则边界。
- 原型加载、hash 和模型身份校验。
- 相似度 Top-K 聚合。
- score/margin 拒识。
- 路由优先级。
- 分类异常 fallback。
- 分类头数学结果与训练脚本黄金样例一致。
- KnowledgeSourceType 与 RetrievalRoute 的序列化兼容。
- Legacy RetrievalMode 转换和不可转换组合显式报错。
- 能力矩阵降级不会扩大授权范围。

### 25.2 集成测试

- 高置信度普通问题只创建 Vector Retriever。
- 关系/不确定问题仍创建 Vector + Graph Retriever。
- 显式 Graph/Hybrid 不被覆盖。
- Character 语义/情景记忆检索不受知识库路由影响。
- 预计算 Query Embedding 传递到所有向量路线。
- Shadow 模式 proposed/effective mode 分离。
- 观测写入失败不影响主请求。
- 路线明细表可以同时记录同一来源的 Vector/Graph，以及不同来源的同名路线。
- 模拟注册 Wiki/BM25 能力时，计划、metadata、RRF 和观测无需修改表结构。
- Wiki source hint 在无权限时被移除，不能回退到其他未授权 Wiki。
- 不包含 GRAPH 的计划不会构建图谱 ChatModel，也不会触发实体抽取。

### 25.3 回归测试

```powershell
cd server
mvn -B -ntp -o -pl zhimesh-common -am -DskipTests compile
mvn -B -ntp -o -pl zhimesh-common test
mvn -B -ntp -o test
mvn -B -ntp -o clean package -DskipTests
```

### 25.4 性能测试

至少分别准备：

- 普通知识问答。
- 关系问答。
- 代词/不确定问答。
- 无需 RAG 的问候。

对比：

```text
固定 HYBRID 基线
Prototype Shadow 理论路由
Classifier Enforce 实际路由
```

报告 P50/P95/P99、吞吐、Graph LLM 调用次数和错误率。

---

## 26. 监控与告警

建议指标：

```text
rag_intent_decision_total{intent,version}
rag_intent_fallback_total{reason}
rag_intent_inference_duration_ms{type,version}
rag_intent_proposed_mode_total{mode}
rag_intent_effective_mode_total{mode}
rag_intent_source_total{source,effective}
rag_intent_route_total{source,route,effective}
rag_intent_shadow_disagreement_total
rag_retrieval_route_duration_ms{source,route,status}
rag_retrieval_route_candidates{source,route,selected}
rag_retrieval_capability_unavailable_total{source,route}
rag_graph_llm_extraction_total{status}
rag_intent_observation_dropped_total{reason}
```

日志只记录 request UUID、分类版本、意图、分数、margin、proposed/effective mode 和 reason code；不默认输出完整问题正文。

---

## 27. 分阶段上线与回滚总表

| 阶段 | 默认配置 | 线上行为 | 回滚 |
| --- | --- | --- | --- |
| 第一阶段 | `enabled=false` | 完全不变 | 无需回滚 |
| 第二阶段 | `enabled=true, mode=SHADOW` | 仍为 Hybrid，只记录建议 | `enabled=false` |
| 第三阶段 Shadow | `recognizer=CLASSIFIER, mode=SHADOW` | 仍为 Hybrid | 切回 PROTOTYPE 或关闭 |
| 第三阶段 Enforce | `mode=ENFORCE` + 固定灰度 | 高置信度普通问答走 Vector | 立即改为 SHADOW |
| 第四阶段 | 专用模型小流量 | 分类实现替换，路由策略不变 | 切回轻量分类头 |

所有开关必须支持通过外部配置修改。生产回滚不应要求重新打包，也不应修改知识库数据。

---

## 28. 最终完成标准

四阶段全部完成不等于必须启用第四阶段。生产目标在第三阶段达到以下条件即可视为核心优化完成：

1. 高置信度普通问题稳定路由到 VECTOR。
2. 关系和不确定问题稳定保留 HYBRID。
3. Query Embedding 调用次数没有增加。
4. 图谱 LLM 实体抽取调用量明显下降。
5. 检索 P50/P95 达到批准目标。
6. VECTOR_SAFE_PRECISION、RELATIONSHIP recall、Recall@K、引用命中率和回答忠实度均通过门槛。
7. 分类器和原型集可版本化、可观测、可灰度、可回滚。
8. 分类异常时系统自动回退 HYBRID。
9. 生产数据完成脱敏、访问控制和保留周期治理。
10. `RetrievalPlan`、观测子表、candidate metadata 和评估数据已经原生表达 Wiki/BM25，不需要修改公共接口或增加固定路线列。

---

## 29. 实施检查清单

### 第一阶段

- [x] 冻结四类意图定义和标注指南。
- [x] 建立原型数据与独立验证集。
- [x] 新增公共接口和 Prototype 实现。
- [x] 完成 KnowledgeSourceType/RetrievalRoute、RoutedRetriever 和旧模式适配。
- [x] 完成权限范围与 Retriever capability 求交。
- [x] 完成 Query Embedding 复用检查。
- [x] 完成单元测试，保持功能关闭。

### 第二阶段

- [ ] 新增 Shadow 观测迁移和异步写入。
- [ ] 接通路线耗时与候选贡献统计。
- [ ] 使用通用路线子表验证未来 Wiki/BM25 记录，无固定 Vector/Graph 列。
- [ ] 建立脱敏、导出、审核和数据集切分流程。
- [ ] 输出原型分类评估与理论延时收益报告。
- [ ] 通过进入第三阶段的数据和安全门槛。

### 第三阶段

- [x] 固定统一四输出、多标签分类器契约。
- [x] 完成 Vector/Graph 标签构建、复核校验、分组切分和训练脚本。
- [x] 完成 Java 线性分类头推理及模型身份、维度和 checksum 校验。
- [ ] 使用多知识库、人工复核数据训练生产 Logistic Regression 基线。
- [ ] 导出并审批生产分类器产物。
- [ ] 先 Shadow 对比，再按固定分桶灰度 Enforce。
- [ ] 证明质量不下降且延时改善。

### 第四阶段（按需）

- [ ] 证明轻量分类头的瓶颈来自语义表示不足。
- [ ] 建立独立意图模型训练与模型卡。
- [ ] 导出并压测 ONNX/推理服务。
- [ ] 与第三阶段在同一 test 集和在线灰度中比较。
- [ ] 保留一键切回轻量分类头的能力。

---

## 30. 实施状态记录

### 2026-08-19：第一阶段已完成

已完成规则识别、Embedding Top-3 原型分类、拒识阈值、来源/方法正交契约、显式 `RoutedRetriever`、通用 route rank RRF、权限与能力求交、旧 `RetrievalMode` 严格适配，以及知识库问答/角色聊天的 Shadow 接入。

生产默认值保持：

```text
enabled=false
executionMode=SHADOW
minTopScore=1.1
minScoreMargin=1.1
noRagEnforceEnabled=false
```

因此本阶段完成后可以打包部署，但不会自动将任何请求切换到 Vector-only，也不会创建 Wiki/BM25 Retriever、索引或数据库表。识别器、原型资源或向量维度发生异常时，实际计划统一回退当前 Hybrid；评估接口的显式模式继续拥有最高优先级。

验证结果：`zhimesh-common` 全量测试 114 项通过；全 Maven reactor 共 115 项测试通过。

### 2026-08-19：Vector/Graph 路由分类闭环骨架已完成

已完成统一四输出模型契约、Java 线性分类头推理、模型 SHA-256/Embedding 身份/维度校验，以及 Vector/Graph/Hybrid retrieval-only 消融数据的标签构建、人工复核校验、按 `groupId` 防泄漏切分、双 Logistic Regression 训练、阈值校准和模型导出。

当前不内置伪造的生产模型。默认识别器仍为 `PROTOTYPE`，默认执行仍为 `SHADOW`；只有收集带 Query Embedding 的新消融数据、完成人工复核并通过独立 test 集后，才允许部署分类器。BM25/Wiki 两个输出已预留，但在相应索引上线前保持 disabled。

本次闭环验证：Java 分类器/路由定向测试 17 项通过，Python 采集配置与训练流水线测试 11 项通过；使用历史 121 条三路结果成功生成 109 条 `AUTO_SUGGESTED`、6 条 `UNRESOLVED` 和 6 条 `EXCLUDED_UNANSWERABLE`。历史结果不含 Query Embedding，因此只用于标签构建冒烟，不用于训练。
