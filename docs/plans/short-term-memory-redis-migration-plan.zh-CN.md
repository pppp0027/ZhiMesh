# 短期记忆迁移 Redis 稳定实施计划

> 文档状态：Implemented（代码完成，待按环境执行灰度切换）  
> 适用范围：server 后端短期对话记忆存储  
> 前置计划：docs/plans/agent-conversation-refactoring-plan.zh-CN.md  
> 本计划只替换短期记忆的存储后端，不改变 Conversation 的业务边界，也不迁移长期记忆。
> 实施结果：docs/reports/short-term-memory-redis-implementation-report.zh-CN.md

## 1. 目标与不可变约束

### 1.1 目标

完成后应达到以下结果：

1. 短期记忆默认保存在 Redis，支持多实例部署。
2. 现有 LangChain4j ChatMemory 行为保持不变：消息顺序、token 窗口、system message、thinking、tool call 和多模态消息均可恢复。
3. 现有 MapDB 历史数据可以无损回填，发布期间可以回滚到 MapDB。
4. Conversation 之间严格隔离；默认 Conversation 的旧 Character key 兼容迁移仍然有效。
5. 同一 Conversation 的并发请求不会静默覆盖消息；无法获得 turn 锁时必须明确失败或排队。
6. Redis 故障、坏数据、迁移失败、删除失败都有可观测结果和恢复路径。

### 1.2 不可变约束

以下约束来自现有 Conversation 设计，Redis 批次不得改变：

- 新短期记忆逻辑 key 仍为 conversation:{conversationUuid}。
- 旧 Character key 只能被默认 Conversation 继承。
- 非默认 Conversation 绝不回退、读取或写入旧 Character key。
- 长期语义/情景记忆仍按 characterId 存储和检索。
- 通用 ChatMemoryStore 不负责推导 characterUuid、conversationUuid 或 isDefault；迁移逻辑由显式服务完成。
- PostgreSQL、Redis、MapDB、异步长期记忆不属于同一个原子事务。
- Redis 切换完成前不得删除 MapDB 文件、依赖和旧 key。

## 2. 当前实现基线

当前短期记忆不是普通 JVM Map，而是本地文件型 MapDB：

- MapDBChatMemoryStore 使用 chat-memory.db 保存消息 JSON 和迁移标记。
- AbstractLLMService 在模型调用前写入 system/user 消息。
- CharacterChatService 在模型完成后追加 AiMessage。
- CompositeRag 的 AiServices 分支也直接使用同一个 MapDB store。
- ShortTermMemoryCleanupTask 和 ShortTermMemoryMigrationService 直接调用 MapDB 单例。

必须覆盖的生产调用点：

1. AbstractLLMService.createChatMessages
2. CompositeRag.ragChat 的 ChatMemoryProvider
3. CharacterChatService 的回答后写入
4. LocalAgentService、blockingAsk、workflow AgentNode 等间接调用路径
5. ShortTermMemoryCleanupTask
6. ShortTermMemoryMigrationService
7. 任何仍然手工拼接 memoryId 的知识库问答入口

Redis 基础设施已经存在：

- common 模块已有 Spring Data Redis starter。
- dev/prod 配置已有 Redis/Lettuce。
- Docker Compose 已有 Redis 服务。

因此本批次的核心工作是存储契约、迁移、并发一致性和发布控制，不是引入 Redis 驱动。

## 3. 目标架构

### 3.1 分层

将当前对 MapDB 单例的直接依赖改为以下三层：

~~~text
业务入口
  -> ShortTermMemoryTurnCoordinator
  -> ShortTermMemoryService / BackendRouter
  -> ChatMemoryStore 适配器
       -> MapDBChatMemoryStore
       -> RedisChatMemoryStore
~~~

职责必须分开：

- RedisChatMemoryStore：只负责一个 memory key 的 get/update/delete 和 JSON 序列化。
- MapDBChatMemoryStore：保留旧实现，并增加受控 snapshot/export 能力。
- ShortTermMemoryService：负责 canonical key、legacy key、迁移、双读、双写和修复。
- ShortTermMemoryTurnCoordinator：负责同一 memory key 的整轮并发控制。
- ShortTermMemoryKeyResolver：继续负责 Conversation/Character key 规则。

