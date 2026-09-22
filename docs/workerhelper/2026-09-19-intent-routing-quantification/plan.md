# Execution Plan: 意图路由量化（后端意图模式 + N6 评测组 + 统计/日志脚本）

## Summary

量化「意图路由 + 知识库前置门」的决策质量（灰带比例、误路由分布），为后续阈值调优（0.78/0.05）与是否启用 LinearRoutingIntentRecognizer 提供数据。四部分：

1. 后端：评测端点 `evaluateAsk` 新增 `intentRouting` 模式，复用交互链路同构序列（前置门专用评估 + BM25 探测式就绪 + 五参 route），响应回显意图决策
2. Python 采集：collect_predictions.py 支持 intent 模式 + 新配置 n6-intent.json
3. 统计脚本：按题型基准算误路由/灰带/置信度分布
4. 日志解析脚本：离线解析服务器 app.log 的前置门三态与意图路由行

## Context（已核实事实）

- 评测端点 `evaluateAsk`（`server/zhimesh-common/.../service/KnowledgeBaseService.java` L1274 起）不跑意图链路：不带显式路由时静默回落 HYBRID(V+G)；响应无意图字段；结果不入库。意图决策只在交互链路（routeKnowledgeQuery L1028-1048）。
- 意图决策观测面：INFO 日志（每请求必打）+ 本次新增的评测回显。
- 数据集 `ragas_eval_dataset_final.jsonl` 121 行可作误路由基准（question_type × required routes）。
- configs/server/*.json 被 .gitignore（L94 `ragas-evaluation/**/configs/`）覆盖，tar 传输。

## 关键设计决策（已定）

- **NO_RAG ⇒ effectiveRoutes 为空是合法核心场景**（IntentRoutingPolicy 对 NO_RAG 返回空集）：Python 校验用"空 ⇒ intent==no_rag"一致性校验，禁止要求非空。strict KB + 空路由 → 固定答案 `NO_KNOWLEDGE_EVIDENCE_ANSWER`（L102 常量，0 token），镜像生产 blockingAsk。
- **不引入 ContextualQueryRewriter**（评测题自包含；改写会改变被测查询并加一次 LLM 调用）——与生产的已知受控差异，写进代码注释。
- **BM25 用探测式复用 `availableKnowledgeRoutes(Collection<String>)`（L1009-1026）**，不用 requireReady fail-closed——镜像生产。BM25 未就绪时决策空间收窄为 V+G。
- **老行为零回归**：全局 ObjectMapper NON_NULL（BeanConfig L76）→ 新 `intent` 字段 null 自动省略；configSnapshot 的 `intentRouting` 键仅 true 时条件写入；RagEvaluationAskReq 保留旧 9 参构造器委托新 10 参规范构造器。
- **意图模式请求体绝不能发 `retrievalMode: ""`**（fromJson("") → HYBRID 非 null，触发互斥误报）——两个检索键整体省略。
- 前置门专用评估路径恒不 skip，UNRELATED 折叠为 UNCERTAIN——报告口径注明"评测端灰带含 UNRELATED 并集语义"。
- N6 use_reranker=false（隔离路由决策本身，与 N1-N3 同口径）；默认带答案生成，干跑用现有 `--retrieval-only`。

## 响应 intent 回显契约（顶层 `intent` 对象，与 rerank/graphTrace 平级）

```json
"intent": {
  "routingEnabled": true,
  "scopePreflight": {"status": "related|uncertain|not_applicable", "score": 0.83, "skip": false, "durationMs": 12, "reason": "..."},
  "availableRoutes": ["vector", "graph"],
  "intent": "relationship",
  "recognizer": "route-prototype:v3",
  "confidence": 0.81,
  "margin": 0.09,
  "proposedRoutes": ["vector", "graph"],
  "effectiveRoutes": ["vector", "graph"],
  "fallback": false,
  "reason": "<plan.reason()，与 IntentRoutingService L71-73 日志字段一一对应>",
  "recognitionReason": "<decision.reason()>"
}
```
路由列表按 ordinal 排序 + 小写（与 configSnapshot.retrievalRoutes 同款）；configSnapshot 追加条件键 `intentRouting: true`。

## Tasks

### 后端链（串行 T1→T2→T3；T4 随 T1 后可并行）

- [x] **T1** DTO 两件
  - `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/evaluation/RagEvaluationAskReq.java`：record 加第 10 组件 `Boolean intentRouting`（retrievalRoutes 之后，无校验注解）；旧 9 参构造器保留并委托新规范构造器（intentRouting=null）；新增 `effectiveIntentRouting()`（Boolean.TRUE.equals）。`effectiveRetrievalRoutes()/effectiveRetrievalMode()` 不动。
  - `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/evaluation/RagEvaluationAskResp.java`：加字段 `private Map<String, Object> intent;`（注释：仅 intentRouting 请求出现）
- [x] **T2** Mapper 回显 + 单测
  - `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/RagEvaluationResultMapper.java`：新增静态 `intent(RetrievalPlan plan, KnowledgeScopeDecision scopeDecision, Set<RetrievalRoute> availableRoutes, boolean routingEnabled)`，镜像 L69-77 rerank() 风格，按契约构建 LinkedHashMap；null 容忍（plan null → intent/confidence 等为 null、effectiveRoutes=[]）
  - 新建 `server/zhimesh-common/src/test/java/com/pppp/zhimesh/common/rag/RagEvaluationResultMapperTest.java`：正常 plan / NO_RAG 空路由 / null plan / scopePreflight 嵌套 / ordinal 排序小写 / routingEnabled 透传
- [x] **T3** Service 意图分支（`server/zhimesh-common/.../service/KnowledgeBaseService.java`，零新增 import）
  - L1307 后追加意图互斥校验（`intentRouting && (mode!=null || routes 非空)` → IllegalArgumentException）
  - L1308 改分支：intentRouting → retrievalRoutes 占位后由路由块赋值；else 走原逻辑（原空校验保留在 else 内）
  - L1313-1317 BM25 requireReady 包 `if (!intentRouting && ...)`
  - queryEmbeddingRequired 加 `intentRouting ||` 前置
  - queryContext 之后插入路由决策块（routeKnowledgeQuery 同构序列，内联以捕获 plan）：KbInfoResp copyProperties → evaluateDedicatedKnowledgeBase → `availableRoutes = scopeDecision.skipKnowledgeBaseRouting() ? Set.of() : availableKnowledgeRoutes(kbScope)` → `availableRoutes` 非空时 `intentRoutingService.route(question, embedding, null, Set.of(DOCUMENT_KB), availableRoutes)` → retrievalRoutes = effectiveRoutes(plan)；intentEcho = mapper.intent(...)
  - L1333-1366 检索创建与执行包 `if (!retrievalRoutes.isEmpty())`，retriever 允许 null，retrievalMs 空分支置 0
  - 答案分支：`retrievalOnly → null`；`strict && 空路由 → NO_KNOWLEDGE_EVIDENCE_ANSWER`；否则既有生成（空上下文）
  - 空路由守卫：retriever==null 时 candidates/routes 给 List.of()、rerank 传 null、graphTrace 传 null
  - L1430+ evaluationConfig 调用加 intentRouting 实参；builder 加 `.intent(intentEcho)`；L1467+ evaluationConfig 签名加 `boolean intentRouting`，L1485 后条件 `if (intentRouting) config.put("intentRouting", true)`
- [x] **T4** `RetrievalModeTest` 扩展（既有文件，dto/evaluation 下）：effectiveIntentRouting 缺省/null=false；旧构造器兼容；规范构造器透传；intentRouting=true 时 effectiveRetrievalRoutes() 仍返回 V+G（DTO 纯数据不抛）

### Python 链

- [x] **T5** 采集器（`ragas-evaluation/scripts/core/collect_predictions.py`，独占）
  - `experiment_options()` 返回四元组 `(mode, routes, use_reranker, intent_routing)`：intent_routing 非 bool → ValueError；true 时要求另两键空（三选一互斥）
  - 请求体：intent 模式省略 retrievalMode/retrievalRoutes 两键 + `"intentRouting": true`
  - `validate_applied_experiment(..., intent_routing=False)` 分支：① snapshot.intentRouting is True；② intent dict 含 intent/proposedRoutes/effectiveRoutes/fallback/reason；③ reason=="intent routing is disabled" → 硬拒（服务器开关未开）；④ effectiveRoutes 空 ⇒ intent=="no_rag"；⑤ routes[] 集合 == set(intent.effectiveRoutes)，per-route timeout/error 健康检查照旧（期望集动态）；⑥ graphTrace 校验条件改 "graph" in effective
  - **修复既有陈旧测试** `ragas-evaluation/scripts/core/test_experiment_config.py`（现断言二元组已坏）→ 四元组 + intent 用例（合法/互斥拒绝/no_rag 放行/disabled 拒绝/echo 不一致拒绝）
- [x] **T6** `ragas-evaluation/configs/server/n6-intent.json`（新，tar 传输）：`intent_routing: true`、`retrieval_mode: null`、`retrieval_routes: null`、`use_reranker: false`、四部署键 null 走 env、输出 `experiments/current-system/n6-intent/{predictions.jsonl,scores.jsonl,.ragas-cache}`、temperature 0.0 / concurrency 1 / timeout 90；README.zh-CN.md 增 N6 行 + 意图模式小节（三键互斥、ZHIMESH_INTENT_ROUTING_ENABLED 前置、BM25 就绪影响路由空间、先发版后跑）
- [x] **T7** `ragas-evaluation/analysis/intent-routing/scripts/intent_routing_stats.py`（新，纯标准库，Python 3.10 兼容）
  - CLI：`--predictions` 必填、`--dataset`（补失败行字段）、`--min-top-score 0.78 --min-score-margin 0.05`、`--output-dir`（默认 `analysis/intent-routing/results/runs/n6-intent`）；ROOT=parents[3]
  - 输出：`INTENT_ROUTING_REPORT.zh-CN.md` + `intent_routing_summary.json` + `intent_routing_per_sample.csv`
  - 口径：**路由灰带率** = P(intent=="uncertain" ∨ fallback)，分报 uncertain 率/fallback 率（recognitionReason 归因）/交集；**前置门灰带率** = P(scopePreflight.status=="uncertain")（注明含 UNRELATED 折叠）+ 2×2 联表；confidence/margin 直方图（桶边界随阈值参数移动，各出全样本与"真实识别"两版——recognizer ∈ {disabled,fallback,explicit,unknown} 的行 confidence 恒 0 需剔除）；分题型路由矩阵 P(route∈effective|type) + P(空|type)；**误路由基准**：single_document_* → {vector}、cross_document/graph_multihop/temporal_comparison → {graph}、unanswerable 不参与（另报正确拒答率 P(NO_RAG|unanswerable)）；误杀率 P(空 ∧ is_answerable)；过度路由 mean(|effective|)；routes[] 与 effectiveRoutes 集合不等 → mismatch 行标记计数
- [x] **T8** `ragas-evaluation/analysis/intent-routing/scripts/parse_intent_logs.py`（新，纯标准库）
  - CLI：`--log-file` 可重复 + glob 展开（轮转 `%d.%i.log`；字面路径缺失/glob 无匹配 → 报错）、`--since/--until`（按行首时间戳，Asia/Shanghai）、`--output-dir`；输出 `LOG_PARSE_REPORT.zh-CN.md` + `log_intent_summary.json`
  - 解析三类：`Knowledge scope preflight: status=,score=,skip=,durationMs=,reason=`；`Intent routing: intent=,confidence=,margin=,routes=[..],fallback=,reason=`；辅助行（scope filtered originalKbCount/retainedKbCount/excludedKbCount、skipped by scope preflight）+ WARN 行（识别失败/fail-open/BM25 降级）
  - 输出：三态分布与 durationMs p50/p95、UNRELATED reason top-N、意图分布/fallback 率/直方图（桶同 T7）、routes 集合分布、多库过滤 excluded>0 占比、WARN 计数；报告头写覆盖率声明（Intent routing INFO 仅成功路径打印，explicit/disabled 不打）
- [x] **T9** `ragas-evaluation/analysis/intent-routing/README.zh-CN.md` 运行手册：发版 → curl 冒烟 → N6 采集 → 统计 → 服务器拉日志（/opt/zhimesh/logs/app.log，30 天窗口）→ 解析

## Verification（主线程终验）

```
# 后端（server/ 下）
mvn -q -pl zhimesh-common -am test -Dtest='RetrievalModeTest,RagEvaluationResultMapperTest'
mvn -q -pl zhimesh-common test
mvn -q -pl zhimesh-admin,zhimesh-bootstrap -am package -DskipTests
# Python（3.10 语法：ast feature_version=(3,10)）
python -m py_compile 全部新改脚本；test_experiment_config.py 全绿；--help 冒烟
# 干跑：T7 喂 3-5 行合成 predictions（no_rag 空/uncertain fallback/graph 缺失各一）；T8 喂 10 行合成 app.log
```
回归红线：无 intent 键的老请求响应字节不变；n1-n5 配置行为不变；`python scripts/core/test_experiment_config.py` 由坏变绿。
端到端（发版后用户执行）：curl 冒烟（intentRouting:true + retrievalOnly:true → 断言 intent.scopePreflight.status/effectiveRoutes/configSnapshot.intentRouting）→ `collect_predictions.py --config configs/server/n6-intent.json --limit 2` → 全量 → 统计脚本。**必须先发版后端再跑 N6**（旧后端走 HYBRID，validate 以 intentRouting 缺失硬拒——预期防错）。

## Task Relationships

- 串行：T1→T2→T3（同模块依赖链）；T5→T6
- 并行：后端链 ‖ Python 链（T5 起）‖ T8（完全独立）；T7 依赖 T5 的行结构（契约已定，可按契约并行，T5 落地后核对）；T9 最后
- 冲突风险：无共享文件（T3 独占 KnowledgeBaseService.java；T5 独占 collect_predictions.py）；不改 sse_benchmark.py / run_ragas.py / summarize_results.py / common.py

## 执行策略

- 3 个实现子代理：A=后端 T1-T4（串行），B=Python T5+T6，C=T7+T8（+T9）
- 主线程：逐 diff 复审 + 亲跑验证命令；完成后派独立复审子代理
- 发版由用户执行（两条 push 流程），不由代理执行

## WorkerSync

- Need worker-sync: no（docs/workerhelper/feature-routes.md 未初始化——与 2026-09-17 评测适配任务判定一致；本次不初始化）

## Risks

- 意图模式空路由（NO_RAG）三分支处理是最大行为新增点——T3 单测 + 冒烟重点覆盖
- strict KB 交互：专用前置门不走 strict 旁路（bypassForStrictKnowledgeBase=false），与生产一致，报告注明
- BM25 未就绪/关闭 → 决策空间收窄 V+G：N6 须在全文索引全量构建后跑；统计按 intent.availableRoutes 与 configSnapshot.bm25Enabled 分段
- ZHIMESH_INTENT_ROUTING_ENABLED=false → disabled 计划且不打 INFO：采集端硬拒（防呆）
- classifierEnabled 若开 → recognizer 分布不同：回显 recognizer 字段并在统计按其分段
- Jackson NON_NULL 依赖需实现时复核（若失效仅多一个 null 键，无功能影响）

## Verification（主线程终验记录，2026-09-19）

- 3 实现代理（A=后端 T1-T4 / B=采集 T5+T6 / C=脚本 T7-T9）全部完成；主线程逐 diff 契约级复审通过
- 后端（主线程亲跑，exit 0）：`mvn -pl zhimesh-common test` 全绿（代理 A 报 665 tests）+ `mvn -pl zhimesh-admin,zhimesh-bootstrap -am package -DskipTests` 成功
- Python（主线程亲跑）：AST feature_version=(3,10) 通过（4 个改动/新脚本）；test_experiment_config.py 由坏（二元组陈旧断言）变绿 21 用例；collect --help OK；N6 干跑 exit 0 打印 intent 行；N1 回归干跑 exit 0 无 intent 污染；n6-intent.json json.tool OK
- T7 合成干跑（主线程自建 5 行夹具独立复核）：graph_multihop 缺失率 1.0 involved=[q4]、cross_document 缺失 0、灰带率 0.25（uncertain/fallback/交集各 0.25、归因 abstained 计 1）、echo_mismatch=0、CSV 5 行 ✓
- T8 合成干跑（主线程自建 10 行夹具）：preflight 三态（含 score=null 行）、intent 2 条、fallback 0.5、routes 集合分布、scope filtered、WARN 计数 ✓
- 主线程修复 2 个 T8 bug（代理自测未覆盖的路径）：
  1. `warn_totals` 转 dict 后 render_report 缺键 KeyError（预置三类键为 0 修复——夹具只含部分 WARN 类别时崩溃）
  2. 前置门行 `score=null` 被正则拒绝静默丢行（生产 lexical/正则捷径路径 maxVectorScore 可为 null；score/confidence/margin 组接受 null，消费端 try/except 已安全）
- 夹具已清理；未 commit；发版留给用户（先发版后跑 N6，见 README）

## 独立复审记录（2026-09-19）

- 复审子代理结论：**可交付**。无 Critical；Important 1 项（I1 评测流量混入日志统计口径）；Minor 9 项（M1-M9）
- 主线程裁定与处置：
  - **已修**：I1（T8 报告头 + README §4.2 增加"避开 N6 采集时段/扣除 121 题"口径声明）、M1（--log-file 相对路径统一按 ROOT=ragas-evaluation/ 解析 glob，与 T7 一致）、M2（新增 DEDICATED_SKIPPED_RE 解析 `Dedicated knowledge-base retrieval skipped by scope preflight, kbUuid:..., reason:...` 行并归入 skipped_by_preflight）、M3（README 冒烟断言注：开关未开时 configSnapshot.intentRouting 仍为 true，以 data.intent.reason 检测）、M5（confidence/margin=null 行跳过且不计入 parse_failures）、M9（"前置门三态分布"→"前置门状态分布"）
  - **记录不修**：M4（analysis/intent-routing/README.zh-CN.md 被 .gitignore L92 `ragas-evaluation/**/*.md` 覆盖，提交时需 `git add -f` 或移入 docs/）、M6（intent 模式容忍 retrieval_mode:"" 但拒绝 retrieval_routes:[]，后端语义一致、无从触发）、M7（后端接受 retrievalRoutes:[]+intentRouting 组合，Python 端三选一互斥不可达）、M8（少量测试覆盖缺口：互斥校验 IllegalArgumentException 分支等，冒烟可覆盖）
- 修复后主线程复验（亲跑，2026-09-19）：
  - AST feature_version=(3,10) 通过
  - 合成日志 7 行（含 score=null 前置门行、专属库跳过行、WARN 级 BM25 行、fail-open 行）：matched=7、preflight 含 null-score 行、skipped_by_preflight count=1（M2 生效）、warn 三键齐全（含缺类别=0）、malformed=0（M5 生效）
  - 相对 glob 从仓库根发起：`output/tmp-intent-verify3/*.log` 按 ragas-evaluation/ 基准解析命中（M1 生效）；repo 根相对路径错误用法按预期报 `glob matched no files` 快速失败
  - 夹具已清理
