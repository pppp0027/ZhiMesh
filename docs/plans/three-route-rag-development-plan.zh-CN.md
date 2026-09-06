# ZhiMesh 三路 RAG 检索与意图路由开发主线

> 当前唯一有效的检索优化主计划  
> 当前范围：Vector、Graph、BM25  
> 明确非目标：Wiki知识源、Title Lookup、第四检索头  
> 更新日期：2026-08-20

## 1. 项目一句话定位

ZhiMesh在同一个文档知识库上建立语义向量、知识图谱和BM25关键词三种索引，通过统一融合链路保证召回质量，再用轻量意图分类器关闭不必要的检索分支以降低延迟。

项目只需要讲清楚两个层次：

```text
索引与检索层：Vector + Graph + BM25
路由优化层：判断Vector是否足够，以及是否必须增加Graph/BM25
```

Wiki是另一种知识来源，不属于当前三路检索优化，暂不开发、暂不训练、暂不出现在演示主线中。

## 2. 当前实现状态

### 2.1 已完成

Vector：

- 文档和Query Embedding。
- pgvector召回。
- Query Embedding请求级复用。
- 候选来源和rank元数据。

Graph：

- 图实体识别和图谱漫游。
- 关系描述与原始证据回溯。
- 独立超时和失败降级。

BM25：

- PostgreSQL `adi_knowledge_base_bm25_document/posting`表。
- canonical chunk级倒排索引。
- 中文CJK bigram/trigram与标识符分析器。
- Okapi BM25评分。
- 索引build、ACTIVE状态和analyzer version readiness。
- 文档重建和删除时同步维护。
- 独立Retriever、Top-K、超时、k1和b配置。
- 与Vector/Graph统一进入去重、RRF、Rerank和Token剪裁。
- 评估接口可以显式运行V、V+G、V+B、V+G+B。

融合层：

- 多路并行执行。
- canonical chunk去重。
- RRF融合。
- Cross-Encoder重排及失败回退。
- 每文档多样化和Token Budget。
- 每路rank、BM25 raw score、耗时和状态可观测。

意图路由骨架：

- 生产路径使用三头Linear Router，规则只负责安全拦截。
- 分类结果直接决定实际检索路线；模型异常或分支未就绪时回退请求基线。
- 三输出分类器契约。
- 模型身份、维度和SHA-256校验。

### 2.2 尚未完成

- 生产库迁移026的正式执行和回滚演练。
- 已有文档BM25全量补建及readiness核验。
- 三路离线消融数据采集。
- `bm25Required`人工审核标签。
- 三头生产分类器训练。
- 生产分类器模型部署和直接路由验收。

## 3. 当前在线检索基线

对BM25已经ready的知识库：

```text
固定质量基线 = Vector + Graph + BM25
```

三路并行后统一执行：

```text
精确去重
→ RRF
→ Rerank（失败则RRF fallback）
→ 每文档多样化
→ 相对分数剪裁
→ Token Budget打包
```

对BM25尚未ready的知识库：

```text
安全降级基线 = Vector + Graph
```

不得因为部分知识库缺少BM25索引，就在同一个请求中悄悄扩大或缩小授权范围。只有请求涉及的全部知识库都ready时，BM25才进入该请求的available routes。

## 4. 意图分类器最终定义

统一分类器使用同一个冻结Query Embedding，输出三个独立二分类结果：

```text
vectorSufficient
graphRequired
bm25Required
```

含义：

| 输出 | true含义 |
| --- | --- |
| `vectorSufficient` | Vector单路已经达到Gold证据质量门槛 |
| `graphRequired` | 选定的最低成本合格计划必须包含Graph |
| `bm25Required` | 选定的最低成本合格计划必须包含BM25 |

合法计划只有四种：

| 标签 | 实际路线 |
| --- | --- |
| `vectorSufficient=true` | V |
| `graphRequired=true` | V+G |
| `bm25Required=true` | V+B |
| `graphRequired=true,bm25Required=true` | V+G+B |

`vectorSufficient=true`不能与另外两个Required同时成立。三个头都没有达到阈值时拒识，回退当前请求基线。

## 5. 为什么保留Vector作为基础路线

- Graph主要补充实体关系和多跳路径，但最终回答通常仍需要原文证据。
- BM25擅长错误码、接口名、配置项、专名和原词精确命中，但不擅长同义表达。
- Vector提供最稳定的语义召回，是三种计划共同的安全底座。

因此当前分类器不是在V/G/B中三选一，而是在V基础上决定是否增加G和B。这使标签定义、在线fallback和项目讲解都更简单。

## 6. 三路数据与打标方案

### 6.1 数据来源

