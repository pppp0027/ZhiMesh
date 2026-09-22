# Execution Plan: ragas-evaluation 适配服务器部署的当前后端

## Summary

让 `ragas-evaluation/` 脚手架对当前部署版本（三路检索 retrievalRoutes + 意图识别 + BM25）可直接运行：
采集脚本支持新请求字段与新实验组、配对/运营脚本参数化、服务器实验配置 + env 兜底、`.env.example` 补全。
后端 API 契约已由主线程逐项核实（见附录），代理按契约实现，不改后端代码。

## 后端契约（主线程已核实，代理照此实现）

- `POST /admin/rag-evaluation/ask/{kbUuid}`：请求体 `RagEvaluationAskReq`
  `{questionId, question, answerModelId, temperature, retrievalMode?, useReranker?, retrievalOnly?, includeQueryEmbedding?, retrievalRoutes?}`
  - `retrievalMode`（vector/graph/hybrid）与 `retrievalRoutes`（["vector","graph","bm25"] 子集）**互斥**，同时传后端抛错
  - legacy hybrid = {vector,graph}（不含 bm25）
- 响应（经 ResponseWrapper `{success,data}` 包装）：`routes[] = {route, status, durationMs, candidateCount, errorType, errorMessage}`，route 值为小写 `vector/graph/bm25`；`configSnapshot.retrievalRoutes` 回显小写数组（ordinal 排序）
- BM25 路由在索引未就绪时 fail-closed（HTTP 错误，脚本要能读出错误信息）
- SSE 压测链路契约不变（prompt/characterUuid/conversationUuid；conversation/add {characterUuid,title}；conversation/del/{uuid}；Authorization 裸 token；登录验证码仅在连续失败后强制）——**sse_benchmark.py 不改**

## 文件所有权（互斥）

- **G1**：`ragas-evaluation/scripts/core/collect_predictions.py`、`ragas-evaluation/configs/`（新增 server/ 子目录）、`ragas-evaluation/.env.example`
- **G2**：`ragas-evaluation/analysis/comparison/scripts/final_experiment_summary.py`、`ragas-evaluation/analysis/quantification/scripts/quantification_offline.py`

## Tasks

- [x] T1 (G1) 采集脚本三路支持 + 服务器配置 + env 兜底
  - config 新键 `retrieval_routes`（list[str]）；与 `retrieval_mode` 互斥（两者同时存在 → 启动即报错退出，提示二选一）
  - 请求体：有 `retrieval_routes` 时发 `retrievalRoutes` 且**不发** `retrievalMode`；否则维持现状
  - `validate_applied_experiment`：expected_routes 扩展为 `{"vector":{"vector"},"graph":{"graph"},"hybrid":{"vector","graph"},"three_route":{"vector","graph","bm25"}}`；routes 实验校验 `configSnapshot.retrievalRoutes` 集合相等（顺序无关）；legacy 实验维持 retrievalMode 校验；BM25 fail-closed 错误（HTTP 非 2xx + 后端错误信息含 "not ready"/"BM25"）要输出可读提示：先去服务器构建全文索引
  - env 兜底：config 中 `base_url`/`kb_uuid`/`answer_model_id`/`dataset` 为 null/缺失时回落 `ZHIMESH_API_BASE_URL`/`ZHIMESH_KB_UUID`/`ZHIMESH_ANSWER_MODEL_ID`/`RAGAS_DATASET`（dotenv 已加载的环境）
  - 新配置 `configs/server/`：`n1-vector.json`、`n2-vector-graph.json`、`n3-three-route.json`、`n4-three-route-rerank.json`、`n5-vector-rerank.json` + `README.zh-CN.md`（每个键说明 + BM25 前置条件 + 互斥规则）；结构仿 `configs/examples/experiment-config.example.json`；`retrieval_mode:null` + `retrieval_routes`（n1/n5 单路也用 routes 写法）；`base_url/kb_uuid/answer_model_id/dataset` 全 null 走 env；`predictions`/`ragas_scores` 指向 `experiments/current-system/<组名>/`；temperature 0.0、concurrency 1、timeout_seconds 90、use_reranker 按组定义（n4/n5 true）
  - `.env.example`：补 `ZHIMESH_KB_UUID`、`ZHIMESH_ANSWER_MODEL_ID` 两组键 + 注释（其余既有键只核对不重写）
  - 兼容性：不带 `retrieval_routes` 的旧 config 行为逐字节不变（旧 E1–E4 配置必须原样能跑）
  - Verify：`python -m py_compile` + `python .../collect_predictions.py --help`；`--limit 1` 干跑不可行（无服务器）则用构造的本地假 config 断言互斥校验与 env 兜底逻辑（可用 python -c 单测式调用内部函数）
