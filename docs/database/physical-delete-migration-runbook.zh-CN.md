# 永久删除与移除逻辑删除机制：生产迁移执行手册

> 目标：将项目从“逻辑删除（`is_deleted`）”切换为“删除即物理删除”，永久清除当前已逻辑删除的数据，并从数据库和后端代码中移除 `is_deleted`。本手册不引入回收站、审计表或自动清理任务；`is_enable` 保留，仍只表示“停用”。

## 0. 执行结论与边界

**最终行为**

- 新的删除请求执行真实 `DELETE`；删除后不能在本库恢复。
- 全部含 `is_deleted` 列的业务表移除此列。
- 当前 `is_deleted = true` 的行会被永久删除，不能因删列而重新显示。
- `is_enable` 不变：`false` 表示记录还在但不参与运行时使用；`true` 表示可用。

**本次不做**

- 不创建回收站、不保留删除审计副本、不实现恢复接口。
- 不在生产库直接试错；数据库变更必须在预演环境验证后执行。

**必须接受的后果**

- 物理删除不可逆；恢复只能依赖迁移前备份。
- 删除平台、模型、工作流或 MCP 时，如有业务代码以 JSON/文本方式保存其 ID 或名称，数据库无法自动识别这种引用。为防止产生坏配置，永久删除接口必须在应用层拒绝“仍被引用”的对象，或由管理员先解除引用。

## 1. 项目现状与改造范围

项目的 `BaseEntity` 定义了 `isDeleted` 字段；模型和平台服务将删除实现为 `softDelete()`，并在普通查询中增加 `is_deleted = false`。数据库初始化快照在 `server/db_migration/all_ddl.sql` 中定义了该列、依赖它的部分索引以及一个唯一约束。

涉及范围：

1. 数据库：生产库中每一个具有 `is_deleted` 列的 `public` 表；不能只修改模型、平台、工作流和 MCP 表。
2. 代码：实体、服务、Mapper XML、控制器调用链、测试和初始化逻辑。
3. 初始化快照：`all_ddl.sql`、必要的 `verify_schema.sql`；**已有编号迁移不改写**。
4. 新增一个只用于已存在数据库的增量迁移，例如 `020_remove_logical_delete.sql`。

## 2. 角色、停机与成功标准

| 角色 | 责任 |
|---|---|
| 发布负责人 | 决定窗口、确认备份可恢复、执行 Go/No-Go 决策 |
| 应用负责人 | 合并代码、完成构建和自动化测试、停止/启动应用 |
| 数据库执行人 | 在预演与生产执行 SQL、保留执行日志、完成校验 |

**维护窗口要求**

1. 先部署已包含物理删除代码、但尚未访问已删列的候选版本到预演环境。
2. 生产执行时停止后端实例和所有异步任务；停止写入后再开始备份。
3. 迁移 SQL 成功、应用启动检查通过、核心冒烟测试通过之前，不恢复流量。

**上线通过标准**

- 数据库中不存在 `is_deleted` 列；不存在依赖该列的索引、约束、视图或函数。
- 后端完整构建和测试通过；全仓源码没有运行时代码继续引用 `isDeleted` 或 `is_deleted`。
- 平台、模型、MCP、工作流的查询、新增、更新、停用、删除均通过冒烟测试。
- 已逻辑删除行的数量为 0，且删除后不会出现在页面或 API 中。

## 3. 迁移前硬性门禁（任何一项失败即停止）

### 3.1 备份与恢复演练

在维护窗口前，对生产库做自定义格式备份；不要把密码写进命令或版本库。使用受控环境变量或 `.pgpass` 提供认证。

```powershell
$env:PGHOST = '<生产主机>'
$env:PGPORT = '5432'
$env:PGDATABASE = '<数据库名>'
$env:PGUSER = '<只具备所需权限的账号>'
pg_dump --format=custom --file "backup-before-physical-delete-YYYYMMDD.dump"
pg_restore --list "backup-before-physical-delete-YYYYMMDD.dump"
```

将备份恢复到独立的预演库，并在该预演库完整跑完本手册第 5 至第 8 节。仅“备份命令成功”不算通过，必须验证 `pg_restore` 可用。

### 3.2 只读预检 SQL

以下脚本只读，输出将决定最终迁移脚本中的表清单、索引重建语句和删除顺序。保存输出到发布记录；不要在输出中记录 API Key 或业务密文。

