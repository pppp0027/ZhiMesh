# Design: graph-ingest-stability（图谱化稳定性改造 B 档）

## Goal

- 降低知识库图谱化（graphical indexing）因**模型输出问题**导致的整文档失败率（JSON 解析失败 / 质量门不通过只有一次修复机会的现状）
- 消除两处低概率但致命的运行时缺陷（tokenUsage 缺失 NPE、清理失败残留脏图数据无人兜底）
- 把重启后 DOING 状态最长 60 分钟的恢复盲区缩短到启动即恢复

## Scope

- In:
  - B1 抽取-解析-质检并入质量自愈循环（`GraphRag.extractSegment`），轮数可配置
  - B3 `ChatResponse.tokenUsage()` 判空，缺失时按 0 记费
  - B4 `cleanupDocument` 失败后记录 Redis 待清理标记，定时任务重试清理
  - B5 应用启动完成时立即恢复上一进程遗留的图谱 DOING 状态为 FAIL
- Out:
  - 不改 fail-fast 语义（B2 坏段隔离未选）
  - 不做 C 档写入性能改造（往返合并、快照复用、分锁）
  - 不改 A 档并发/池参数（运维配置，另行实施）
  - B5 仅覆盖图谱状态；embedding/fulltext 的启动恢复属同类机制，作为后续可选扩展

## Assumptions

- 部署形态为单实例（既有 OpenRouter job 注释明确 single-instance），启动时刻作为 DOING 归属边界是安全的
- 质量自愈轮数默认 3（首轮抽取 + 2 轮修复），最坏 token 成本 ≈ 现状 extract+repair 再加一轮，可接受
- Redis 与既有统计信号共用可用性等级：Redis 不可用时 B4 降级为仅日志（等同现状），不阻塞索引主流程

## Current Behavior

- `GraphRag.extractSegment`（`server/zhimesh-common/.../rag/GraphRag.java:250-301`）：LLM 调用经 `GraphExtractionRequestExecutor`（仅重试超时/限流/5xx）；返回后 `qualityIssues` 检查，不合格触发**恰好一次** repair 调用，`assertQuality` 仍不过即抛 `IllegalArgumentException` → `GraphExtractionTaskRunner` fail-fast → 整文档 FAIL。JSON 解析失败（`parsePayload`）同样一次性失败
- `GraphRag.java:279-280,293-294`：`aiMessageResponse.tokenUsage().totalTokenCount()` 未判空，供应商不回 usage 时 NPE
- `KnowledgeBaseItemService.indexingGraph`（`:659-670`）：失败路径中 `cleanupDocument` 再失败仅打日志，AGE 半写数据与溯源行残留，无重试
- `IndexingRecoveryJob`（`rag/IndexingRecoveryJob.java:23`）：`@Scheduled(fixedDelay=60s)` 调 `failTimedOutGraphIndexing()`，按 `graphDoingTimeoutMinutes=60` 阈值恢复；进程重启后遗留 DOING 最长等 60 分钟

## Proposed Behavior

- B1：`extractSegment` 内部改为质量自愈循环——每轮 = 一次 LLM 请求（首轮 extraction prompt，后续轮 repair prompt 携带累积 issues）+ 解析 + 质检；解析异常与质量 issues 均进入下一轮；达到轮数上限仍有 issues 则照旧抛出（fail-closed 不变）。HTTP 级瞬时重试仍由 `GraphExtractionRequestExecutor` 负责，两层重试互不叠加（每轮质量尝试内部最多消耗 maxAttempts 次 HTTP 重试）
- B3：新增私有方法统一记费，`tokenUsage()` 为 null 时记 0 并 warn 一次
- B4：`indexingGraph` 的 cleanup 失败 catch 中向 Redis set `kb:graph:cleanup:retry:signal` 添加 `kbUuid:kbItemUuid`；`IndexingRecoveryJob` 定时消费（逐个解析 → `cleanupDocument` → 成功移除成员，失败保留待下轮）；文档重建成功路径顺手移除对应标记
- B5：`IndexingRecoveryJob` 新增 `@EventListener(ApplicationReadyEvent)` 方法，捕获启动时刻，将图谱 DOING 且 `graphicalStatusChangeTime` 早于该时刻的行转 FAIL（复用 `failTimedOutGraphIndexing` 的更新语义，阈值换成启动时刻）

## Implementation Direction

- `ZhiMeshProperties.Indexing` 新增 `graphExtractionQualityMaxAttempts`（默认 3，≥1）
- B1 循环写在 `GraphRag.extractSegment` 内，不改动 `GraphExtractionRequestExecutor`；repair prompt 复用 `GraphExtractPrompt.buildJsonRepairPrompt`，issues 累积拼接
- B4 常量放 `RedisKeyConstant`，消费逻辑挂 `IndexingRecoveryJob`（与既有三个恢复调用并列、独立 try/catch）；清理调用经 `KnowledgeBaseItemService` 暴露的包内 seam（`knowledgeBaseGraphRag()` 已存在）
- B5 时间界用 `ApplicationReadyEvent` 回调参数化的 now，不依赖时钟回拨假设

## Affected Files

- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/GraphRag.java` (scan)：B1 自愈循环 + B3 记费判空
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/config/ZhiMeshProperties.java` (scan)：新增质量轮数配置
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/KnowledgeBaseItemService.java` (scan)：B4 失败标记写入 + 重建成功移除标记
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/rag/IndexingRecoveryJob.java` (scan)：B4 消费 + B5 启动恢复
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/cosntant/RedisKeyConstant.java` (scan)：B4 key 常量
- 测试：`GraphExtractionResponseTest` 邻域新增/调整（自愈循环、判空、恢复边界）

## Risks

- B1 最坏路径拉长单段耗时（3 轮 × 120s 超时），大文档 DOING 总时长上升；60 分钟 DOING 阈值在极端文档下可能被启动恢复误判——现有语义本就允许活任务完成时覆盖恢复的 FAIL，风险与现状同级
- B1 轮数配置过大时 token 成本线性上升（默认 3 已控制）
- B5 若未来改多实例部署，启动恢复需要换进程归属标识（当前单实例假设下无问题）

## Test Strategy

- 单测：伪 ChatModel 首轮返回坏 JSON/不合格输出、次轮合格 → 循环成功且记费两轮
- 单测：N 轮仍不合格 → 抛出、文档 FAIL（保持 fail-closed）
- 单测：tokenUsage 为 null 的 ChatResponse → 不抛 NPE、成本记 0
- 单测：cleanup 失败 → Redis set 出现标记；消费 job 成功后移除
- 单测：DOING 且 changeTime 早于启动时刻 → 启动恢复转 FAIL；晚于则不动

## WorkerHelper Impact

- Need worker-sync: yes
- Affected routes: 图谱化管线（GraphRag / IndexingRecoveryJob）条目在路由图建立后补充稳定性机制注记

## Acceptance Criteria

- 质量不合格输出在轮数内可自愈（原先一次 repair 失败即整文档 FAIL 的场景变为可成功）
- 轮数耗尽仍失败时行为与现状一致（FAIL + 清理 + 可重试）
- 供应商缺 usage 不再导致失败
- cleanup 失败不再永久残留（Redis 标记 + 定时重试收敛）
- 重启后遗留 DOING 在启动完成后即刻转 FAIL，无需等待 60 分钟
