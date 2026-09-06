# 混合检索融合与重排优化复盘

## 1. 文档目的

本文记录图谱查询链路优化完成后，E3（Vector + Graph）与E4（Vector + Graph + Rerank）暴露出的融合问题、问题定位过程、方案演进、最终实现、工程验证和最后一轮实验验收方法。

本文重点回答四个问题：

1. 为什么Graph Only已经大幅改善，Hybrid的收益却没有同步放大？
2. 为什么这个现象不能直接解释为“图谱化没有用”？
3. 原始RRF融合和固定Top5重排分别存在哪些问题？
4. 如何只修复有证据支持的失败模式，而不为了少数题破坏原本正常的题目？

本轮冻结以下条件：

- 不重新分块；
- 不重新向量化；
- 不重新图谱化；
- 不修改知识库文档和121题测试集；
- 不修改回答模型、温度和RAGAS评测口径；
- 不覆盖既有E1、E3、E4正式结果；
- 最后一轮实验写入独立的 `E3-FINAL` 和 `E4-FINAL` 目录。

因此，本轮变量集中在：

```text
Vector候选
  + Graph候选
  → 去重与RRF融合
  → 可选Rerank
  → 文档多样性
  → Token预算与TopN打包
  → 最终回答上下文
```

## 2. 问题如何暴露

### 2.1 最初观察

图谱查询链路优化后，Graph Only相较优化前已经出现明确改善：

- 标准来源命中和完整覆盖显著提升；
- Context Precision、Context Recall和Answer Accuracy显著提升；
- 图谱检索P95显著下降；
- 65题诊断集中的35道C类“候选池命中但最终选择丢失”被消除。

但是在横向查看E1、E3和E4时，又出现了一个新的反常现象：

- E3加入图谱分支后，部分题目的最终答案并没有优于Vector Only；
- 某些题的文档召回正常，但上下文精度、答案相关性或准确率反而下降；
- E4加入重排器后，整体方向优于E3，但仍存在部分题目退化；
- Hybrid显著增加检索耗时，却没有在每道题上稳定转化为回答收益。

如果只看总体分数，很容易得到一个过早结论：

> 图谱加入融合后没有稳定超过向量检索，因此图谱化没有用。

这个结论忽略了检索链路中的多个独立阶段。Graph分支可以成功补充文档和关系证据，但这些证据仍可能在RRF、重排、文档配额、Token预算或Top5阶段被错误组织。

### 2.2 三个竞争假设

围绕Hybrid退化，最初存在三个假设：

- H1：图谱召回本身无效，加入Hybrid只会增加噪声；
- H2：图谱召回有效，但RRF融合错误地改变了Vector高置信证据的优先级；
- H3：融合候选基本有效，但固定Top5和重排尾部噪声降低了最终上下文质量。

要判断H1～H3，必须把“候选有没有找到”和“最终有没有选对”分开。

## 3. 为什么不能只比较总体均值

总体均值会同时受到以下因素影响：

- 问题类型构成；
- Vector和Graph的候选交集；
- 回答模型生成波动；
- RAGAS裁判波动；
- 某一道灾难性失败对均值的拉动；
- 多数无变化题对少数结构性问题的稀释。

因此本轮使用四层证据：

| 层级 | 检查内容 | 目的 |
|---|---|---|
| 总体层 | E1/E3/E4各项均值、配对差值 | 判断问题是否具有总体影响 |
| 检索层 | 文档Recall、Hit Rate、MRR、候选来源 | 判断Graph是否补充了正确来源 |
| 候选层 | `vectorRank`、`graphRank`、`rrfScore`、`rerankScore`、`selected` | 判断证据在哪个排序阶段被改变 |
| 个案层 | 问题、参考答案、最终上下文和回答 | 判断排序变化是否真正影响答案 |

本轮所有融合结论均优先使用候选与选择Trace，不根据单次回答分数反推检索根因。

## 4. 优化前融合链路

### 4.1 原始链路

