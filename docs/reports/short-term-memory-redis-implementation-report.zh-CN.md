# 短期记忆 Redis-only 实施报告

## 当前结论

短期记忆已永久切换为 Redis-only。项目不再包含 MapDB 依赖、实现、后端模式、双写、回退、迁移任务、迁移标记或旧 Character 短期记忆 key。

这次切换按项目现状处理：旧数据仅来自测试用户，因此不备份、不迁移。新版本部署后，每个 Conversation 会从 Redis 中已有的短期记忆继续；如果 Redis 是空实例，则从空短期上下文开始。PostgreSQL 聊天记录和长期向量记忆不受影响。

## 运行边界

- Character/Agent 有状态聊天必须先解析出具体 Conversation，memoryId 固定为 `conversation:{conversationUuid}`。
- Character/Agent Redis key 使用 `zhimesh:short-memory:v1:conversation:{base64url(uuid)}`。
- 知识库等非 Character 聊天继续使用 `zhimesh:short-memory:v1:memory:{base64url(memoryId)}` 通用命名空间；旧 Character namespace 已删除。
- 消息仍使用 LangChain4j JSON 编解码，保留 tool、thinking 和多模态消息结构。
- 写入可配置滑动 TTL；`0s` 表示不因 TTL 自动过期，只在业务删除 Conversation/Character 时清理。
- 单值大小默认限制为 1 MiB，超过限制直接失败，避免异常上下文占满 Redis。
- 任何非空 memoryId 都强制获取 Redis 分布式 turn lock；锁会续租，并在正常完成、SSE 结束、异常或取消时释放。
- Redis 读取、写入或加锁失败会让当前有状态请求失败，不会回退到本地存储。

## 保留配置

```properties
ZHIMESH_SHORT_MEMORY_TTL=0s
ZHIMESH_SHORT_MEMORY_MAX_VALUE_BYTES=1048576
ZHIMESH_SHORT_MEMORY_TURN_LOCK_WAIT=2s
ZHIMESH_SHORT_MEMORY_TURN_LOCK_LEASE=30s
```

已删除且不再生效的旧配置包括：

- `ZHIMESH_SHORT_MEMORY_BACKEND_MODE`
- `ZHIMESH_SHORT_MEMORY_TURN_LOCK_ENABLED`
- `ZHIMESH_SHORT_MEMORY_MIGRATION_ON_STARTUP`
- `ZHIMESH_SHORT_MEMORY_MIGRATION_DRY_RUN`
- `ZHIMESH_SHORT_MEMORY_MIGRATION_BATCH_SIZE`
- `ZHIMESH_SHORT_MEMORY_MIGRATION_CURSOR`
- Conversation 下的 Character key fallback、legacy dual-write 和 rollout 开关
- `local.chat-memory`

升级服务器配置时应主动删除这些变量，避免值班人员误以为还能切回 MapDB。

## 生产部署要求

1. 在发布 API 前确认 Redis 可连接，认证信息正确。
2. Redis 应启用 AOF 或符合业务恢复目标的持久化方案。
3. 使用 `noeviction` 或明确评估过的淘汰策略，避免短期记忆被静默淘汰。
4. 按并发 Conversation 数、平均消息窗口和 TTL 估算内存，并配置监控告警。
5. 多实例必须连接同一个 Redis 集群或同一逻辑数据源，否则 turn lock 和短期上下文会分裂。
6. 不要把旧 `chat-memory.db` 放进新容器；新代码不会读取它。

推荐发布顺序：

1. 备份并验证 Redis 配置与持久化状态。
2. 部署新 API 镜像。
3. 检查 API 和 Redis health check。
4. 用新测试 Conversation 完成两轮以上对话，确认上下文连续。
5. 并发请求同一 Conversation，确认第二个请求等待或返回忙错误，而不是覆盖消息。
6. 中断一次 SSE，再发起下一轮请求，确认锁已释放。
7. 删除 Conversation，确认对应 Redis key 被清理。

## 验收标准

- 构建依赖树中不存在 `org.mapdb:mapdb`。
- 源码中不存在 MapDB store、迁移 runner、backend mode、fallback 或 legacy dual-write。
- Redis 中只产生 Conversation/通用 memory key 和 turn lock key，不产生旧 Character namespace 或 migration marker。
- 两个 API 实例访问同一 Conversation 时读取一致。
- Redis 不可用时有状态聊天明确失败并产生日志/告警。
- PostgreSQL 的消息记录、长期语义记忆、长期情景记忆、RAG 和 Workflow 行为保持原有边界。

## 故障处理

Redis-only 不提供 MapDB 回滚路径。Redis 故障时应恢复 Redis 服务或切换到已验证的 Redis 副本，然后重试请求。

如果 Redis 数据丢失，只会丢失短期上下文窗口；PostgreSQL 中已持久化的聊天消息仍保留，但当前实现不会自动把历史消息重建为 Redis 短期记忆。恢复后 Conversation 从新的短期上下文开始。

旧 `chat-memory.db` 可在确认新版本稳定后由服务器运维人员单独归档或删除；它不属于应用启动流程，也不应再挂载到容器。