不要把 Character/Conversation 查询和迁移规则塞进 RedisChatMemoryStore。

### 3.2 Redis key 设计

Redis 外层必须有产品命名空间和版本号，不能直接把裸 UUID 当 Redis key：

~~~text
zhimesh:short-memory:v1:conversation:{conversationUuid}
zhimesh:short-memory:v1:character:{characterUuid}
zhimesh:short-memory:v1:memory:{encodedMemoryId}
zhimesh:short-memory:v1:migration:{conversationUuid}
zhimesh:short-memory:v1:lock:{canonicalMemoryId}
~~~

说明：

- conversation key 和 character key 物理隔离。
- 现有非 Conversation 入口如果仍使用自定义 memoryId，必须进入 memory 命名空间，禁止与 UUID key 混淆。
- encodedMemoryId 需要长度限制和稳定编码，不能直接允许冒号、空格等任意内容。
- migration key 保存迁移来源、schema version、消息 hash 和完成时间。
- lock key 只保存随机 owner token，不保存用户消息。

### 3.3 值格式

第一版使用 Redis String：

~~~text
value = LangChain4j ChatMessageSerializer.messagesToJson(messages)
~~~

原因：

- 与 ChatMemoryStore 的“完整列表读写”接口一致。
- 能保留 AiMessage.thinking、ToolExecutionResultMessage 和多模态消息。
- 当前 TokenWindowChatMemory 已经限制了消息数量，不需要一开始拆成 Redis List。

必须复刻 MapDB 当前的 normalizeMessages 语义：

- 首个 AiMessage 的处理规则保持一致。
- 只保留首个 SystemMessage。
- 不静默丢弃 tool、thinking 或多模态字段。
- key 不存在返回空列表。
- JSON 损坏必须记录错误并进入明确的 fallback/error 分支，不能当作空记忆覆盖原数据。

### 3.4 后端模式

使用一个有序的 backend-mode 配置，不用多个互相矛盾的布尔开关：

| 模式 | 读取 | 主写入 | 辅助写入 | 用途 |
|---|---|---|---|---|
| MAPDB_ONLY | MapDB | MapDB | 无 | 默认和回滚 |
| MAPDB_PRIMARY_DUAL_WRITE | MapDB | MapDB | Redis | 兼容发布、历史迁移 |
| REDIS_PRIMARY_MAPDB_FALLBACK | Redis | Redis | MapDB | 灰度观察 |
| REDIS_ONLY | Redis | Redis | 无 | 稳定后的最终状态 |

推荐配置项：

~~~text
zhimesh.conversation.short-memory-backend-mode
zhimesh.conversation.short-memory-redis-fallback-enabled
zhimesh.conversation.short-memory-turn-lock-enabled
zhimesh.conversation.short-memory-turn-lock-wait-ms
zhimesh.conversation.short-memory-turn-lock-lease-ms
zhimesh.conversation.short-memory-ttl
zhimesh.conversation.short-memory-max-value-bytes
zhimesh.conversation.short-memory-migration-batch-size
~~~

现有 short-memory-dual-write-default 继续只表示“默认 Conversation 是否双写旧 Character key”，不要与后端双写混用。

## 4. 并发一致性方案

### 4.1 为什么不能只给 Redis GET/SET 加锁

TokenWindowChatMemory 的实际模式是：

~~~text
getMessages()
本地修改消息列表
updateMessages()
~~~

Redis 的 GET 和 SET 各自原子，但整个读改写不是原子。只给 updateMessages 加锁仍然会发生 lost update。

### 4.2 第一版采用整轮 turn 锁

新增 ShortTermMemoryTurnCoordinator，按 canonical memory key 加锁：

~~~text
获得锁
  -> 读取短期记忆
  -> 添加当前 User/System 消息
  -> 执行 RAG、MCP、LLM 和工具循环
  -> 持久化最终 AI 消息
  -> 完成数据库消息写入和短期记忆更新
释放锁
~~~

实现要求：