```text
Vector TopN ─┐
             ├─ 按文本精确去重
Graph TopN ──┘
                  ↓
             计算标准RRF
                  ↓
        按RRF从高到低排序
                  ↓
          文档多样性重排
                  ↓
    每文档软上限2 + Token预算
                  ↓
              最终Top5
```

RRF计算规则为：

```text
vector贡献 = 1 / (60 + vectorRank)
graph贡献  = 1 / (60 + graphRank)
rrfScore   = vector贡献 + graph贡献
```

该规则的优点是：

- 不需要比较Vector Score和Graph Score的绝对刻度；
- 同时被两条路线命中的候选会得到交叉验证加分；
- 单一路由失败时仍可使用另一条路线。

但它也隐含了一个风险：

> “两路都出现”会获得接近双倍RRF分数，但这不等于该候选一定比高置信Vector Top1更接近问题答案。

### 4.2 固定Top5带来的二次放大

在RRF排序之后，系统还执行：

- 相似内容延后；
- 每个文档优先最多2条；
- Token预算；
- Graph关系描述Token比例；
- 最终最多5条。

因此一个Vector Top1即使仍位于RRF前5，也可能因为其所属文档已有两个更高RRF候选而被延后。延后后，其他文档或Graph候选会填满5个名额，Vector Top1不再进入回答上下文。

问题不是单独发生在RRF，也不是单独发生在每文档上限，而是二者组合：

```text
双路候选获得RRF加成
  → 同一文档两个双路候选排在前面
  → 高置信Vector Top1触发每文档软上限
  → 其他Graph候选填满Top5
  → Vector Top1即使相关性更高也无法回填
```

## 5. 关键失败案例：eval_doc19_01

`eval_doc19_01` 是定位融合问题的关键样本。

### 5.1 Vector Only

Vector链路中：

- Vector Rank1相似度为0.8971；
- 该候选包含回答所需事实；
- 候选进入最终上下文；
- 回答简洁且正确；
- 五项RAGAS指标接近满分。

### 5.2 原始Hybrid RRF

Hybrid链路中：

- Vector Rank1仍然存在于候选池；
- 它没有被Graph召回，因此只有单路RRF贡献；
- 同一文档的其他候选同时被Vector和Graph命中，获得双路RRF加成；
- 两个同文档双路候选先占用文档配额；
- Vector Rank1被延后；
- 一个Graph Only候选填入最终Top5；
- 最终回答由27个字符扩展到106个字符，并偏离参考答案。

对应指标由接近满分下降为：

| 指标 | 原始E3 |
|---|---:|
| Context Precision | 0 |
| Context Recall | 0 |
| Faithfulness | 0.875 |
| Answer Relevancy | 0 |
| Answer Accuracy | 0 |
| Overall | 0.175 |

该案例证明：

1. Vector证据没有召回失败；
2. Graph分支也不是完全空结果；
3. 错误发生在融合排序和最终打包；
4. “双路命中”不能无条件覆盖高置信Vector Top1；
5. 只看文档级命中无法发现问题，因为前后仍可能命中同一标准文档。

### 5.3 Rerank为何能够恢复

旧E4中，重排器把Vector Rank1重新排回最终上下文，`eval_doc19_01` 的Overall恢复到0.9988。

这进一步支持H2：

> 候选池中存在正确证据，错误主要发生在无重排的RRF最终选择阶段。

如果图谱或向量候选池根本没有正确证据，Rerank无法把它恢复。

## 6. 从错误方案到最终方案

### 6.1 第一版：无条件保护Vector Top1

最直接的修复设想是：

```text
Hybrid无Rerank
  → 固定把Vector Rank1移动到最前
  → 其余候选保持RRF顺序
```

这能修复 `eval_doc19_01`，但离线回放发现它过于宽泛。

在原E3的121题中，真正没有进入最终上下文的Vector Top1只有4题：

