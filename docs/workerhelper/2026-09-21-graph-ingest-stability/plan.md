# Execution Plan: graph-ingest-stability（图谱化稳定性改造 B 档）

## Summary

- 依据 `design.md`（已确认）实施四项稳定性改造：B1 抽取质量自愈循环、B3 tokenUsage 判空、B4 清理失败 Redis 标记重试、B5 启动即恢复遗留 DOING
- 全部改动集中在 `zhimesh-common` 模块，5 个源文件 + 对应单测；不改 fail-fast 语义、不动 HTTP 级重试器

## Tasks

- [x] T1: 新增质量自愈轮数配置
  - Files: `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/config/ZhiMeshProperties.java`
  - Change: `Indexing` 内新增 `graphExtractionQualityMaxAttempts`（int，默认 3，语义：含首轮抽取的总尝试轮数，≥1），带中英注释说明与 `graphRequestMaxAttempts`（HTTP 级）的层次关系
  - Verify: 编译通过；`ZhiMeshProperties` 现有测试（如有）不破
  - Depends on: none

- [x] T2: GraphRag 自愈循环 + 记费判空（B1+B3）
  - Files: `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/GraphRag.java`；新增/调整测试 `server/zhimesh-common/src/test/java/com/pppp/zhimesh/common/rag/GraphRagSelfHealingTest.java`（命名可按现有测试风格调整）
  - Change:
    - `extractSegment` 重构为质量自愈循环：首轮用 `buildJsonExtractionPrompt`，后续轮用 `buildJsonRepairPrompt`（issues 累积去重拼接）；每轮请求仍经 `GraphExtractionRequestExecutor.execute`（stage 分别为 `extract`/`repair`）；解析异常（`parsePayload` 抛出的 IllegalArgumentException）与 `qualityIssues` 非空均进入下一轮；轮数达 `graphExtractionQualityMaxAttempts` 上限仍有 issues → 抛 IllegalArgumentException（消息含累积 issues，保持 fail-closed）
    - 抽私有方法 `recordCost(User, ChatResponse, boolean)`：`tokenUsage()` 为 null 时记 0 并 `log.warn` 一次；两处调用点（extract/repair 后）统一走它
    - 空白段、配额检查逻辑不动
  - Verify: 单测覆盖四场景——①首轮坏 JSON 次轮合格→成功且记费两轮；②首轮质量 issues 次轮修复→成功；③N 轮仍败→抛出；④tokenUsage 为 null→不 NPE 记 0。`mvn -pl zhimesh-common test -Dtest='GraphRag*,GraphExtraction*'`
  - Depends on: T1（读配置）

- [x] T3: 清理失败 Redis 标记（B4 写入侧）
  - Files: `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/cosntant/RedisKeyConstant.java`、`server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/KnowledgeBaseItemService.java`；测试邻域 `src/test/java/.../service/`
  - Change:
    - `RedisKeyConstant` 新增 `KB_GRAPH_CLEANUP_RETRY_SIGNAL = "kb:graph:cleanup:retry:signal"`
    - `indexingGraph` 的 cleanup 失败 catch（`:666` 附近）：保留原 error 日志，追加 `opsForSet().add(key, kbUuid + ":" + kbItemUuid)`，Redis 操作自身包 try/catch 降级 warn（Redis 不可用不阻塞主流程）
    - `indexingGraph` 成功置 DONE 后：移除该 item 对应标记（存在才移除，静默）
  - Verify: 单测——①cleanup 抛异常→set 里出现 `kb:item` 成员；②正常完成→标记被移除；③Redis 抛异常→不影响 FAIL 状态回写
  - Depends on: none

- [x] T4: 恢复 job 扩展（B4 消费侧 + B5 启动恢复）
  - Files: `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/IndexingRecoveryJob.java`、`server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/KnowledgeBaseItemService.java`；测试 `IndexingRecoveryJob` 邻域
  - Change:
    - 消费方法：读取 set 全量成员 → 逐个解析 `kbUuid:kbItemUuid`（格式非法则直接移除并 warn）→ 经 service 层 seam 调 `cleanupDocument` → 成功移除成员、失败保留待下轮；整体 try/catch 保证不拖垮 scheduler，与既有三个恢复调用并列
    - `cleanupDocument` 的 service 层入口（`KnowledgeBaseItemService` 新增包内/public 方法委托 `knowledgeBaseGraphRag().cleanupDocument(...)`，供 job 调用）
    - B5：新增 `@EventListener(ApplicationReadyEvent)` 方法，以事件时刻为界调用 `failTimedOutGraphIndexing` 的变体（阈值=事件时刻，而非 60 分钟）——在 `KnowledgeBaseItemService.failTimedOutGraphIndexing(LocalDateTime now)` 基础上抽一个接受 `staleBefore` 的重载，原 60 分钟路径复用它
  - Verify: 单测——①set 含合法标记且清理成功→成员移除；②清理抛异常→成员保留；③DOING 且 changeTime 早于启动时刻→转 FAIL；④晚于启动时刻→不动；⑤embedding/fulltext 定时路径行为不变
  - Depends on: T3（key 常量与标记格式）

- [x] T5: 集成验证与路由图收尾
  - Files: 无新改动（验证性任务）
  - Change: 编译整个 server；跑 `zhimesh-common` 相关测试全量；确认无回归
  - Verify: `cd server && mvn -q compile` + `mvn -pl zhimesh-common test`（全绿）；git diff 复查无越界改动
  - Depends on: T2, T3, T4

## Verification

- Commands:
  - `cd server && mvn -q compile`
  - `cd server && mvn -pl zhimesh-common test`
  - 定向：`mvn -pl zhimesh-common test -Dtest='GraphRag*,IndexingRecovery*,KnowledgeBaseItem*'`
- Manual checks:
  - 起后端，对一个小文档触发图谱化（正常路径确认无行为变化、日志可见轮次信息）
  - 人为把 ingest 模型换成会输出劣质 JSON 的配置验证自愈（可选，成本高则信任单测）

## Task Relationships

- Strongly related: T1→T2（配置消费）；T3→T4（标记格式与清理入口）
- Weakly related: T2 与 T4 均触碰 GraphRag 周边但方法不重叠（extractSegment vs cleanupDocument），先后皆可
- Independent: T3 与 T1/T2 无文件交集
- Conflict risks: T3 与 T4 都改 `KnowledgeBaseItemService`——T4 的 seam 方法与 T3 的标记写入位置不同段，顺序执行（T3 先）避免合并冲突

## WorkerSync

- Need worker-sync: yes（任务完成后连同本次会话的盘点结果一起落路由图；图谱化管线条目补 B 档稳定性机制注记）
- Expected updates: feature-routes 中图谱 ingest 链路条目（GraphRag/IndexingRecoveryJob/RedisKeyConstant）

## Risks

- B1 最坏路径单段耗时拉长（3 轮×120s）：与 60 分钟 DOING 阈值的交互在极端大文档下可能触发启动恢复误判，但既有语义允许活任务 DONE 覆盖恢复的 FAIL，与现状同级
- 单测需遵守项目 MP 测试模式（lambdaQuery 预置 entityClass；BaseException 场景需 MessageSourceStub），执行时先读现有测试样板再写
