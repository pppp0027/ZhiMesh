# Execution Plan: A 类正确性缺陷修复（5 项）

## Summary

落实 `report.md` 的 A1–A5：索引 DOING 恢复不对称、run_workflow 漏账、阻塞路径错误语义、编辑后旧向量残留、删库物理数据残留。三组子代理并行（文件所有权互斥），主线程复审 + 新鲜跑全量测试。无 schema 变更（所需列均已存在）。

## 文件所有权（互斥，越界禁止）

- **G1**：KnowledgeBaseItemService.java、rag/GraphIndexingRecoveryJob.java（改名）、ZhiMeshProperties.java、application.yml、test: KnowledgeBaseItemRecoveryTest / GraphIndexingRecoveryJobTest / KnowledgeBaseItemService 相关
- **G2**：AbstractLLMService.java、ErrorEnum.java、messages 资源（若需）、WorkflowUtil.java、test: 工具循环 / WorkflowUtil 相关
- **G3**：KnowledgeBaseService.java、IKnowledgeEmbeddingService + pgvector/neo4j 实现、KnowledgeBaseEmbeddingMapper(.java/.xml)、Bm25IndexService、CanonicalChunkIndexService、test: 删库清理相关

## Tasks

- [x] T1 (G1) A1 恢复 Job 扩展
  - KBISvc 新增 `failTimedOutEmbeddingIndexing()` / `failTimedOutFulltextIndexing()`，完全镜像 graphical 版（状态列、StatusChangeTime 列、超时属性）；ZhiMeshProperties.Indexing 加 `embeddingDoingTimeoutMinutes=30` / `fulltextDoingTimeoutMinutes=30`，application.yml zhimesh.indexing 块补注释样例
  - GraphIndexingRecoveryJob → IndexingRecoveryJob（类名+文件+测试同步改），@Scheduled 方法依次调三个恢复，javadoc/日志措辞覆盖三类；保留"DB 故障不拖垮调度器"的 try/catch
  - Tests: KnowledgeBaseItemRecoveryTest 补 embedding/fulltext 超时用例（DOING+超时→FAIL；DOING+未超时不动；非 DOING 不动）
- [x] T2 (G1) A4 编辑同步删向量
  - saveOrUpdate contentChanged 分支：追加 `iKnowledgeEmbeddingService.deleteByItemUuid(previous.getUuid())`，并镜像 fulltext 的状态复位（EmbeddingStatusEnum.NONE + changeTime + startedAt/completedAt 置空；embeddingChunkSetUuid 已清零保持）
  - 图谱贡献不做编辑期清理（re-index 时 cleanupDocument 已覆盖），代码注释说明该取舍
  - Tests: 内容变化→deleteByItemUuid 被调且状态复位；内容未变→不调
- [x] T3 (G2) A3 阻塞路径优雅收尾
  - innerChatWithDepth 深度上限分支改为镜像流式 :320-359：追加 TOOL_LIMIT_REACHED_INSTRUCTION + withoutToolSpecifications 再给一轮，返回该轮结果；toolContext 存在时补 loop_limit_reached 轨迹（SSE uuid 为 null，sendToolCall 自行短路，先核实）
  - 收尾轮仍要工具 → 抛 BaseException，错误码新增 `B_TOOL_CALL_LIMIT_EXCEEDED`（查 ErrorEnum 现有编码规则取下一个 B00xx；i18n 挂接方式照抄现有条目，含 messages 资源与测试的 MessageSourceStub）
  - Tests: 循环到上限→得到收尾轮答案不抛错；收尾轮仍索要工具→B_TOOL_CALL_LIMIT_EXCEEDED；轨迹含 marker
- [x] T4 (G2) A2 工作流 LLM 入账（复审 I1 补修：AgentNode→LocalAgentService 路径同样入账，appendLlmCostToUserSafely + 2 测试）
  - WorkflowUtil 两个 saveLLMCallRecord 调用点（:96/:162）之后：解析模型 isFree（复用该方法内已有的模型解析路径）→ `SpringUtil.getBean(UserDayCostService.class).appendCostToUser(wfState.getUser(), totalTokens, isFree)`（模式照抄 GraphRag:279）
  - Tests: mock/静态桩验证 appendCostToUser 以正确 tokens+isFree 被调（照仓库现有静态工具测试模式）
- [x] T5 (G3) A5 删库物理清理（chunk_set 复用经实证不跨条目，kb_uuid 级删除安全）
  - softDelete 删行成功后 best-effort 清理（每项独立 try/catch+log，风格照 cleanupKnowledgeBaseGraph）：条目行、向量（库级）、BM25（库级）、canonical chunks；图谱清理已有保持
  - 新增库级删除：IKnowledgeEmbeddingService.deleteByKbUuid（pgvector 走 mapper XML 按 metadata->>'kb_uuid'；neo4j 走 removeAll(IsEqualTo(KB_UUID))）、Bm25IndexService.deleteByKbUuid（documents+postings 按 kb）、CanonicalChunkIndexService.deleteByKbUuid
  - **关键安全约束**：先读 CCISvc:135-140 确认 chunk_set 复用作用域——若 (content_hash, config_hash) 复用是跨库共享，删除必须按 chunk 的 kb_item_uuid 归属限定，chunk_set 仅在无剩余 chunk 引用时删（或保守留孤儿 set 并注释说明）；不得破坏他库在用快照
  - softDeleteForUserWorkspace 若为独立路径同样接入；文件物理存储（sha256 去重跨库共享）明确不清理，注释说明
  - Tests: 删库→四类清理都被调；单项清理抛异常不影响其余与返回值
- [x] T6 review 子代理复审（对照本 plan + report.md A1–A5 逐项核销）→ I1 由主线程直接补修并验证 → 主线程 `mvn -B test`（server 根，全模块）新鲜全绿（common 657/0/0/0 + chat 1/0/0/0，reactor 5 模块 SUCCESS）
  - 复审遗留（记录不修）：M1 startedAt/completedAt 置空经 updateById 是 DB no-op（镜像既有 fulltext 行为，恢复 Job 不依赖这两列）；M2 invokeLLM 入账嵌在 metrics 守卫内（现生产调用方恒设 LLMMetrics）；M3 流式路径病态收尾轮仍抛裸 RuntimeException（存量，未在范围内）；M4 删库与在跑索引任务的孤儿行窗口（javadoc 已述）；M5 target 下旧测试报告残留（构建产物）

## Verification

- 各 G 内：`mvn -pl zhimesh-common -am test` 相关模块绿
- 终验（主线程新鲜跑）：server 根 `mvn -B test` 全绿（覆盖 chat/admin 对 common 的编译依赖）
- 后端 :9999 属于用户且当前未运行——不启动、不碰 dev 库（迁移无需执行）

## Task Relationships

- Independent: G1 / G2 / G3（文件所有权互斥，可完全并行）
- Conflict risks: 无跨组共享文件；ZhiMeshProperties/application.yml 仅 G1；ErrorEnum 仅 G2

## WorkerSync

- Need worker-sync: uncertain（恢复 Job 改名、KBSvc 清理属核心实现变化；完成后评估 feature-routes 是否有对应条目需更新）

## Risks

- chunk_set 跨库共享误删（T5 已内置安全约束，review 重点核）
- ErrorEnum 新码漏挂 i18n（G2 必须照抄现有条目的全部挂接点）
- 静态 SpringUtil 在测试中的可桩性（G4 照仓库既有模式，拿不准就 MockedStatic）