- 锁值是随机 owner token，释放使用 compare-and-delete Lua 脚本。
- lease 必须自动续期，不能使用固定租约包住不确定时长的 LLM 调用。
- 续期失败时立即记录 lock-lost，并停止覆盖式写入。
- 等待超过 turn-lock-wait-ms 后返回明确的“会话处理中”错误；第一版不静默并发执行。
- 所有异常、SSE timeout、客户端断开和模型失败都在 finally/生命周期回调中释放锁。
- 同一请求内的嵌套工具调用必须复用同一个 owner，不得二次抢锁。
- 双写 Conversation key 和旧 Character key 时使用同一个 Conversation turn 锁。

### 4.3 入口覆盖要求

锁必须在“能看到第一次 memory read”之前取得，并在“最终 AiMessage 写入”之后释放：

- 流式聊天：锁的生命周期绑定到 SSE complete/error/timeout 回调，不能在 sseManager.call 返回时提前释放。
- blockingAsk：覆盖 Agent invoke 和回答后短期记忆写入。
- CompositeRag AiServices：覆盖完整的 ragChat 调用。
- workflow/外部 Agent：只有存在 memoryId 时进入 turn scope；无 memoryId 的无状态调用不加锁。

禁止只在 RedisChatMemoryStore.updateMessages 内部尝试解决整轮并发。

## 5. 分阶段实施计划

### D0：冻结基线和发布门禁

任务：

1. 冻结当前代码提交号、配置、MapDB 目录备份和固定聊天样例。
2. 记录每个样例的消息 JSON hash、消息数量、token 窗口、RAG/MCP/tool-call 情况。
3. 盘点所有 memoryId 来源，特别是非 Conversation 的手工拼接 key。
4. 增加短期记忆指标和结构化日志，但不改变 backend-mode。
5. 明确 Redis 生产容量、持久化、ACL/TLS、淘汰策略和备份策略。

退出条件：

- 基线测试通过。
- 所有 memory 写入入口有清单和负责人。
- MapDB 备份可在另一份目录启动读取。
- 发布、回滚和数据负责人已确认。

### D1：抽象存储契约，Redis 只读实现

任务：

1. 将 MapDB 访问从业务代码抽到 ShortTermMemoryService/BackendRouter。
2. 新增 Spring 管理的 RedisChatMemoryStore。
3. 使用 LangChain4j JSON serializer，不使用 Java 原生序列化。
4. 增加 key 编码、schema version、最大 value 大小和坏 JSON 处理。
5. 为 MapDB 增加 snapshot/export API，禁止直接暴露内部 Map。
6. 保持默认 MAPDB_ONLY，Redis 代码不能影响线上请求。

测试：

- Redis JSON round-trip。
- System/User/Ai thinking/tool/multimodal 消息 round-trip。
- 空 key、删除、坏 JSON、超大 value。
- Conversation、Character、generic memory key 隔离。

退出条件：

- Redis store contract tests 与 MapDB contract tests 通过。
- 线上默认行为与改造前完全一致。

### D2：兼容双写和迁移工具

任务：

1. 实现 MAPDB_PRIMARY_DUAL_WRITE。
2. MapDB 主写成功后写 Redis；Redis 写失败不影响本轮用户请求，但必须记录 repair 指标。
3. 增加批量迁移命令/任务，支持 dry-run、断点、批次、重试和幂等。
4. 迁移时使用 SET-if-absent 或带来源 hash 的保护逻辑，不盲目覆盖 Redis 中更新的数据。
5. 迁移 Conversation key、旧 Character key 和 migration marker。
6. 默认 Conversation 迁移仍调用显式 ensureMigrated(characterUuid, conversationUuid, isDefault)。
7. 迁移 marker 只有在目标写入成功后才标记完成。

推荐顺序：

~~~text
部署所有兼容版本
  -> 开启 MAPDB_PRIMARY_DUAL_WRITE
  -> 批量回填 MapDB -> Redis
  -> 校验 key 数量和消息 hash
  -> 处理差异并再次校验
~~~

滚动发布期间不允许仍在运行只写 MapDB 的旧版本实例；否则 Redis 会持续落后。

退出条件：