| 题目 | Vector Top1分数 | Top1-Top2分差 | 原E3相较Vector表现 |
|---|---:|---:|---|
| `eval_doc18_01` | 0.8985 | 0.0212 | 原E3改善 |
| `eval_doc19_01` | 0.8971 | 0.0682 | 原E3灾难性退化 |
| `eval_doc28_02` | 0.8134 | 0.0044 | 原E3改善 |
| `eval_doc29_01` | 0.8534 | 0.0298 | 原E3改善 |

无条件保护会同时改动4题，其中3题原本没有出现回答退化。它还会改变部分“Vector Top1已经安全入选”的题目顺序。

因此第一版被否决。问题不能简化为“Vector永远优先于Graph”。

### 6.2 第二版：只按Vector分数阈值保护

第二版尝试只保护高分Vector Top1，但绝对相似度仍无法区分：

- `eval_doc18_01`：0.8985，原E3改善；
- `eval_doc19_01`：0.8971，原E3失败。

两题绝对分数非常接近，使用 `vectorScore >= 0.88` 仍会同时触发，不能隔离真正的问题。

因此绝对分数阈值也被否决。

### 6.3 第三版：Top1领先幅度

进一步比较Vector Top1与Top2的分差：

```text
eval_doc18_01：0.0212
eval_doc19_01：0.0682
eval_doc28_02：0.0044
eval_doc29_01：0.0298
```

`eval_doc19_01` 的Top1领先幅度明显更大。最终选择0.05作为“高置信领先”阈值：

```text
vectorTop1Score - vectorTop2Score >= 0.05
```

相对分差比绝对分数更能表达“Rank1是否形成明显断层”，也较少依赖Embedding模型的整体分数偏移。

### 6.4 第四版：增加“确有淘汰风险”

仅使用0.05分差仍会把一些已经安全进入Top5的Vector Top1移动到最前，改变不必要的上下文顺序。

最终增加风险判断：

- Vector Top1位于最终选择数量之外；或
- Vector Top1之前已有达到每文档软上限的同文档候选。

只有确实可能被TopN或文档配额淘汰时才触发保护。

## 7. 最终融合保护规则

最终规则必须同时满足：

1. 当前同时存在可用Vector和Graph路线；
2. 没有成功的Rerank结果；
3. `hybridProtectedVectorCount > 0`；
4. 至少有两个带有效分数的Vector候选；
5. Vector Top1与Top2分差不低于0.05；
6. Vector Top1确实面临TopN或每文档上限淘汰风险。

默认参数：

```text
hybridProtectedVectorCount = 1
hybridVectorProtectionMinMargin = 0.05
```

触发后：

- 只保护Vector Rank1；
- 其余候选保持原RRF顺序；
- 不删除任何Graph候选；
- Graph Only和Vector Only不受影响；
- Rerank成功的E4不受影响；
- Rerank失败回退RRF时可获得同样保护。

在旧E3的121题候选上离线回放，最终规则只触发：

```text
eval_doc19_01
```

其余120题不改变融合顺序。

这比“提高Vector权重”或“固定Vector优先”更保守，也更符合现有数据。

## 8. 重排阶段审计

### 8.1 重排服务本身是否不稳定

旧E4的121题记录显示：

- Rerank配置121/121启用；
- Rerank执行121/121成功；
- Circuit Open为0；
- 没有进入RRF失败回退；
- 最终文档Recall高于E3。

因此E4剩余问题不是重排服务超时或不可用。

### 8.2 真正问题：固定塞满5条

原链路在Rerank成功后仍固定选择最多5条：

```text
完整候选池
  → Cross Encoder重排
  → 不考虑尾部分数断层
  → 固定选择5条
```

部分题的第4、第5候选Rerank Score已经很低。即使它们不会挤掉标准来源文档，也可能：

- 增加无关实体和事件；
- 诱导回答模型补充不必要背景；
- 降低Context Precision；
- 增加回答长度；
- 降低Faithfulness或Answer Relevancy。

### 8.3 为什么没有使用绝对0.5阈值

在旧E4候选上离线回放：

