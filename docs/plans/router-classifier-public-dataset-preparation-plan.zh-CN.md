# 路由分类头公共数据集清洗与训练准备执行计划

> 文档状态：执行中  
> 数据范围：MIRACL 中文 1,705 条、HotpotQA Bridge 500 条  
> 目标分类器：冻结现有 Query Embedding，训练统一路由分类器  
> 输出能力：`vectorSufficient`、`graphRequired`、`bm25Required`  
> 非目标：Wiki、Title Lookup和第四分类头  
> 核心原则：公共数据集只提供问题和 Gold Evidence；最终路由标签必须来自本项目真实检索消融实验

## 1. 最终完成标准

只有同时满足以下条件，数据才可以进入分类头训练：

1. 问题、答案、Gold Evidence 已通过内容质量门禁。
2. 每条样本已在独立评测知识库中完成实际检索实验。
3. 标签来自统一版本的 Vector、Graph、BM25 检索结果，不由数据集名称推断。
4. 每条训练记录的 `labelStatus=REVIEWED`。
5. 三个标签字段完整且经过审核。
6. 所有 Query Embedding 使用与生产分类器相同的模型身份和维度。
7. 同一问题族、同一Gold文档组和近重复问题不跨 train/validation/test。
8. 冻结 test 集后不再根据test结果修改规则、阈值或标签。

未达到上述条件的文件只能称为：

```text
source candidate        原始候选
retrieval-labeling source  可进入检索实验的数据
label review candidate  待审核路由标签
```

不得称为 production training dataset。

## 2. 当前数据结论

### 2.1 MIRACL 中文

现有数据：

```text
问题                         1,705
官方正负 qrels               已保留
相关段落                     15,243
聚合导入文档                  9,850
```

MIRACL 中文原生问题和中文语料不需要翻译。主要风险是重复问题、同一文章泄漏、qrels缺失、评测语料与生产业务分布不同。

它适合提供：

- 普通中文语义检索样本。
- Vector单路可满足的正样本。
- BM25与Vector效果对比样本。

它不能自动被标记为 `vectorSufficient=true`；必须以实际检索结果为准。

### 2.2 HotpotQA Bridge

现有500条全部为 `hard + bridge`，每条涉及两篇Gold文档：

```text
问题                            500
答案                            500
Gold supporting facts         1,214
唯一Gold标题                     993
候选上下文段落约               4,959
```

英文原始问题、答案、supporting fact索引和上下文结构可以保留。当前OPUS-MT中文译文只能作为人工校订草稿，不能直接用于训练或打标。

已完成的人工审计显示，当前机器译文存在系统性问题：问题截断、专名直译、比较/否定丢失、Bridge条件消失、答案污染。自动结构检查无法发现这类错误。

## 3. HotpotQA英文数据的有效处理方案

### 3.1 不采用的方案

以下方案禁止作为正式数据来源：

- 在用户电脑下载大型LLM并直接批量翻译。
- 继续使用相同等级的小型离线翻译模型重译。
- 只审核自动标记的185条，默认其他315条正确。
- 只翻译问题，不校订Gold Evidence和Gold标题。
- 根据 `public_dataset=HotpotQA` 直接写 `graphRequired=true`。

### 3.2 采用“Gold优先、机器干扰文档隔离”的方案

人工/高质量模型验收范围固定为：

| 对象 | 必审数量 | 原因 |
| --- | ---: | --- |
| 中文问题 | 500 | 分类器直接输入，必须保留两跳关系 |
| 中文答案 | 500 | 验证Evidence是否支持结论 |
| Gold Evidence | 1,214 | 生成检索命中指标的真值 |
| Gold标题 | 993 | 图谱实体、标题查找和跨文档关联依赖它 |
| 非Gold上下文 | 抽检5%～10% | 只作为检索干扰项，不得作为正证据 |

非Gold上下文允许继续使用当前机器译文，但必须标记：

```text
contentReviewStatus=MACHINE_TRANSLATED_DISTRACTOR
mayBeUsedAsGoldEvidence=false
```