- 迁移任务可暂停、重启和从 checkpoint 继续。
- 迁移重复执行不会产生重复或覆盖。
- 抽样和全量 hash 校验无未解释差异。

启用 MAPDB_PRIMARY_DUAL_WRITE 之前，D3 的 turn coordinator 必须已经部署并处于可用状态。
D2 的迁移工具可以和 D3 并行开发，但不能在没有整轮并发保护时扩大双写流量。

### D3：统一调用点和默认 turn 锁

任务：

1. 替换 AbstractLLMService、CompositeRag、CharacterChatService 等硬编码 MapDB 单例。
2. 将 CleanupTask 和 MigrationService 改为调用 ShortTermMemoryService。
3. 在单实例先实现本地 per-memory lock，验证 turn 边界和异常释放。
4. 再接入 Redis 分布式 lock、续期和 compare-and-delete。
5. 流式路径把锁绑定到 SSE 生命周期；blocking 和 workflow 路径分别补回归测试。
6. 同一 Conversation 并发请求默认 reject，暂不做无界排队。

退出条件：

- 所有有状态入口均通过同一 turn coordinator。
- 并发测试证明不会静默丢失消息。
- 锁超时、续期失败、客户端断开均有确定行为。

### D4：Redis 主读灰度

任务：

1. 切换到 REDIS_PRIMARY_MAPDB_FALLBACK。
2. Redis miss 或 Redis 暂时不可用时回退 MapDB，并异步修复 Redis。
3. 在同一 turn lock 或稳定 snapshot 下比较 Redis/MapDB hash；进行中的写入差异必须单独标记，不能误报为迁移损坏。
4. 按用户或 Conversation UUID 做稳定分桶，逐步放量。
5. 观察至少一个完整业务周期，覆盖流式、阻塞、RAG AiServices、工具调用和重启。

放量顺序：

~~~text
内部测试 -> 1% -> 10% -> 50% -> 100%
~~~

硬性回滚条件：

- 任何确认的跨 Conversation 泄漏或消息丢失。
- Redis/MapDB hash 差异无法解释或持续增长。
- lock lost、坏 JSON、迁移失败出现非预期增长。
- 关键聊天错误率或 P95 明显超过 D0 基线。

### D5：Redis 全量切换

前置条件：

- D4 观察周期通过。
- 回滚演练完成。
- MapDB 与 Redis 差异为零或全部有记录并已修复。
- 所有旧实例已经下线。

操作：

1. 先进入 REDIS_PRIMARY_MAPDB_FALLBACK，并保留 MapDB 辅助写入一个明确的回滚窗口。
2. 继续保留旧 Character key 双写，直到 Conversation 兼容回滚窗口结束。
3. 关闭默认 Character key 双写前先做一次最终一致性校验。
4. 回滚窗口结束且指标稳定后，才切换 REDIS_ONLY。

### D6：清理和长期运维

只有在回滚窗口结束后执行：

1. 停止 MapDB 写入和回退。
2. 归档 MapDB 文件和迁移报告。
3. 删除未使用的 MapDB 依赖和初始化目录逻辑。
4. 保留 Redis key schema v1 的版本说明和运维脚本。
5. 保留按 Conversation/Character 删除的显式清理任务；TTL 不能替代业务删除。

## 6. 历史数据迁移细则

### 6.1 MapDB snapshot

MapDB 当前消息 Map 是私有字段，不能在外部脚本中直接读取。应新增受控 snapshot 接口：

- 只返回 memoryId、消息 JSON hash、消息数量和 JSON 内容。
- 分批迭代，不一次性加载全部会话。
- snapshot 时记录开始时间、结束时间和 MapDB 文件版本。
- 不允许把消息内容写入普通日志。

### 6.2 双写窗口

双写期间以 MapDB 为权威：

- MapDB 写成功、Redis 写失败：请求成功，记录待修复任务。
- MapDB 写失败：本轮失败，不宣称双写成功。
- Redis 写成功、MapDB 写失败：记录 divergence，不能把它当作可回滚数据。
- 删除操作必须对两端幂等执行；一端失败进入重试队列。

### 6.3 默认 Conversation 迁移