- 绝对阈值0.5可以显著减少上下文；
- 但 `eval_cross_revised_09` 的第二份标准证据分数低于0.5；
- 该题精确证据覆盖会由1.0下降到0.5。

Cross Encoder分数的绝对刻度还可能随模型、部署框架和归一化方式变化。因此没有使用固定 `rerankScore >= 0.5`。

### 8.4 相对Top1分数截断

最终采用：

```text
candidate.rerankScore >= top1.rerankScore × 0.30
```

同时至少保留1条候选：

```text
rerankRelativeScoreThreshold = 0.30
rerankMinCandidates = 1
```

离线回放仅用于选择安全规则，不作为最终实验结果：

| 诊断指标 | 固定Top5 | Top1×0.30 |
|---|---:|---:|
| 平均选择候选数 | 5.000 | 3.802 |
| 候选级标准来源精度代理 | 0.3930 | 0.5066 |
| 标准文档Recall | 0.9739 | 0.9752 |
| 精确证据覆盖 | 0.9711 | 0.9711 |
| Cross Document精确证据覆盖 | 0.9722 | 0.9722 |

相对阈值在减少约1.2条平均上下文的同时，没有降低旧数据上的文档召回和精确证据覆盖。

## 9. 优化后完整链路

```text
Vector候选 ─┐
            ├─ 文本去重与来源合并
Graph候选 ──┘
                 ↓
             计算RRF
                 ↓
       是否有成功Rerank结果？
          ├─ 否
          │   ├─ 检查Vector Top1领先幅度
          │   ├─ 检查TopN/文档配额淘汰风险
          │   └─ 必要时仅保护Vector Top1
          └─ 是
              ├─ 保持Cross Encoder排序
              ├─ 至少保留1条
              └─ 删除低于Top1×0.30的弱尾部
                 ↓
            相似内容多样化
                 ↓
     每文档软上限 + Graph Token比例
                 ↓
          总Token预算与最终TopN
                 ↓
             回答模型
```

## 10. 代码实施

### 10.1 融合与选择

文件：

- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/DeduplicatingContentRetriever.java`
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/RetrievedCandidate.java`

实施内容：

1. 为候选暴露内部 `vectorRank` 和 `vectorScore`，只用于融合决策。
2. 增加 `shouldProtectVectorEvidence`，限制保护逻辑只在Hybrid且无成功Rerank时运行。
3. 增加 `prioritizeVectorEvidence`：
   - 校验Top1与Top2分差；
   - 校验TopN和每文档配额风险；
   - 使用对象身份集合避免重复加入候选；
   - 保持未保护候选的原RRF顺序。
4. 增加 `applyRerankScoreCutoff`：
   - 使用相对Top1分数；
   - 支持最少保留数量；
   - 阈值为0时可关闭；
   - 候选Trace仍保留完整候选池，只有最终选择阶段过滤弱尾部。

### 10.2 配置

文件：

`server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/config/ZhiMeshProperties.java`

新增默认配置：

```text
hybridProtectedVectorCount = 1
hybridVectorProtectionMinMargin = 0.05
rerankRelativeScoreThreshold = 0.30
rerankMinCandidates = 1
```

这些参数均可通过Spring配置覆盖，设置保护数量或相对阈值为0可以分别关闭对应逻辑。

### 10.3 实验可复现性

文件：

`server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/KnowledgeBaseService.java`

评测响应的 `configSnapshot` 新增：

- `hybridProtectedVectorCount`
- `hybridVectorProtectionMinMargin`
- `rerankRelativeScoreThreshold`
- `rerankMinCandidates`

这样最终结果文件可以证明后端实际应用了哪组融合与重排参数，而不是只依赖代码版本推断。

## 11. 自动化验证

### 11.1 新增边界测试

`DeduplicatingContentRetrieverTest`覆盖：

