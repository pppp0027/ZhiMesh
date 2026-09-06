# Character / Conversation 改造实施报告

## 1. 本次实施边界

本次按照 `agent-conversation-refactoring-plan.zh-CN.md` 完成后端安全增量改造，遵循两个约束：

1. 不重写现有 Character、RAG、MCP、长期记忆和 LLM 编排模型。
2. 统一 Chunk 只实施阶段 B（Schema、血缘模型、只读接口），不切换实验或生产知识库的写入与检索链路。

真实数据库迁移尚未执行，E0～E4 冻结文件和 RAGAS 评测文件未修改。

## 2. 已完成内容

### 2.1 MCP / Character 正确性

- 修复 User MCP 更新对象错误。
- Character 的 MCP/知识库绑定统一采用 null、空集合、非空集合语义。
- 运行时同时校验系统 MCP 和用户 MCP 的启用状态及参数完整性。
- URL 参数编码、敏感日志收敛和同名工具冲突处理已完成。

### 2.2 Conversation 与消息归属

- 新增 `adi_conversation`、Conversation CRUD 和默认会话并发唯一约束。
- `adi_character_message` 保留 Character 字段，同时新增 Conversation 双归属字段。
- 新增 Conversation 消息分页、父问题归属校验和 Character 删除级联软删除。
- 历史消息回填使用持久化高水位、小批次事务和可恢复进度，不引入额外任务框架。

### 2.3 聊天入口和记忆边界

- `AskReq` 支持 `characterUuid` / `conversationUuid` 兼容矩阵。
- `ChatContextResolver` 统一处理用户、Character、Conversation 和短期记忆 Key。
- 显式拒绝越权 Conversation、已删除 Conversation 和 Character 不一致请求。
- 用户问题和 AI 回答写入同一 Conversation；重新生成不能跨 Conversation 引用父问题。
- SSE `complete` 在消息事务成功返回后发送。
- 短期记忆使用 `conversation:{conversationUuid}`；只有默认 Conversation 可以迁移和灰度双写旧 Character Key。
- 长期记忆仍使用 `characterId`，未改变共享边界。

### 2.4 统一 Chunk 关闭态基础设施

- 新增 Chunk Set、标准 Chunk、Index Build、Graph Release Schema 和 Java 血缘模型。
- KnowledgeBaseItem、图谱 Segment 和图元素来源增加版本摘要字段。
- 提供按知识条目查询 Chunk Set 和 Chunk 的只读接口。
- `zhimesh.indexing.canonical-chunk-enabled=false`，现有向量化、图谱化和检索代码未接入新表。

## 3. 默认开关

```yaml
zhimesh:
  conversation:
    enabled: false
    auto-create-default: false
    message-dual-write: false
    short-memory-use-conversation: false
    fallback-to-character-memory-key: true
    short-memory-dual-write-default: false
  indexing:
    canonical-chunk-enabled: false
```

在执行数据库迁移和数据库级验证前不得开启这些写入开关。

## 4. 验证结果

- `mvn -pl zhimesh-common test`：33 项测试通过。
- `mvn -pl zhimesh-chat -am test`：通过。
- `mvn -pl zhimesh-bootstrap -am package -DskipTests`：完整后端打包通过。
- `all_ddl.sql` 中新增表、索引和触发器名称无重复。

测试日志中的既有编译警告和 RAG 失败路径堆栈来自原项目测试场景，不是测试失败。

## 5. 后续发布顺序

1. 备份真实数据库，在测试环境依次执行 `014`、`015`、`016`。
2. 执行计划书中的字段、索引、唯一约束和一致性校验 SQL。
3. 先开启 Conversation 主开关，只测试显式 `conversationUuid`。
4. 再开启消息双写，观察新消息 Conversation 字段完整率。
5. 运行受控历史回填并复核不一致计数为 0。
6. 开启默认 Conversation 和短期记忆 Conversation Key，保留默认会话旧 Key 双写回滚窗口。
7. RAGAS 汇总、影子对比和回滚演练完成前，不得开启统一 Chunk 写入或切读。

阶段 C～E（影子写、历史 Chunk 构建、灰度切读和旧数据清理）不在本次交付中，需根据实际实验结论单独实施。