~~~text
canonical conversation key 存在
  -> 不读取旧 Character key

canonical key 不存在且 isDefault=true
  -> 读取旧 Character key
  -> 写入 Conversation key
  -> 成功后写 migration marker

isDefault=false
  -> 从空 Conversation key 开始
  -> 永不读取旧 Character key
~~~

在所有旧写入实例下线前，不得把“旧 key 不存在”永久标记为迁移完成；否则旧实例稍后写入的内容会被漏迁。

## 7. TTL、容量和故障策略

### 7.1 TTL

第一阶段默认 ttl=0（不自动过期），保持 MapDB 原有“显式删除才清理”的语义。

后续如果启用 TTL：

- 采用配置化滑动 TTL。
- 默认在成功写入时刷新，读取是否刷新需要单独评估。
- migration marker 的生命周期不能短于待迁移数据。
- Redis eviction 不能被当作正常业务删除。

### 7.2 容量

- 每个会话设置最大 JSON 字节数。
- 超限时先按现有 token window 规则裁剪；仍超限则明确失败并告警。
- 监控 value size、key 数量、内存使用和 eviction 次数。
- 生产 Redis 必须配置持久化、内存上限和恢复演练。

### 7.3 Redis 故障

发布阶段：

- MAPDB_PRIMARY_DUAL_WRITE：Redis 故障不阻断聊天，但进入 repair。
- REDIS_PRIMARY_MAPDB_FALLBACK：Redis 故障回退 MapDB，并触发告警。

稳定阶段：

- REDIS_ONLY 发生故障时，产品必须选择“短暂不可用”或“无记忆降级”，不能静默混用旧 key。
- 建议默认 fail-closed，避免用户得到看似正常但上下文错误的回答。

Redis 中保存聊天内容，必须使用网络隔离、ACL/密码/TLS（按部署环境）、持久化和不记录正文日志。

## 8. 测试计划

### 8.1 单元/契约测试

对 MapDB 和 Redis 两个 backend 运行同一组 contract tests：

- JSON 往返和消息顺序。
- System message、thinking、tool call、图片内容。
- normalizeMessages 兼容性。
- key 编码、删除和空 key。
- migration marker 幂等性。

### 8.2 Redis 集成测试

优先使用 Testcontainers Redis；CI 不支持容器时使用隔离 Redis 服务：

- TTL 和过期。
- Redis 重启、连接超时、坏 JSON。
- 双写失败和 repair。
- 两个应用实例同时读写。
- lock acquire、续期、超时、释放和 owner 校验。

### 8.3 端到端回归

至少覆盖：

1. SSE 普通聊天。
2. blockingAsk。
3. RAG AiServices/CompositeRag。
4. MCP 工具调用循环。
5. workflow AgentNode。
6. 旧 characterUuid 请求。
7. 新 conversationUuid 请求。
8. 默认和非默认 Conversation。
9. 删除 Conversation/Character 后清理 Redis。
10. 后端重启后恢复上下文。

### 8.4 并发与故障注入

- 同一 Conversation 并发发送两条消息，必须串行或明确拒绝。
- 不同 Conversation 并发发送，不能互相阻塞或污染。
- 在 LLM 调用中途杀死实例，锁最终可释放，消息不被半写覆盖。
- 在 Redis 写入、续期、删除、迁移各阶段注入失败。
- 验证重复请求、SSE 重连和模型重试不会重复追加 AI 消息。

## 9. 监控与验收指标

新增指标：

~~~text
short_memory_read_total{backend,result}
short_memory_write_total{backend,result}
short_memory_fallback_total{reason}
short_memory_migration_total{result}
short_memory_dual_write_mismatch_total
short_memory_lock_total{result}
short_memory_lock_lost_total
short_memory_json_error_total
short_memory_operation_latency
short_memory_value_bytes
~~~

交付前必须满足：

- 跨 Conversation 泄漏：0。
- 确认的消息丢失：0。
- 未解释的 Redis/MapDB hash 差异：0。
- 非默认 Conversation 读取旧 Character key：0。
- migration marker 在目标写入前完成：0。
- 所有短期记忆入口回归通过。
- Redis 重启、应用重启和回滚演练通过。
- Redis 读写 P95 相比 D0 基线在约定阈值内。