- 高置信Vector Top1面临文档配额淘汰时得到保护；
- Vector领先幅度不足时保持RRF顺序；
- Vector Top1已经安全入选时不改变顺序；
- Graph Only顺序不变；
- 保护规则可以关闭；
- 相对Rerank阈值删除弱尾部；
- 多样化后出现的高分候选不会被误删；
- 至少保留配置数量；
- Rerank截断可以关闭；
- 路由失败、超时和Token预算既有行为不回归。

### 11.2 工程验证结果

| 验证 | 结果 |
|---|---:|
| `zhimesh-common` 主代码编译 | 594个Java文件成功 |
| 融合与重排测试 | 15项通过，0失败 |
| 评测映射测试 | 1项通过，0失败 |
| 相关测试合计 | 16项通过，0失败 |
| Maven构建 | BUILD SUCCESS |

测试日志中的路由ERROR/WARN来自既有故障注入用例，断言均通过，不是构建失败。

## 12. 最终实验隔离

旧E3/E4目录已经包含121条成功记录。采集脚本会跳过成功ID，如果直接复用旧配置，新代码不会真正产生新回答。

因此新增：

- `experiments/after-optimization/configs/e3-hybrid-final.json`
- `experiments/after-optimization/configs/e4-hybrid-rerank-final.json`
- `experiments/after-optimization/run_final_experiments.ps1`

新结果目录：

```text
experiments/after-optimization/results/e3-hybrid-final
experiments/after-optimization/results/e4-hybrid-rerank-final
```

最终脚本统一提供：

- `status`
- `smoke`
- `score-smoke`
- `collect`
- `score`
- `report`

避免从 `experiments/after-optimization` 错误拼接项目根目录、Python虚拟环境和配置路径。

## 13. 四题烟雾验证

烟雾题不是随机样本，而是专门覆盖已知风险：

| 题目 | 验证目标 |
|---|---|
| `eval_doc19_01` | Vector Top1被RRF和文档配额淘汰 |
| `eval_doc13_03` | 旧E4重排后明显退化的关系题 |
| `eval_cross_revised_09` | 相对阈值不能删除第二份跨文档证据 |
| `eval_unanswerable_01` | 弱尾部上下文是否被压缩 |

当前烟雾结果只作为放行信号，不作为121题最终量化结论：

- E3 `eval_doc19_01` 的Vector Rank1已进入最终上下文，Overall由原E3的0.175恢复到0.9987；
- E3 `eval_doc13_03` Overall由0.9258提高到0.9492；
- E4 `eval_doc13_03` Overall由0.6678提高到0.8904；
- E4 `eval_unanswerable_01` 最终上下文由5条减少到2条，Answer Accuracy和Faithfulness均保持1；
- `eval_cross_revised_09` 的标准来源仍被覆盖，Answer Accuracy与Context Recall保持1；
- E4 `eval_doc19_01` 回答采集成功且Answer Accuracy、Answer Relevancy为1，但首次RAGAS有三个指标为PARTIAL，需要技术性补评分。

同一题、相同上下文的LLM评测仍可能波动。例如E3 `eval_cross_revised_09` 新旧上下文完全一致，但Context Precision单次评分不同。因此四题烟雾只判断：

- 结构性失败是否修复；
- 标准证据是否保留；
- 新参数是否实际生效；
- 是否存在接口、鉴权、Rerank或指标缺失。

不能使用四题均值替代最终121题结论。

## 14. 最终结果待回填

本节等待 `E3-FINAL` 和 `E4-FINAL` 完成121题回答、RAGAS及确定性指标后填写。参数在最终实验期间冻结，不再根据单题分数继续调整。

### 14.1 完成状态

| 实验 | 回答成功 | RAGAS SUCCESS | PARTIAL | ERROR |
|---|---:|---:|---:|---:|
| E3-FINAL | 待填写 | 待填写 | 待填写 | 待填写 |
| E4-FINAL | 待填写 | 待填写 | 待填写 | 待填写 |

### 14.2 RAGAS总体结果