```sql
-- A. 需要移除 is_deleted 的实际表清单
SELECT table_schema, table_name
FROM information_schema.columns
WHERE table_schema = 'public' AND column_name = 'is_deleted'
ORDER BY table_name;

-- B. 每张表中待永久删除的行数（动态 SQL；只读）
DO $$
DECLARE r record; n bigint;
BEGIN
  FOR r IN
    SELECT table_schema, table_name
    FROM information_schema.columns
    WHERE table_schema = 'public' AND column_name = 'is_deleted'
    ORDER BY table_name
  LOOP
    EXECUTE format('SELECT count(*) FROM %I.%I WHERE is_deleted', r.table_schema, r.table_name) INTO n;
    RAISE NOTICE '% . % : % rows marked deleted', r.table_schema, r.table_name, n;
  END LOOP;
END $$;

-- C. 所有外键：若有结果，必须先明确子表 -> 父表删除顺序
SELECT conrelid::regclass AS child_table,
       confrelid::regclass AS parent_table,
       conname,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE contype = 'f'
  AND connamespace = 'public'::regnamespace
ORDER BY 1, 2, 3;

-- D. 依赖 is_deleted 的索引、约束、视图、物化视图或函数
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public' AND indexdef ILIKE '%is_deleted%'
ORDER BY tablename, indexname;

SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
  AND pg_get_constraintdef(oid) ILIKE '%is_deleted%'
ORDER BY 1, 2;

SELECT schemaname, viewname, definition
FROM pg_views
WHERE schemaname = 'public' AND definition ILIKE '%is_deleted%'
ORDER BY viewname;

SELECT schemaname, matviewname, definition
FROM pg_matviews
WHERE schemaname = 'public' AND definition ILIKE '%is_deleted%'
ORDER BY matviewname;

SELECT n.nspname AS schema_name, p.proname AS function_name,
       pg_get_functiondef(p.oid) AS definition
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND pg_get_functiondef(p.oid) ILIKE '%is_deleted%'
ORDER BY p.proname;
```

### 3.3 代码预检

在仓库根目录运行；结果中的增量迁移和本文档可排除，业务 Java/XML/前端运行时代码必须为 0。

```powershell
Get-ChildItem server,admin-web,user-web -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch '\\(target|node_modules|\.git)\\' } |
  Select-String -Pattern 'is_deleted|isDeleted|softDelete' -CaseSensitive:$false |
  Select-Object Path,LineNumber,Line
```

### 3.4 Go/No-Go 条件

只有同时满足以下条件才允许进入第 5 节：

- 备份和恢复预演已成功；应用已停止写入。
- 外键删除顺序已经确认；如果第 3.2-C 有结果，最终脚本必须按子表到父表顺序删除，不能使用未排序的通用循环。
- 每一条依赖 `is_deleted` 的索引/约束/视图/函数都有替代定义或确认应删除。
- 所有待删除数据已由业务负责人确认可永久清除。

## 4. 代码改造清单（先完成，再迁移生产库）

1. 从 `BaseEntity` 删除 `isDeleted` 字段及访问器。
2. 删除所有实体/DTO/Mapper XML 中的 `is_deleted` 映射。
3. 移除所有 `eq(...IsDeleted, false)`、`is_deleted = false` 和相似过滤条件。
4. 将各服务的 `softDelete(id)` 实现改为物理删除：`deleteById(id)` 或显式 `DELETE ... WHERE id = ?`。方法可保留原名称以减少控制器改动，但推荐重命名为 `deletePermanently`，使语义可见。
5. 删除前仍保留“对象存在”校验；删除成功后应清理内存初始化器/缓存，例如模型初始化器中对应模型必须移除。
6. 保留 `is_enable` 相关查询与接口：它是停用开关，不是删除标记。
7. 更新 `server/db_migration/all_ddl.sql`：删除列、列注释、包含该列的索引谓词和唯一约束字段；为新库创建正确的索引/约束。
8. 添加回归测试：物理删除后 `SELECT ... WHERE id = ?` 返回 0 行；停用后仍返回 1 行且运行时列表不包含该对象。

**当前源代码已确认的特殊点**

- 模型可用查询同时要求 `is_enable = true` 与 `is_deleted = false`；改造后只保留 `is_enable = true`。
- 平台删除当前会拒绝删除仍有未逻辑删除模型的平台；物理删除方案仍应拒绝删除仍有关联模型的平台，避免留下无平台模型。
- `adi_workflow_component` 当前唯一约束是 `(name, is_deleted)`；迁移后需改成 `UNIQUE (name)`。由于第 5 节先清理逻辑删除行，正常情况下不会因历史同名行导致冲突，但预演必须验证。