## 10. 回滚 Runbook

### 10.1 仍在双写窗口

~~~text
1. 停止继续放量。
2. backend-mode 切回 MAPDB_PRIMARY_DUAL_WRITE 或 MAPDB_ONLY。
3. 保留 Redis 写入一段时间，避免产生新的不可回放数据。
4. 查询 mismatch、fallback、lock lost 和 repair 指标。
5. 修复后重新校验 MapDB/Redis hash。
~~~

### 10.2 已进入 REDIS_ONLY

不得直接切换旧的、只理解 Character key 的二进制版本。应先：

1. 恢复仍支持 conversation key 的兼容版本。
2. 从 Redis 导出受影响 Conversation。
3. 回填 MapDB conversation key 并校验 hash。
4. 切换 MAPDB_ONLY。
5. 保留 Redis 数据用于排查，不能直接删除。

如果旧版本只支持 Character key，非默认 Conversation 无法无损回滚；这必须在发布审批中明确为已知限制。

## 11. 文件级变更清单

### 新增

~~~text
server/zhimesh-common/src/main/java/.../memory/shortterm/RedisChatMemoryStore.java
server/zhimesh-common/src/main/java/.../memory/shortterm/ShortTermMemoryService.java
server/zhimesh-common/src/main/java/.../memory/shortterm/ShortTermMemoryBackendRouter.java
server/zhimesh-common/src/main/java/.../memory/shortterm/ShortTermMemoryTurnCoordinator.java
server/zhimesh-common/src/test/.../RedisChatMemoryStoreTest.java
server/zhimesh-common/src/test/.../ShortTermMemoryMigrationIntegrationTest.java
~~~

### 修改

~~~text
MapDBChatMemoryStore.java
ShortTermMemoryMigrationService.java
ShortTermMemoryCleanupTask.java
AbstractLLMService.java
CompositeRag.java
CharacterChatService.java
LocalAgentService.java
ZhiMeshProperties.java
application.yml / application-dev.yml / application-prod.yml
zhimesh-common/pom.xml
~~~

### 保留但暂不删除

~~~text
MapDB 依赖
MapDB 文件
旧 Character key
short-memory-dual-write-default
长期记忆向量表和 metadata
~~~

## 12. 预计工作量与执行顺序

按一名熟悉 Spring、LangChain4j 和 Redis 的后端开发者估算：

| 批次 | 内容 | 估算 |
|---|---|---:|
| D0 | 基线、指标、key 盘点 | 0.5—1 天 |
| D1 | Store 抽象、Redis JSON store、契约测试 | 2—3 天 |
| D2 | 双写、snapshot、迁移任务、hash 校验 | 2—4 天 |
| D3 | 全入口接入、turn lock、故障处理 | 3—5 天 |
| D4 | 集成/并发/故障测试和灰度 | 2—4 天 |
| D5/D6 | 全切换、观察、清理和文档 | 1—3 天 |

稳定生产版本预计 2—3 周；只做单实例、无历史迁移的 Redis MVP 不能视为本计划完成。

## 13. 最终交付物

交付时必须同时提供：

1. 代码提交清单和配置说明。
2. MapDB snapshot/Redis migration 报告。
3. Redis/MapDB hash 校验结果。
4. 单元、集成、并发、故障注入测试报告。
5. 灰度指标和异常处理记录。
6. 回滚演练记录。
7. Redis 容量、TTL、备份、ACL/TLS 运维说明。
8. 已知限制：旧二进制回滚对非默认 Conversation 的影响。

只有“代码、数据、测试、灰度、回滚”全部通过，才允许把 backend-mode 设置为 REDIS_ONLY。
# 状态：已废止（仅保留为历史设计记录）

> 本计划中的 MapDB、双写、fallback 和迁移步骤不得再用于部署。项目已永久切换为 Redis-only，旧测试短期记忆不迁移。当前生产说明以[短期记忆 Redis-only 实施报告](../reports/short-term-memory-redis-implementation-report.zh-CN.md)为准。
