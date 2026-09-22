# 数据库初始化与迁移

数据库脚本以当前 Java 实体、Mapper、运行时向量表创建逻辑，以及 `001`～`043` 迁移后的目标结构为准。完整结构说明见 [`docs/database/schema.zh-CN.md`](../../docs/database/schema.zh-CN.md)。

## 依赖

- PostgreSQL 16
- pgvector：使用 PostgreSQL 向量检索时必需
- Apache AGE：使用 PostgreSQL 图检索时必需；若配置 Neo4j 可不安装 AGE

扩展需要由有权限的数据库管理员安装并启用：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;
```

## 全新安装

先创建空数据库，再按顺序执行：

1. `all_ddl.sql`：创建当前版本的静态表、索引、约束、触发器与注释。
2. `all_dml.sql`：写入系统配置、模型平台、模型等基础数据。
3. 二选一执行 `all_dml_cn.sql` 或 `all_dml_en.sql`：写入对应语言的展示数据。

示例：

```bash
createdb -h localhost -U postgres zhimesh
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d zhimesh -f all_ddl.sql
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d zhimesh -f all_dml.sql
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d zhimesh -f all_dml_cn.sql
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d zhimesh -f verify_schema.sql
```

向量表不写死在 `all_ddl.sql` 中。应用会根据嵌入模型和维度创建相应表，例如默认 BGE 模型使用 `*_embedding_bge_512`。

## 从旧版本升级

不要在已有数据库上执行 `all_*`。按编号依次执行增量脚本：

| 编号 | 内容 |
| --- | --- |
| 001 | 对话消息与记忆向量引用关系 |
| 002 | 替换已弃用模型 |
| 003 | 用户语言偏好 |
| 004 | 预设角色增强；中文与英文脚本二选一 |
| 005 | OpenAI TTS/ASR 支持 |
| 006 | Conversation 到 Character 的结构重命名 |
| 007 | 模型字段和知识库分段策略 |
| 008 | 绘图与 MCP 外部 API |
| 009 | 工作流与 LLM 调用可观测性 |
| 010 | 图像模型替换 |
| 011 | 图检索跳数 |
| 012 | BGE 重排配置 |
| 013 | 图元素来源追踪 |
| 014 | 独立会话表 |
| 015 | 消息关联会话 |
| 016 | 标准化知识库分块、索引构建与图发布结构 |
| 017 | MCP Streamable HTTP 传输支持 |
| 018 | 角色预设 Agent 能力开关 |
| 019 | 最小系统知识库字段与索引 |
| 020 | 不可逆的逻辑删除转物理删除迁移，执行前必须完成备份与预检 |
| 021 | 工作流实用节点 |
| 022 | 停用尚未支持的工作流节点 |
| 023 | 首批实用 MCP 预设 |
| 024 | 扩充 MCP 工具预设 |
| 025 | 清理用户角色中遗留的系统知识库 ID |
| 026 | 基于 canonical Chunk 的 BM25 document/posting 表与 FULLTEXT 构建类型 |
| 027 | 知识条目的 BM25 状态、开始时间与完成时间字段，供用户端和管理端展示 |
| 028 | 回答级 BM25 关键词命中溯源与消息命中标记 |
| 029 | 知识库多主题路由画像集、画像明细与 Generation 状态字段 |
| 030 | 支持没有数据库模型记录的内置本地嵌入模型，以模型身份字符串作为兼容性边界 |
| 031 | 删除平台和模型配置中已停用且运行时不再读取的旧字段 |
| 032 | 新增用户头像路径，并为现有用户随机分配内置头像库中的头像 |
| 033 | 为现有用户幂等写入 9 条常用提示词；中文与英文脚本二选一 |
| 034 | 按失败节点/成功终点节点修复历史工作流悬挂状态，同步中断节点，并增加运行心跳索引 |
| 035 | 扩展模型 ID 长度与平台内唯一约束，新增 OpenRouter 模型状态表和同步审计表 |
| 036 | 工作流取消状态、历史分页与节点详情查询索引 |
| 037 | 图元素贡献来源追踪 |
| 038 | 图元素语义标识与结构化抽取贡献 |
| 039 | 团队表与知识库三级归属（个人/团队/企业）字段 |
| 040 | 下线 is_public 公开列：可见性按归属推导，企业库新增 company_scope（STAFF 全员/EXECUTIVE 管理层），存量公开个人库升级为 COMPANY/STAFF |
| 041 | 角色对话 Agentic 改造：adi_character 新增 is_agentic 开关，新增 adi_character_message_tool_call 工具调用轨迹表（工具名、入参、结果摘要、耗时、成败与轮内序号） |
| 042 | 角色对话 Agentic 模式成为产品默认：存量角色全部开启，is_agentic 列默认值翻转为 true（未绑知识库、无可运行工作流的角色无工具可调，行为不受影响） |
| 043 | mcp-servers 演示三件套（人事 / IT 服务台 / 财务报销助手）由用户自建角色转为系统预设（adi_character_preset，is_system=true，沿用原角色 uuid 作幂等键），并删除原用户角色及其会话、消息与工具调用痕迹 |

BM25 上线时必须先依次执行 `026`、`027`、`028`，再启动设置了 `ZHIMESH_BM25_ENABLED=true` 的应用。已有普通知识库可调用 `POST /knowledge-base/indexing/{uuid}?indexTypes=fulltext` 批量回填；系统知识库通过 `POST /admin/kb/items/indexing-list` 并传入 `indexTypes=fulltext` 回填。在某个知识库的全部条目都具备当前 FULLTEXT 构建前，在线请求会自动保持 Vector/Graph 双路检索。


035 不会立即增删或上下架模型；它只准备自动同步所需结构。每日任务只查询配置的 `OpenRouter` 平台：新模型仅补足通过验证的免费模型，已有该平台模型默认先重新验证是否免费、可调用且速度达标，不符合条件时只将 `is_enable` 设为 false；非 OpenRouter 平台和受保护模型不会被修改。036 不增加表字段：状态列沿用 smallint，并补充 6=取消中、7=已取消及节点 5=已取消的说明；同时重建活动运行索引并增加历史分页、节点按需详情查询所需索引。

031 会永久删除 `adi_model_platform.secret_key`，以及 `adi_ai_model.setting/context_window/max_output_tokens`。执行前需要备份数据库，并确保部署代码已经不再映射这些字段。032 新增 `adi_user.avatar`，并为头像为空的现有用户随机分配内置头像。033 应根据部署语言选择 `033_seed_common_prompts_cn.sql` 或 `033_seed_common_prompts_en.sql`，脚本不会覆盖用户已有的同名提示词。034 只修复工作流运行记录状态：失败节点可证明工作流失败，成功的显式 End 节点或图中叶子节点可证明工作流成功；其余超过 30 分钟的旧 READY/DOING 记录才标记为执行中断，并同步修复仍未终止的子节点。脚本还会创建运行心跳巡检使用的部分索引，不影响等待人工输入的状态 5。迁移完成后执行 `verify_schema.sql`。该脚本只读取元数据，不修改业务数据；若缺失表、缺失字段或缺失索引计数不为 0，说明迁移尚未完成。

## 脚本维护规则

- `all_ddl.sql` 必须代表全新安装后的最终静态结构。
- 已发布的编号迁移不修改语义；新变更新增下一个编号脚本。
- 结构变化必须同时更新全量 DDL、增量迁移、实体/Mapper 和校验脚本。
- 生产执行前必须备份，并在测试库使用 `ON_ERROR_STOP=1` 完整演练。