| 实验 | Context Precision | Context Recall | Faithfulness | Answer Relevancy | Answer Accuracy | Overall |
|---|---:|---:|---:|---:|---:|---:|
| E1 Vector基线 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 |
| 旧E3 Hybrid | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 |
| E3-FINAL | 待实验完成 | 待实验完成 | 待实验完成 | 待实验完成 | 待实验完成 | 待实验完成 |
| 旧E4 Hybrid+Rerank | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 | 待统一引用 |
| E4-FINAL | 待实验完成 | 待实验完成 | 待实验完成 | 待实验完成 | 待实验完成 | 待实验完成 |

### 14.3 配对结果

最终至少输出：

- 旧E3 → E3-FINAL同题均值差；
- 旧E4 → E4-FINAL同题均值差；
- E1 → E3-FINAL与E1 → E4-FINAL；
- 每项改善/持平/退化题数；
- Bootstrap 95%置信区间；
- 问题类型分组；
- 标准来源Recall、Hit Rate、MRR；
- 最终上下文数量与Token变化；
- Rerank成功率、失败率和熔断次数；
- Top1保护触发题目；
- 相对阈值实际截断题目及平均减少候选数。

### 14.4 验收规则

工程完整性必须满足：

1. E3-FINAL与E4-FINAL均回答121/121；
2. 五项指标完整，`PARTIAL=0`、`ERROR=0`；
3. E4 Rerank 121/121成功；
4. `configSnapshot`记录四个新增参数；
5. `eval_doc19_01` 的Vector Rank1进入E3-FINAL上下文；
6. E4最终选择数量能够随分数变化，不再机械固定为5。

质量验收重点：

1. E3-FINAL不再出现 `eval_doc19_01` 类型的高置信Vector证据淘汰；
2. E3-FINAL标准来源Recall不得出现实质下降；
3. E4-FINAL Context Precision应优于或不低于旧E4的合理波动范围；
4. E4-FINAL Context Recall和跨文档证据覆盖不得因截断明显下降；
5. Faithfulness与Answer Relevancy需结合置信区间判断，不能按单次均值小幅变化继续调参。

## 15. 复测命令

重启包含最终代码的后端后，在 `ragas-evaluation` 根目录执行：

```powershell
cd D:\My-Study\iedaProjects\zhimesh\ragas-evaluation

.\experiments\after-optimization\run_final_experiments.ps1 -Stage smoke
.\experiments\after-optimization\run_final_experiments.ps1 -Stage score-smoke
.\experiments\after-optimization\run_final_experiments.ps1 -Stage collect
.\experiments\after-optimization\run_final_experiments.ps1 -Stage score
.\experiments\after-optimization\run_final_experiments.ps1 -Stage report
.\experiments\after-optimization\run_final_experiments.ps1 -Stage status
```

采集前必须使用有效的管理端 `ZHIMESH_ADMIN_TOKEN`。该接口位于 `/admin/rag-evaluation/...`，不能使用普通用户Token，也不能添加 `Bearer ` 前缀。

## 16. 本轮复盘结论

本轮最重要的经验不是“Vector比Graph更可靠”，而是：

> 多路召回的价值取决于证据如何进入最终上下文。召回更多候选不等于回答质量自动提高。

原始Hybrid有两个不同层次的问题：

1. 无Rerank时，标准RRF的双路加成与每文档软上限组合，可能淘汰明显领先的Vector Top1；
2. 有Rerank时，固定塞满Top5会把明显偏弱的尾部候选继续送入回答模型。

最终方案没有重新设计整个检索框架，也没有给Vector或Graph设置永久优先级，而是分别增加两个局部约束：

- 只在高置信且确有淘汰风险时保护一个Vector候选；
- 只在Rerank成功后按Top1相对分数删除弱尾部。

这两个规则均保留现有RRF、Graph候选、Cross Encoder和Token预算，并通过离线回放把影响范围收敛到可解释范围。最终质量收益仍以冻结参数后的121题 `E3-FINAL`、`E4-FINAL` 为准。
