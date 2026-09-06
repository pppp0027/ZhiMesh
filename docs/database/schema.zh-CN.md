# 数据库结构与真实状态

## 结构依据

当前数据库目标结构按以下优先级整理：

1. Java 实体、Mapper 与实际查询字段；
2. 应用运行时创建向量表的逻辑；
3. `server/db_migration/001`～`035` 增量迁移后的结果；
4. `all_ddl.sql` 的全新安装快照。

项目中曾保存的 IDE 数据库快照包含 37 张静态表和 3 张 BGE 向量表，但缺少后续版本新增的会话、回填、标准分块、索引构建、图发布与溯源表。因此该快照只代表某次运行现场，不能作为当前代码的建表基线。当前 `all_ddl.sql` 定义 43 张静态表。

## 表分类

| 分类 | 主要表 |
| --- | --- |
| 用户与系统 | `adi_user`、`adi_user_day_cost`、`adi_sys_config`、`adi_file`、`adi_prompt` |
| 模型平台 | `adi_model_platform`、`adi_ai_model`、`adi_llm_call_record`、`adi_openrouter_model_state`、`adi_openrouter_sync_run` |
| 角色与会话 | `adi_character*`、`adi_conversation`、`adi_conversation_backfill`；回答溯源包含向量、图谱、长期记忆和 BM25 关键词命中 |
| 知识库 | `adi_knowledge_base*`，包括 item、qa、chunk、chunk_set、index_build、graph_release、bm25_document、bm25_posting、route_profile_set、route_profile 等 |
| 工作流 | `adi_workflow*`，包括 component、node、edge、runtime、runtime_node |
| 绘图 | `adi_draw`、`adi_draw_star`、`adi_draw_comment` |
| MCP 与外部 API | `adi_mcp`、`adi_user_mcp`、`adi_user_ext_api_key` |

完整静态表名单由 [`verify_schema.sql`](../../server/db_migration/verify_schema.sql) 维护并自动校验，避免在多份文档中产生不同清单。

`adi_user.avatar` 保存用户头像的公开静态路径。内置头像位于用户前端的 `/avatars/users/`，迁移 `032` 为已有用户随机分配头像，注册和后台新增用户时由服务端从同一头像池随机选择。

## 运行时向量表

pgvector 表由 `PgVectorEmbeddingStoreConfig` 使用 `createTable(true)` 按模型动态创建，所以不应在 `all_ddl.sql` 中固定一种维度。默认 `bge-small-zh-v1.5` 在本项目常量中配置为 512 维，对应现场常见表：

- `adi_knowledge_base_embedding_bge_512`
- `adi_character_memory_embedding_bge_512`
- `adi_character_episodic_memory_embedding_bge_512`

切换嵌入模型后，表名后缀与维度会随配置变化。不能仅因旧表仍存在就判断当前应用仍在使用该表。

## 知识库路由画像

`adi_knowledge_base_route_profile_set` 保存知识库级不可变画像发布版本，`adi_knowledge_base_route_profile` 保存数量有界的 OVERVIEW、DOCUMENT、TOPIC 多主题向量。画像向量使用维度无关的 PostgreSQL `real[]` 持久化，在线请求读取 Redis 版本化副本并在 Java 内存计算余弦相似度，不在真实知识分块表上执行前置向量搜索。

模型配置以 `adi_model_platform.api_key` 作为唯一平台凭据字段；`secret_key` 已随千帆兼容逻辑移除。模型上下文预算只使用 `adi_ai_model.max_input_tokens`；旧的 `setting`、`context_window` 和 `max_output_tokens` 已在迁移 031 中删除。图片规格、语音选项和嵌入维度等结构化能力继续保存在 `adi_ai_model.properties`。

`adi_openrouter_model_state` 按 `platform + model_name` 保存 OpenRouter 目录资格、端点性能、真实 SSE 探测时延、上下架决策和失败原因；未达到导入条件的模型也可以在 `model_id` 为空时保留审计状态。`adi_openrouter_sync_run` 保存完整目录同步、轻量测活、管理员手动或启动恢复任务的计数和脱敏变更摘要。自动同步只禁用不再符合条件的受管模型，不物理删除模型行；当立即下架会使已验证免费模型低于安全线时，先准备替代模型再统一切换。

知识库主表中的 `route_profile_generation` 表示最新知识源版本，`route_profile_active_generation` 表示仍在服务的画像版本。知识更新期间两者可以不同，旧画像只提供“相关”正证据；低分结果必须按不确定放行真实检索。

## 新库与升级库

- 新库：执行 `all_ddl.sql`、`all_dml.sql`，再从中英文 DML 中选择一个。
- 升级库：严格按编号执行迁移；`004` 只选择一个语言版本。
- 两种方式完成后都运行 `verify_schema.sql`。
- `vector`、`age` 扩展的安装依赖数据库管理员权限，DDL 中仅保留提示，不擅自启用。

详细步骤见[数据库初始化与迁移](../../server/db_migration/README.zh-CN.md)。