Gold标题和Gold Evidence完成校订后，回填到原段落对应位置。文档正文同时保留英文标题别名：

```text
中文标题（English Title）
```

这样可以在不人工翻译20,453个句子的前提下，保证检索真值、桥接关系和标题实体是可靠的。机器干扰文档只用于制造与真实检索相近的噪声，不参与Gold判断。

### 3.3 翻译与审核方式

不要求本地运行LLM。按优先级选择：

1. Codex按50条一批校订CSV，用户每批抽查5～10条。
2. 使用已有云端LLM/API按结构化提示翻译，再由人审核；密钥只通过环境变量传入，不写入数据集或仓库。
3. 双语人工直接校订。

无论采用哪种翻译来源，最终都必须填写人工审核状态。机器或LLM生成不能直接写成 `ACCEPT`。

审核规则：

- 人名、组织、作品首次出现采用“通用中文名（英文名）”；无可靠通用译名时保留英文。
- 同一实体在问题、标题、Evidence中必须使用同一译名。
- 数字、单位、日期、否定、比较级不得改变。
- 必须保留问题中连接两篇文档的Bridge实体或限定条件。
- 中文问题必须仍然需要两篇Gold文档；若退化成单跳，必须修复或拒绝。
- 中文答案必须能从中文Gold Evidence直接得到。
- 英文原始样本自身矛盾、问题无唯一答案或Gold链不完整时标记 `REJECT`，不得靠翻译猜测修补。

### 3.4 HotpotQA逐条验收门禁

一条样本只有满足以下条件才进入检索实验：

```text
question decision = ACCEPT
answer reviewed text 非空
所有关联Gold title decision = ACCEPT
所有关联Gold evidence decision = ACCEPT
两篇Gold文档均存在
Gold Evidence能支持答案
Bridge条件仍存在
```

否则：

- `PENDING`：留在审核队列，不进入实验。
- `REJECT`：写入排除清单并记录原因。

建议目标：500条中保留至少400条高质量样本。若低于400条，不降低门禁，而是从完整HotpotQA Bridge池重新补抽并走同一流程。

## 4. MIRACL处理方案

### 4.1 自动结构清洗

执行：

- Unicode NFKC和空白规范化。
- ID唯一性检查。
- 空问题检查。
- Gold文档和Evidence非空检查。
- positive qrels检查。
- 规范化问题去重检查。
- 原始train/dev来源保留，不覆盖官方字段。

对于完全相同的规范化问题，只保留一条，避免同一个Query Embedding获得冲突路由标签。审核队列会建议保留Gold判断最丰富的一条（依次比较positive passage、Evidence、Gold文档数量），其余建议拒绝；建议仍需人工确认。

### 4.2 内容抽检

MIRACL为原生中文，不要求逐条翻译审核。采用分层抽检：

- 官方train随机抽取10%。
- 官方dev全部保留为候选独立验证来源，并至少抽检20%。
- 自动异常行100%审核。
- 对短问题、数字问题、专名问题、多个正段落问题分别抽样。

抽检发现严重错误率超过2%时，将对应问题类型扩大到100%审核。

### 4.3 防泄漏

不能直接沿用公共数据的train/dev作为分类器train/test。路由分类器切分必须在生成标签以后重新进行，并以以下信息构造group：

```text
Gold文档集合
规范化问题近重复簇
同一原始query family
```

任一group只能进入一个split。

## 5. 数据处理工具和产物

### 5.1 生成审核队列

脚本：

```text
ragas-evaluation/scripts/routing/prepare_public_router_sources.py
```

标准命令：

```powershell
python ragas-evaluation/scripts/routing/prepare_public_router_sources.py `
  --miracl-queries "C:\Users\p'p'p'p'\Desktop\意图识别数据集\miracl-zh\prepared\router-queries.jsonl" `
  --hotpot-source "C:\Users\p'p'p'p'\Desktop\意图识别数据集\hotpotqa-bridge\official\bridge-500-source.jsonl" `
  --hotpot-translated-queries "C:\Users\p'p'p'p'\Desktop\意图识别数据集\hotpotqa-bridge\prepared\router-queries.zh-CN.jsonl" `
  --hotpot-translated-documents "C:\Users\p'p'p'p'\Desktop\意图识别数据集\hotpotqa-bridge\prepared\import-documents.zh-CN.jsonl" `
  --output-dir outputs/router-dataset-preparation/v1