## 5. 数据库迁移脚本的组成

为已存在数据库新增 `server/db_migration/020_remove_logical_delete.sql`。不要编辑已经执行过的 001-019 迁移；新库则通过修改后的 `all_ddl.sql` 获得最终结构。

脚本必须使用：

```sql
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '10min';
```

**5.1 删除现有逻辑删除数据**

最终脚本需要按第 3.2-C 确认的子表到父表顺序，为每个表写明确的 `DELETE`。不要在生产环境使用未排序的动态循环删除。每条删除都应只删除 `is_deleted = true` 的记录。

示例模板（表名和顺序必须以预检结果为准）：

```sql
-- 先子表，再父表；以下只是模板，不能直接替代预检结果。
DELETE FROM public.adi_workflow_runtime_node WHERE is_deleted = true;
DELETE FROM public.adi_workflow_runtime      WHERE is_deleted = true;
DELETE FROM public.adi_workflow_edge         WHERE is_deleted = true;
DELETE FROM public.adi_workflow_node         WHERE is_deleted = true;
DELETE FROM public.adi_workflow              WHERE is_deleted = true;

DELETE FROM public.adi_user_mcp              WHERE is_deleted = true;
DELETE FROM public.adi_mcp                   WHERE is_deleted = true;

DELETE FROM public.adi_ai_model              WHERE is_deleted = true;
DELETE FROM public.adi_model_platform        WHERE is_deleted = true;
```

**5.2 重建依赖索引和约束**

`ALTER TABLE ... DROP COLUMN is_deleted` 会删除依赖该列的索引或约束。因此，先根据第 3.2-D 的输出，在 `DROP COLUMN` 后立即创建替代结构。初始化快照中至少需要处理如下模式：

```sql
-- 示例：去掉 is_deleted 后保留原有业务语义。
ALTER TABLE public.adi_workflow_component
  ADD CONSTRAINT uk_workflow_component_name UNIQUE (name);

CREATE INDEX idx_kb_chunk_set_item_active
  ON public.adi_knowledge_base_chunk_set (kb_item_uuid, is_active);
CREATE UNIQUE INDEX uk_kb_chunk_set_source_config
  ON public.adi_knowledge_base_chunk_set (kb_item_uuid, source_content_hash, split_config_hash);
CREATE UNIQUE INDEX uk_kb_chunk_set_active_item
  ON public.adi_knowledge_base_chunk_set (kb_item_uuid) WHERE is_active = true;
CREATE UNIQUE INDEX uk_kb_chunk_set_index
  ON public.adi_knowledge_base_chunk (chunk_set_uuid, chunk_index);
CREATE UNIQUE INDEX uk_kb_index_build_active
  ON public.adi_knowledge_base_index_build (kb_item_uuid, index_type) WHERE is_active = true;
CREATE UNIQUE INDEX uk_kb_index_build_inflight_target
  ON public.adi_knowledge_base_index_build (kb_item_uuid, index_type, build_key_hash)
  WHERE status IN ('PENDING', 'BUILDING', 'READY', 'ACTIVE');
CREATE UNIQUE INDEX uk_kb_graph_release_item_build
  ON public.adi_knowledge_base_index_build (graph_release_uuid, kb_item_uuid)
  WHERE index_type = 'GRAPH';
CREATE INDEX idx_kb_index_build_recovery
  ON public.adi_knowledge_base_index_build (status, update_time);
CREATE UNIQUE INDEX uk_kb_graph_release_active
  ON public.adi_knowledge_base_graph_release (kb_uuid) WHERE is_active = true;
```

迁移负责人必须把第 3.2-D 输出与该段逐项比对；这段不是完整索引清单。

**5.3 删除列**

在依赖项替代方案已写入迁移脚本后，对第 3.2-A 返回的每一张表执行：

```sql
ALTER TABLE public.<table_name> DROP COLUMN is_deleted;
```

执行前再次检查目标表确实位于 `public` schema，且列存在。不要使用 `CASCADE` 掩盖未知的视图、函数或约束依赖；发现未知依赖时，应回到第 3.2-D 补齐定义。

**5.4 提交与维护**

```sql
COMMIT;
VACUUM (ANALYZE);
```

`VACUUM (ANALYZE)` 可在迁移成功后执行以更新统计信息和复用空间；它不保证立即缩小磁盘文件。若确实需要压缩文件大小，另行在维护窗口评估 `VACUUM FULL`，它会持有更强的表锁，不能与本次发布混用。

## 6. 建议的发布顺序