- [x] T2 (G2) 配对汇总与运营指标参数化 + bm25 指标
  - `final_experiment_summary.py`：新增 `--experiment NAME=DIR`（可重复）与 `--pair LEFT:RIGHT`（可重复）；提供任一参数即覆盖内置默认实验集/配对组合；都不给时行为与现在完全一致；docstring 补服务器用法示例（N2→N3 BM25 增量、N3→N4 rerank 增量、N1→N4 总提升）
  - `quantification_offline.py`：同样支持 `--experiment NAME=DIR` 覆盖默认四组；`routes[]` 提取新增 `bm25_route_ms`（route=="bm25" 行的 durationMs，无该行留空）；paired_differences 组合跟随实验集参数（或 `--pair`）
  - `graph_quality.py` 仅只读核对现有 CLI 参数（--kb-uuid/--graph-name 等）并在 T2 报告中确认，不改文件
  - 兼容性：无参数调用行为不变（旧结果可复现）
  - Verify：`python -m py_compile` + `--help`；用 repo 内旧 scores/predictions 样例各跑一次无参数模式，输出文件与改动前一致或语义等价（旧数据在 experiments/*/ 下）

## Verification（主线程终验）

- 逐 diff 复审 G1/G2 全部改动（对照本 plan + 契约附录）
- 本机 `python -m py_compile` 全部改动文件 + `--help` 冒烟
- 契约交叉核对：collect 请求体字段 vs RagEvaluationAskReq；validate 断言的响应字段 vs configSnapshot/routes 实际结构
- 复审修正（主线程直接修）：configs/server/ 五个配置与 README 的分数文件名由 `ragas_scores.jsonl` 统一为 `scores.jsonl`（与旧约定及 final_experiment_summary 的 DIR 内文件约定对齐）
- 新鲜验证（主线程亲自跑，Python 3.12.7 venv + ast feature_version=(3,10)）：
  - 三脚本 3.10 语法兼容 OK
  - collect_predictions --help OK；N3 配置 + 四 env 兜底 `--limit 0` 干跑 OK（打印 retrieval_routes=vector+graph+bm25，exit 0）
  - final_experiment_summary --help（--experiment/--pair 出现）；旧数据冒充 N1/N3/N4 + --pair N3:N4 N1:N4 → paired_comparisons 键精确为 [N3_to_N4, N1_to_N4]，三产物生成
  - quantification_offline --help + 无参基线跑通（experiments=4, samples=484, exit 0）
- G2 代理已验证无参模式输出与改动前 byte-identical（final_experiment_summary 三产物；quantification 除新增 bm25 列外逐值相等）
- 复审子代理独立核查（见下方记录）

## 复审子代理结论记录

复审代理独立核查（含后端源码穿透比对），主线程逐条裁定：

- **Critical：无**
- **I1 BM25 中文提示永不触发**（Bm25UnavailableException 是 IllegalStateException，被 GlobalExceptionHandler:52 统一包装成 B0003"全局异常"，原始消息丢失）——主线程核实属实。修法采用脚本侧兜底：bm25 实验遇 B0003/全局异常时追加条件式提示（BM25_GLOBAL_ERROR_HINT）；后端透出具体消息（@ExceptionHandler）记为遗留改进，不在本次范围
- **I2 validate 不校验 routes[].status**——属实，但复审建议的 status=="completed" 有误：枚举实际为 {SUCCESS, EMPTY, TIMEOUT, ERROR}（RetrievalRouteStatus.java），健康路由可合法返回 empty（如 BM25 无命中）。主线程按正确语义修复：timeout/error 行 → 校验失败并带 errorType/errorMessage；success/empty 放行；legacy 与 routes 实验同规则
- **I3 .env.example 优先级注释与 collect 语义相反**——属实（sse_benchmark 是 env 覆盖 config，collect 是 config 优先 env 兜底）。注释已按脚本区分改写
- **Minor 已处理**：M1 README base_url 例外说明、M2 "返回400"措辞修正、M7 N4/N5 rerank 前置条件入 README、M9 五配置补每组独立 ragas_cache
- **Minor 记录不修**：M3 routes[].route 未小写归一（契约保证小写，无实际影响）；M4 prediction_success_rate_against_121 硬编码（既有代码）；M5 两脚本无 --pair 时兜底语义不同（相邻对 vs 全对，docstring 已写明，示例命令均显式 --pair）；M6 无参输出新增 bm25 空列（计划内 schema 扩展）；M8 ZHIMESH_ANSWER_MODEL_ID 非数字时首请求处 traceback（与旧代码同位置）
- 修正后新鲜验证（主线程）：3.10 语法 OK；healthy(success/empty) 行通过、bm25 timeout 行拒绝带 detail、legacy error 行拒绝、hint exact/B0003 兜底/负例三态正确；N3 dry-run 回归不变 exit 0

## Task Relationships

- Independent: T1 / T2（文件所有权互斥，可并行）
- Conflict risks: 无共享文件；两代理都不碰 sse_benchmark.py / run_ragas.py / summarize_results.py / common.py

## WorkerSync

- Need worker-sync: no（纯评测脚手架目录，feature-routes 未初始化，后端零改动）

## Risks

- 旧配置兼容回归（T1 以"旧路径逐字节不变"为硬约束）
- 代理误改后端文件（plan 明确禁止）
- Windows 本机 python 3.14 与服务器 3.10 的语法差异（禁用 3.11+ 语法特性）