- MIRACL中文：普通语义检索、关键词补充样本。
- HotpotQA Bridge：高质量中文化后补充多跳Graph样本。
- 原有121条业务问题：只作为独立业务验收，不能被公共训练数据污染。
- 真实Shadow问题：后续生产分类器的主要增量来源。

### 6.2 每题运行四个实验

```text
V
V+G
V+B
V+G+B
```

全部使用同一版本的：

- 文档集与canonical chunks。
- Embedding模型。
- Graph release。
- BM25 analyzer/index build。
- Top-K、RRF、Reranker和Token Budget。

### 6.3 标签生成

计划合格门槛：

```text
Gold Document Recall = 1.0
Gold Evidence Recall达到冻结阈值
最终剪裁后仍保留直接证据
路线没有超时或失败
```

在合格计划中按以下顺序选择：

1. 路线数更少。
2. 检索延迟更低。
3. Evidence Recall更高。
4. 固定名称顺序作为稳定tie-break。

自动输出只能标记 `AUTO_SUGGESTED`；人工核对后才能改为 `REVIEWED` 并进入训练。

## 7. 后续开发阶段

### 阶段A：BM25生产闭环

- [ ] 在目标数据库执行并验证迁移026。
- [ ] 对历史文档补建FULLTEXT索引。
- [ ] 校验每个知识库BM25 readiness。
- [ ] 用错误码、类名、中英文混合、长中文问题验证分词。
- [ ] 验证索引重建、文档删除和analyzer版本升级。

出口：所有评测知识库可以稳定显式执行BM25。

### 阶段B：三路评测闭环

- [ ] 扩展评测脚本采集V、V+G、V+B、V+G+B。
- [ ] 保存Query Embedding和配置快照。
- [ ] 保存每路耗时、状态、rank和Gold命中。
- [ ] 使用三路标签脚本生成审核队列。

出口：三头 `AUTO_SUGGESTED` 数据。

### 阶段C：数据审核与分类器训练

- [ ] 完成公共问题内容清洗。
- [ ] 人工复核三头路由标签。
- [ ] 按文档组和问题族防泄漏切分。
- [ ] 训练三头Logistic Regression。
- [ ] 在validation校准独立阈值。
- [ ] 冻结test报告和模型SHA-256。

出口：`retrieval-router-v1.json`，三个头全部enabled。

### 阶段D：直接路由上线

- [ ] 部署 `retrieval-router-v1.json` 并校验模型身份、维度和SHA-256。
- [ ] 将意图路由开关打开，分类结果直接进入实际检索路线。
- [ ] 验证V、V+G、V+B、V+G+B四种路线均能正确执行。
- [ ] 验证BM25未ready、分类器异常和Graph初始化失败时的基线回退。

上线前完成固定测试集验收；生产不再维护Shadow/灰度执行模式。

## 8. 关键验收指标

分类指标：

- `vectorSufficient`重点保证precision，目标不低于0.98。
- `graphRequired`重点保证recall，目标不低于0.98。
- `bm25Required`重点保证recall，目标不低于0.98。
- 报告每头PR-AUC、F1和coverage。
- 报告三标签完全匹配率与Hamming Loss。

RAG指标：

- Document Recall@K。
- Evidence Recall@K。
- nDCG@K和MRR。
- 最终剪裁后证据覆盖率。
- 回答正确性、忠实度和引用命中率。

性能指标：

- 检索P50/P95/P99。
- Graph LLM实体抽取次数。
- BM25查询次数。
- 每路超时与失败率。
- 相对固定V+G+B基线的延迟下降。

## 9. 项目讲解顺序

建议按以下顺序讲述：

1. 单一向量检索对精确标识符和多跳关系存在短板。
2. 引入Graph解决实体关系和多跳问题。
3. 引入BM25解决错误码、类名、配置项和原词精确匹配。
4. 三路候选通过canonical chunk去重、RRF和Rerank统一融合。
5. 固定三路质量高但延迟和资源开销较大。
6. 使用冻结Embedding上的三个轻量分类头做安全减枝。
7. 低置信度和异常情况始终回退V+G+B或当前ready基线。

这条主线不需要引入Wiki、独立知识来源、页面同步和标题索引，边界清晰且每一步都能用实验数据证明。

## 10. 明确非目标

当前版本不做：

- Wiki数据源和页面模型。
- Title Lookup/别名页面定位。
- 第四分类头。
- 用LLM在线决定任意检索参数。
- 修改现有Retrieval Embedding权重。
- 在没有检索消融标签的情况下直接上线分类器。

未来如重新启动Wiki，必须新建独立计划和模型Schema版本，不修改当前三路v1数据定义。