```

关键产物：

```text
readiness-report.json
miracl-zh/source-candidates.jsonl
miracl-zh/review-queue.csv
hotpotqa-bridge/question-answer-review.csv
hotpotqa-bridge/gold-evidence-review.csv
hotpotqa-bridge/gold-title-review.csv
hotpotqa-bridge/source-candidates.jsonl
```

CSV使用UTF-8 BOM，可直接用Excel打开。审核者只填写：

```text
reviewed*Zh
decision=ACCEPT/REJECT/PENDING
reviewer
reviewedAt
notes
```

不得修改原始英文、ID、documentName和sentenceIndex。

### 5.2 汇总已审核数据

脚本：

```text
ragas-evaluation/scripts/routing/finalize_public_router_sources.py
```

正式汇总时不允许 `PENDING`。审核过程中的预览可以加 `--allow-partial`。

由于非Gold干扰段落仍是机器翻译，HotpotQA只能导入独立评测知识库；必须显式加：

```text
--allow-machine-distractors
```

该开关表示接受它们作为实验噪声，不表示认可其内容质量。

汇总产物仍然是 `retrieval-labeling-source`，其 `trainingEligible=false`。只有完成实际检索打标后才可能进入训练。

## 6. 独立知识库和索引准备

为避免污染生产知识库，建立两个只用于路由训练的数据空间：

```text
router-eval-miracl-zh-v1
router-eval-hotpot-bridge-zh-v1
```

要求：

- 固定Chunk参数、Embedding模型、Graph抽取配置和Reranker版本。
- 每个数据集导入后保存文档总数、Chunk总数和索引完成时间。
- Gold documentName必须可追溯到canonical chunk UUID。
- BM25、Graph、Vector分别记录readiness。
- 索引版本变化后，旧检索结果全部失效，不能混合打标。

HotpotQA的非Gold机器干扰文档不得进入生产问答知识库。

## 7. 三路检索标签生成

### 7.1 不使用数据集名称贴标签

错误示例：

```text
MIRACL -> vectorSufficient=true
HotpotQA -> graphRequired=true
```

数据集名称只用于采样统计，不参与标签计算。

### 7.2 检索实验

对每条问题分别缓存：

```text
Vector Top-K
Graph Top-K
BM25 Top-K
各路线耗时和状态
Query Embedding
```

当前生产计划以Vector为基础路线，评估以下4种计划：

```text
V
V+G
V+B
V+G+B
```

其中 `V/G/B` 分别代表Vector/Graph/BM25。每个组合使用与线上相同的去重、RRF、Rerank和剪裁逻辑。不能只拼接Top-K后按文档是否出现来代替线上融合。

### 7.3 质量门槛

单个计划被视为“可接受”，至少满足：

```text
所有Gold文档召回（Document Recall@K = 1.0）
Gold Evidence Recall@K >= 0.8
最终剪裁后至少保留一条直接支持答案的证据
检索链无超时或失败
```

HotpotQA Bridge优先要求两篇Gold文档全部召回。MIRACL允许按官方qrels的相关等级计算nDCG@10，同时保留positive passage recall。

阈值先在validation集确定，冻结后应用于test集；不能针对每条问题人工改变门槛。

### 7.4 三个标签的确定

从所有可接受计划中选择成本最低的计划：

1. 路线数量更少。
2. P95延迟预测更低。
3. Gold Evidence Recall更高。
4. 使用固定字典序作为最终稳定tie-break。

写入：

```text
选择V           -> vectorSufficient=true,  G/B=false
选择V+G         -> vectorSufficient=false, graphRequired=true
选择V+B         -> vectorSufficient=false, bm25Required=true
选择V+G+B       -> vectorSufficient=false, graphRequired=true, bm25Required=true
```

若没有任何组合达到门槛，标记 `UNRESOLVED` 并排除训练。若组合差异太小或路线失败，标记 `REVIEW_REQUIRED`，不能自动贴标签。

BM25尚未完成消融时：

```text
bm25Required=null
```

对应模型头保持 `enabled=false`。后续索引上线必须重新跑完整消融并训练新模型版本，不能把旧的false当成负样本。

## 8. 标签人工复核

自动建议只写：

```text
labelStatus=AUTO_SUGGESTED
```

人工复核至少检查：

- Gold Evidence匹配是否被翻译或分块差异误伤。
- Vector单路是否真的包含完整答案，而不是只命中同名文档。
- Graph带来的增益是否来自正确桥接关系。
- BM25增益是否来自错误码、编号、专名等合理精确命中。
- 路线超时不能被当作能力负样本。

审核完成才能写：

```text
labelStatus=REVIEWED
```

## 9. 训练前数据门禁

建议数量门槛：

- 当前Vector/Graph二头基线：至少600条REVIEWED，且每个头正负样本各不少于150条。
- 三头正式训练：建议1,500～3,000条REVIEWED；每个启用头正样本不少于200条，最好不少于500条。
- 任一头正样本不足时保持disabled，不进行过采样伪造上线条件。

质量门槛：

```text
重复ID                            0
空问题                            0
PENDING内容审核                    0
非REVIEWED标签进入正式训练          0
Embedding模型/维度混用              0
group跨split泄漏                    0
Gold文档引用丢失                    0
Hotpot Gold Evidence未人工审核       0
```

## 10. 切分、训练与最终验收

推荐切分：

```text
train       70%
validation  15%
test        15%
```

业务121条问题保留为外部业务测试集，不参与公共训练集切分。

分类器验收不能只看accuracy：

- `vectorSufficient`重点看precision，建议不低于0.98。
- `graphRequired/bm25Required`重点看recall，建议不低于0.98。
- 报告每头Precision、Recall、F1、PR-AUC。
- 报告四标签完全匹配率和Hamming Loss。
- 最终必须比较固定Hybrid/全路基线的Evidence Recall、回答质量和P50/P95延迟。

达不到门槛时优先修复标签和样本覆盖，不通过调低门槛强行上线。

## 11. 执行顺序和可交付物

### 阶段A：源数据清洗

- [x] 保留原始英文和中文文件，不覆盖。
- [x] 建立MIRACL结构清洗规则。
- [x] 建立Hotpot问题/答案、Evidence、标题三份审核队列。
- [x] 实现审核汇总和Gold证据回填工具。
- [ ] 完成Hotpot 500条人工校订。
- [ ] 完成MIRACL异常队列和分层抽检。

出口：`retrieval-labeling-source.jsonl`，不是训练集。

### 阶段B：索引与消融

- [ ] 建立两个独立评测知识库。
- [ ] 完成Vector/Graph索引并记录版本。
- [ ] 完成BM25索引和readiness验证。
- [ ] 缓存单路结果并按线上逻辑重放组合。

出口：三路四组合观测文件。

### 阶段C：标签审核

- [ ] 自动生成三头建议标签。
- [ ] 处理UNRESOLVED和路线失败。
- [ ] 人工把通过项标记为REVIEWED。

出口：`reviewed-routing-dataset.jsonl`。

### 阶段D：防泄漏切分与训练

- [ ] 按文档组和问题族切分。
- [ ] 生成一次Query Embedding并记录模型身份。
- [ ] 运行训练前validator。
- [ ] 训练、校准阈值、冻结test报告。

出口：分类器artifact、SHA-256和测试报告。

## 12. 回滚与可追溯要求

- 原始文件永不覆盖。
- 每一阶段输出新版本目录。
- readiness report记录输入绝对路径和SHA-256。
- 翻译、内容审核、路由标签审核是不同状态，不得复用一个`REVIEWED`字段混淆。
- 任何索引、Embedding、Chunk、Reranker版本变化都必须生成新一版路由观测和训练集。
- 生产分类器只加载经过checksum校验且测试报告获批的artifact；失败时回退Hybrid。