1. 在独立预演库恢复生产备份。
2. 在预演库运行第 3 节预检，生成并审阅 `020_remove_logical_delete.sql`。
3. 在预演库运行迁移，执行第 7 节所有验证；记录耗时、锁等待和删除行数。
4. 合并代码改造和迁移文件；完成完整构建、单元测试和集成测试。
5. 在生产窗口停止所有应用实例和异步任务，确认无活动写入。
6. 创建生产备份，并抽检备份可读。
7. 用 `psql -v ON_ERROR_STOP=1 -f 020_remove_logical_delete.sql` 执行迁移；任何错误立即停止，事务回滚后不启动新版本。
8. 启动新版本，先进行只读健康检查，再开放少量流量并完成冒烟测试。
9. 监控错误日志、数据库锁、连接数和关键接口 30 分钟后，再恢复正常流量。

## 7. 迁移后验证

```sql
-- A. 应为 0：库中不再存在 is_deleted 列
SELECT count(*) AS remaining_is_deleted_columns
FROM information_schema.columns
WHERE table_schema = 'public' AND column_name = 'is_deleted';

-- B. 应为 0：不再存在依赖该列的索引或约束
SELECT count(*) AS remaining_is_deleted_indexes
FROM pg_indexes
WHERE schemaname = 'public' AND indexdef ILIKE '%is_deleted%';

SELECT count(*) AS remaining_is_deleted_constraints
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
  AND pg_get_constraintdef(oid) ILIKE '%is_deleted%';

-- C. 检查第 5.2 中的关键索引是否存在
SELECT indexname
FROM pg_indexes
WHERE schemaname = 'public'
  AND indexname IN (
    'idx_kb_chunk_set_item_active',
    'uk_kb_chunk_set_source_config',
    'uk_kb_chunk_set_active_item',
    'uk_kb_chunk_set_index',
    'uk_kb_index_build_active',
    'uk_kb_index_build_inflight_target',
    'uk_kb_graph_release_item_build',
    'idx_kb_index_build_recovery',
    'uk_kb_graph_release_active'
  )
ORDER BY indexname;
```

应用冒烟清单：

- 管理端：平台、模型、MCP、工作流列表正常加载；创建、更新、停用正常。
- 永久删除：创建测试对象，调用删除接口后用 SQL 按 ID 查询，确认返回 0 行；刷新 API/页面不再显示。
- 停用：创建测试对象并停用，SQL 仍能查到该行，运行时可用列表不包含该对象。
- 运行时：启动时无 `column is_deleted does not exist`、`NoSuchMethodError getIsDeleted` 或初始化缓存残留错误。

代码验收命令：

```powershell
Get-ChildItem server,admin-web,user-web -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch '\\(target|node_modules|\.git)\\' } |
  Select-String -Pattern 'is_deleted|isDeleted|softDelete' -CaseSensitive:$false

Set-Location server
mvn test
```

第一个命令的业务代码输出必须为 0；如迁移说明文档本身包含关键词，应在检查命令中排除文档与迁移历史，或人工确认仅剩预期文本。

## 8. 失败处理与恢复

| 失败点 | 处理 |
|---|---|
| 预演/生产迁移在 `BEGIN` 到 `COMMIT` 之间失败 | 不提交事务；执行 `ROLLBACK`，排查后重新生成脚本。 |
| 迁移已经提交但新应用启动失败 | 保持应用下线，用迁移前备份恢复到新数据库或在隔离环境验证恢复后切换；不要尝试手工补列后继续。 |
| 索引/约束缺失 | 保持流量关闭，补充经审阅的 `CREATE INDEX` / `ALTER TABLE ADD CONSTRAINT`，再次运行第 7 节验证。 |
| 发现不可接受的数据丢失风险 | 停止发布；不执行生产迁移。逻辑删除历史只能从备份恢复。 |

**回滚事实**：此变更没有 SQL 级的“无损回滚”。因为已逻辑删除行会被真正 `DELETE`，恢复路径只能是迁移前的、已验证可还原的备份。

## 9. 执行记录模板

| 项目 | 填写内容 |
|---|---|
| 发布版本/提交 |  |
| 预演库名称与时间 |  |
| 预演迁移耗时 |  |
| 生产备份文件与校验结果 |  |
| 生产维护窗口 |  |
| 待删除行数汇总 |  |
| 实际执行人/复核人 |  |
| 迁移脚本校验和 |  |
| 迁移后 SQL 验证结果 |  |
| 冒烟测试结果 |  |
| 30 分钟监控结论 |  |

