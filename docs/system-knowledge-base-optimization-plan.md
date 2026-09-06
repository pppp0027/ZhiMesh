# 系统知识库与系统角色优化方案

## 1. 文档目的

本文档说明如何在当前项目中增加“系统知识库”能力，并将它安全地绑定到管理端创建的系统角色上。

目标是解决以下问题：

1. 管理员可以上传和维护专业资料。
2. 系统角色可以使用这些资料进行 RAG 检索。
3. 用户不需要把系统知识库复制到自己的账号下。
4. 系统知识库不会因为设置为公共知识库而暴露在用户的知识库列表中。
5. 用户仍然可以为自己的自定义角色绑定个人知识库或公共知识库。
6. 系统角色的知识库可以由管理员集中维护，更新一次即可对所有用户生效。

本文档是实施设计和改造清单，不会自动执行数据库迁移，也不会代替上线前的备份和验收。

## 1.1 方案审查结论

对照当前项目的实体、接口和前端页面逐项检查后，原方案的总体方向可行，但其中“新增独立管理 Controller、立即引入规范化关联表、单独新建一套管理页面”属于完整架构方案，不是当前项目必须一次完成的最小改动。

本次审查后的执行原则是：

1. **新增字段和表，不删除旧字段。** 现有用户知识库、角色和公共知识库默认不改变。
2. **保留旧 `kbTitle` 兼容分支。** 只有迁移完成、验证通过的系统角色才切换到系统知识库引用。
3. **第一阶段复用现有 `/admin/kb`、`/knowledge-base/uploadDocs` 和知识库管理页面。** 暂不新增独立 Controller 和独立菜单。
4. **第一阶段使用 `system_kb_ids` 字段复用项目已有的逗号分隔 ID 模式。** 规范化的 `adi_character_preset_kb` 关系表保留为第二阶段优化，不作为首批上线阻塞项。
5. **系统知识库只对聊天检索链路授权。** 不能简单把 `is_system` 加入普通用户知识库列表，否则会造成资料暴露。
6. **所有旧数据保持可回退。** 迁移脚本只 `ADD COLUMN`、`CREATE TABLE` 和新增索引，不批量改写用户角色的 `kb_ids`。

下文第 14 节是与当前项目最匹配的“最小改动执行版本”；前面的关系表方案作为长期演进方案保留。

---

## 2. 当前项目的实际逻辑

### 2.1 管理端的“知识库名称”不是知识库选择器

管理端角色预设页面目前使用的是 `kbTitle` 字段：

- 前端字段：
  `admin-web/src/views/conversation/preset-conv/PresetConv.vue`
- 数据字段：
  `adi_character_preset.kb_title`
- 后端保存：
  `CharacterPresetService.addOne()` 和 `CharacterPresetService.edit()`

当前保存的只是一个字符串名称，并没有保存知识库 ID，也没有上传文件的逻辑。

数据库迁移文件对该字段的定义也是“用户选择预设后自动创建知识库的名称，留空则不创建”。因此，它不是“绑定现有知识库”，也不是“管理员上传资料”。

### 2.2 用户选择预设时会创建一个新的空知识库

用户点击使用系统角色后，当前后端流程位于：

`server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/CharacterService.java`

逻辑如下：

1. 查询 `adi_character_preset`。
2. 如果 `kb_title` 不为空，构造 `KbEditReq`。
3. 调用 `knowledgeBaseService.saveOrUpdate()` 创建知识库。
4. `saveOrUpdate()` 使用当前登录用户作为 `owner_id`、`owner_uuid` 和 `owner_name`。
5. 将新知识库 ID 写入用户角色的 `adi_character.kb_ids`。

结果是：

- 管理员填写的知识库名称只会变成一个新知识库的标题。
- 新知识库最初没有任何文档。
- 每个用户使用一次预设，都会得到自己的一份空知识库。
- 管理员上传的知识库不会自动与系统角色关联。

### 2.3 当前用户知识库选择逻辑

用户端 `ConvKnowledgeSelector.vue` 会调用：

`GET /knowledge-base/mine/search?includeOthersPublic=true`

后端 `KnowledgeBaseService.searchMine()` 对普通用户的查询范围是：

- 用户自己的知识库；或
- 其他用户设置为公开的知识库。

角色绑定知识库时，`CharacterService.filterEnableKb()` 也只允许：

- 当前用户拥有的知识库；或
- `is_public = true` 的知识库。

这解释了用户的担心：

- 如果管理员知识库保持私有，普通用户无法绑定和使用。
- 如果管理员将知识库设置为公开，用户可以在公共知识库列表中看到它。
- 如果管理员直接作为普通用户上传，所有系统资料都会挂在管理员账号下面。

### 2.4 当前数据模型

当前主要字段关系如下：

```text
adi_character_preset
  ├─ kb_title       仅保存一个名称字符串
  ├─ mcp_ids        保存推荐 MCP ID 字符串
  └─ is_system      标识系统角色

adi_character
  └─ kb_ids         保存角色绑定的知识库 ID 字符串

adi_knowledge_base
  ├─ owner_id       知识库所有者
  ├─ owner_uuid
  ├─ owner_name
  └─ is_public      是否公开

adi_character_preset_rel
  └─ 用户使用某个系统角色后生成的用户角色关系
```

当前没有“系统角色—系统知识库”的真实关联表。

---

## 3. 推荐的目标架构

### 3.1 知识库分为两种作用域

#### 用户知识库（USER）

- 所有者是普通用户。
- 用户可以上传、编辑、删除。
- 默认私有。
- 可以由用户主动设置为公开。
- 可绑定到用户自定义角色。

#### 系统知识库（SYSTEM）

- 由管理员创建、上传、索引和维护。
- 不依赖管理员个人账号的普通知识库列表。
- 不需要设置为公开。
- 用户不能编辑、删除、上传或下载原始文件。
- 只能通过绑定了该知识库的系统角色使用。
- 一个系统知识库可以被多个系统角色和多个用户共享。

### 3.2 推荐的访问关系

```text
管理员
  │
  ├─ 创建系统知识库
  ├─ 上传文件
  ├─ 执行索引
  └─ 将系统知识库绑定到系统角色
          │
          ▼
用户选择系统角色
          │
          ▼
用户角色引用系统知识库 ID
          │
          ▼
聊天服务进行只读 RAG 检索
```

系统知识库不应该通过 `is_public = true` 来实现共享。公共知识库和系统知识库是两种不同的权限语义：

- 公共知识库：用户可以发现、选择和管理自己的绑定关系。
- 系统知识库：用户只能通过系统角色间接使用。

### 3.3 关键原则

1. **系统角色绑定知识库 ID，不绑定名称。** 名称会变化，ID 才是稳定关系。
2. **系统知识库只保存一份。** 不要在用户选择角色时复制文件和向量。
3. **用户角色保存引用，不获得所有权。** 用户角色删除不应删除系统知识库。
4. **系统知识库不是公共知识库。** 不能通过公开开关绕过权限设计。
5. **系统知识库的管理接口和用户知识库接口分离。** 管理端使用管理员专用接口更清晰、更安全。
6. **用户只能看到能力标签和必要的知识库名称。** 不返回文件列表、原文、分段和索引详情。

---

## 4. 数据库改造方案

建议新增迁移文件：

`server/db_migration/019_system_knowledge_base.sql`

### 4.1 给知识库增加系统作用域字段

推荐增加以下字段：

```sql
ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS is_system boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_enabled boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN adi_knowledge_base.is_system IS
    '系统知识库：由管理员维护，只能通过系统角色授权给用户使用';

COMMENT ON COLUMN adi_knowledge_base.is_enabled IS
    '系统知识库是否允许继续被系统角色检索';

CREATE INDEX IF NOT EXISTS idx_kb_system_scope
    ON adi_knowledge_base (is_system, is_enabled, is_deleted);
```

说明：

- `owner_id` 和 `owner_name` 仍然保留，用于记录最后的管理者和审计信息。
- `is_system = true` 后，访问权限不能再由 `owner_id` 或 `is_public` 单独决定。
- 所有现有知识库默认 `is_system = false`，不会影响现有用户数据。

### 4.2 新增系统角色与系统知识库关联表

不建议把多个知识库 ID 继续塞进字符串字段。建议新增关系表：

```sql
CREATE TABLE IF NOT EXISTS adi_character_preset_kb
(
    id                  bigserial primary key,
    uuid                varchar(32) NOT NULL DEFAULT '',
    preset_id           bigint NOT NULL,
    preset_uuid         varchar(32) NOT NULL,
    knowledge_base_id   bigint NOT NULL,
    knowledge_base_uuid varchar(32) NOT NULL,
    sort_order          integer NOT NULL DEFAULT 0,
    create_time         timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted          boolean NOT NULL DEFAULT false
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_character_preset_kb_active
    ON adi_character_preset_kb (preset_id, knowledge_base_id)
    WHERE is_deleted = false;

CREATE INDEX IF NOT EXISTS idx_character_preset_kb_preset
    ON adi_character_preset_kb (preset_id, is_deleted);

CREATE INDEX IF NOT EXISTS idx_character_preset_kb_kb
    ON adi_character_preset_kb (knowledge_base_id, is_deleted);
```

### 4.3 是否需要给 `adi_character` 增加字段

第一阶段可以继续复用现有的 `adi_character.kb_ids`：

- 系统角色创建出的用户角色保存系统知识库 ID。
- 用户自定义角色保存个人/公共知识库 ID。

但必须在服务层区分“为什么允许这个 ID”：

- 自有/公共知识库：按照现有用户权限判断。
- 系统知识库：只有当用户角色来自对应系统预设，并且预设仍然绑定该系统知识库时才允许。

第二阶段如果需要更清晰的审计，可以新增 `source_preset_id` 到 `adi_character`，但不是第一阶段的必需项，因为现有 `adi_character_preset_rel` 已经能表达用户角色来源。

### 4.4 旧字段 `kb_title` 的处理

不要立即删除 `adi_character_preset.kb_title`，建议分三个阶段：

1. 第一阶段保留字段，停止新逻辑使用。
2. 第二阶段将已有系统角色的 `kb_title` 作为迁移提示，创建对应的空系统知识库或由管理员重新选择实际知识库。
3. 所有角色完成迁移并验证后，再将字段标记为 deprecated，后续版本再删除。

旧用户已经生成的个人空知识库不能直接批量删除，应先统计其是否有用户上传文件，再决定是否归档。

---

## 5. 后端改造清单

### 5.1 新增实体和 Mapper

新增：

```text
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/entity/CharacterPresetKnowledgeBase.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/mapper/CharacterPresetKnowledgeBaseMapper.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/CharacterPresetKnowledgeBaseService.java
```

实体字段对应 `adi_character_preset_kb`。

`CharacterPreset` 增加返回用字段，例如：

```java
private List<Long> systemKbIds;
private List<SystemKnowledgeBaseSummary> systemKnowledgeBases;
```

管理员返回完整的摘要信息，用户只返回：

- ID（内部使用）；
- 展示名称；
- 是否可用；
- 只读标识。

不返回文件、分段、原文和向量信息。

### 5.2 新增系统知识库服务方法

在 `KnowledgeBaseService` 中新增或拆分以下方法：

```java
KnowledgeBase createSystemKnowledgeBase(SystemKnowledgeBaseAddReq req);
Page<KbInfoResp> searchSystemKnowledgeBases(...);
void assertSystemKnowledgeBaseAdmin(Long kbId);
void assertSystemKnowledgeBaseReadable(User user, Character character, Long kbId);
void assertSystemKnowledgeBaseWritable(User user, Long kbId);
```

权限规则：

| 操作 | 管理员 | 普通用户 |
|---|---:|---:|
| 创建系统知识库 | 允许 | 禁止 |
| 上传系统知识库 | 允许 | 禁止 |
| 编辑系统知识库 | 允许 | 禁止 |
| 删除/停用系统知识库 | 允许 | 禁止 |
| 在系统角色中使用 | 允许 | 仅绑定角色允许 |
| 在个人知识库列表中看到 | 可见 | 不显示 |
| 查看原始文件和分段 | 可见 | 禁止 |

### 5.3 新增管理员专用接口

建议新增：

```text
server/zhimesh-admin/src/main/java/com/pppp/zhimesh/admin/controller/AdminSystemKnowledgeBaseController.java
```

接口建议：

```text
POST /admin/system-knowledge-base/search
POST /admin/system-knowledge-base/add
POST /admin/system-knowledge-base/edit/{uuid}
POST /admin/system-knowledge-base/upload/{uuid}
POST /admin/system-knowledge-base/index/{uuid}
POST /admin/system-knowledge-base/disable/{uuid}
POST /admin/system-knowledge-base/delete/{uuid}
GET  /admin/system-knowledge-base/{uuid}/summary
```

实现上可以复用 `KnowledgeBaseService` 的上传和索引能力，但入口必须显式校验管理员权限，并且只能操作 `is_system = true` 的记录。

这样做比让管理端直接调用普通用户的 `/knowledge-base/saveOrUpdate` 更容易维护，也避免管理员账号的普通知识库列表越来越混乱。

### 5.4 修改系统角色 DTO

修改：

```text
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/CharacterPresetAddReq.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/CharacterPresetEditReq.java
```

增加：

```java
private List<Long> systemKbIds;
```

保留 `kbTitle` 读取兼容，但新建和编辑时不再将它作为绑定依据。

`CharacterPresetService` 的新增和编辑流程改为：

1. 校验角色标题、描述和系统提示词。
2. 校验所有 `systemKbIds` 都是启用中的系统知识库。
3. 保存角色预设。
4. 使用事务重建 `adi_character_preset_kb` 关联关系。
5. 清理不再绑定的关联关系时使用软删除。

### 5.5 修改用户选择系统角色的逻辑

修改：

```text
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/CharacterService.java
```

`addByPresetCharacter()` 不再执行：

```java
new KbEditReq();
knowledgeBaseService.saveOrUpdate(kbEditReq);
```

而是：

1. 查询系统预设关联的系统知识库 ID。
2. 过滤已删除或已停用的系统知识库。
3. 将这些 ID 写入用户角色的 `kb_ids`。
4. 保留 `adi_character_preset_rel` 关系。
5. 不创建新知识库，不复制文件，不复制向量。

该方法应保持事务性，避免角色创建成功但关联关系没有保存。

### 5.6 防止普通用户伪造系统知识库 ID

不能只修改 `filterEnableKb()` 让所有系统知识库都可读，否则普通用户可以直接在自定义角色接口中传入任意系统知识库 ID。

建议拆成三个方法：

```java
filterUserOwnedOrPublicKbIds(user, ids)
filterPresetSystemKbIds(user, character, ids)
filterReadableKbIds(user, character, ids)
```

判断系统知识库是否可读时必须同时满足：

1. 知识库 `is_system = true`。
2. 知识库 `is_enabled = true`。
3. 当前角色存在有效的 `adi_character_preset_rel`。
4. 该预设与该知识库存在有效的 `adi_character_preset_kb` 关系。

这样用户不能通过修改请求把任意系统知识库挂到自己的角色上。

### 5.7 聊天检索逻辑

修改 `CharacterChatService` 以及 `CharacterService.filterEnableKb()` 的调用链：

- 用户自己的知识库：照旧检索。
- 公共知识库：照旧检索。
- 系统知识库：只有通过系统预设关系授权的才检索。
- 被管理员停用的系统知识库：从检索列表中自动排除。

系统知识库的向量只需要索引一次，所有用户的角色通过相同的 `kb_uuid` 检索，不产生重复向量。

### 5.8 统一补充知识库读取权限

在启用系统知识库前，应统一检查以下接口：

```text
GET  /knowledge-base/info/{uuid}
GET  /knowledge-base-item/info/{uuid}
GET  /knowledge-base-item/search
GET  /knowledge-base-item/{itemUuid}/chunk-sets
GET  /knowledge-base-item/{itemUuid}/chunk-sets/{chunkSetUuid}/chunks
POST /knowledge-base/qa/add/{kbUuid}
GET  /knowledge-base/qa/search
```

规则是：

- 管理员可以查看系统知识库完整内容。
- 普通用户不能通过这些接口浏览系统知识库原文。
- 普通用户只能在服务器内部聊天检索链路使用系统知识库。

现有项目已经有 `KnowledgeBaseService.checkReadPrivilege()`，但必须保证所有详情、文件、分段、问答接口都统一调用，不能只保护上传接口。

---

## 6. 管理端前端改造

### 6.1 新增系统知识库管理页面

建议新增：

```text
admin-web/src/views/knowledge-base/system/SystemKnowledgeBase.vue
admin-web/src/api/systemKnowledgeBase.ts
```

页面功能：

1. 系统知识库列表。
2. 新建系统知识库。
3. 上传 PDF、Word、Markdown、TXT 等文件。
4. 查看文件数量、向量数量和索引状态。
5. 重新索引。
6. 启用/停用。
7. 删除或归档。
8. 显示“系统共享、用户只读”标签。

新建知识库时建议保留现有知识库的切分、Embedding、召回和重排配置，但界面增加明确提示：

> 这是系统级知识库，只能由管理员维护。用户不会在个人知识库列表中看到它，但绑定系统角色后会参与模型检索。

### 6.2 修改管理端角色创建弹窗

修改：

```text
admin-web/src/views/conversation/preset-conv/PresetConv.vue
```

将现有：

```text
知识库名称
```

替换为：

```text
关联专业知识库（系统，可选）
```

控件改为 `n-select multiple`：

- 数据源：`GET /admin/system-knowledge-base/search`
- 只展示 `is_system = true && is_enabled = true`
- 支持搜索和多选
- 显示知识库名称和文档数量
- 支持跳转“系统知识库管理”

表单提示：

> 先在系统知识库管理中上传资料，再在这里绑定。系统知识库不会显示在用户的个人知识库列表中。

### 6.3 系统角色列表展示

角色表格的能力列可以展示：

```text
系统提示词
系统知识库 2
MCP 工具 1
```

不建议直接在表格中展示文件名或知识库内部内容。

### 6.4 系统角色删除和编辑规则

- `is_system = true` 的角色不能删除，只能编辑提示词、描述、知识库绑定和 MCP 推荐工具。
- 系统知识库即使从角色上解除绑定，也不能被角色编辑操作删除。
- 删除系统知识库前，后台必须检查仍然被哪些系统角色引用。
- 如果仍被引用，优先要求停用或解除绑定，不允许直接物理删除。

---

## 7. 用户端前端改造

### 7.1 系统角色卡片

用户端系统角色卡片只显示能力摘要：

```text
系统角色
专业知识库
MCP 工具
```

可以显示知识库的展示名称，但不显示：

- 文件列表；
- 文件上传按钮；
- 文档原文；
- 分段内容；
- Embedding 配置；
- 管理员账号信息。

### 7.2 系统角色详情

系统角色详情中建议使用只读标签：

```text
知识能力
法律法规知识库（系统只读）
```

不要复用用户端的可编辑知识库选择器直接展示系统知识库，否则用户会误认为它是自己的知识库。

### 7.3 自定义角色保持原功能

用户新建自定义角色时，继续使用原有的知识库选择器：

- 用户自己的知识库可以选择；
- 公开知识库可以选择；
- 系统知识库不出现在这个列表中；
- MCP 工具继续保持可选。

---

## 8. 数据迁移步骤

### 8.1 迁移前备份

迁移前必须备份：

```text
adi_character_preset
adi_character
adi_character_preset_rel
adi_knowledge_base
adi_knowledge_base_item
相关 embedding 表和文件存储
```

### 8.2 执行结构迁移

执行 `019_system_knowledge_base.sql`，只新增字段、索引和关联表，不修改现有用户知识库的归属。

### 8.3 处理现有 `kb_title`

现有系统角色中的 `kb_title` 只能作为迁移参考，不能直接认为它已经对应真实资料。

建议建立迁移清单：

| 旧角色 | 旧 kb_title | 新系统知识库 | 处理方式 |
|---|---|---|---|
| 法律顾问 | 法律法规知识库 | 待管理员选择 | 上传资料后绑定 |
| 财务助手 | 财务知识库 | 待管理员选择 | 上传资料后绑定 |
| 数据分析师 | 数据分析资料库 | 待管理员选择 | 上传资料后绑定 |

如果暂时需要保持角色可用，可以创建空的系统知识库作为占位，但必须在管理端显示“尚未上传资料”，避免误以为角色已有专业知识。

### 8.4 处理历史用户空知识库

不要立即删除旧逻辑创建的用户知识库：

1. 统计每个知识库的文件数量和向量数量。
2. 文件数为 0 且没有用户修改过的，可以标记为待清理。
3. 有用户上传资料的，保留为用户私有知识库。
4. 迁移完成并观察一段时间后，再清理没有内容的空记录。

---

## 9. 实施顺序

建议按以下顺序实施，降低一次性改动风险：

### 阶段一：数据库和后端基础能力

1. 新增 `is_system`、`is_enabled`。
2. 新增 `adi_character_preset_kb`。
3. 新增实体、Mapper、Service。
4. 新增管理员系统知识库接口。
5. 增加系统知识库权限判断。
6. 补齐所有知识库读取接口的权限检查。

### 阶段二：管理端

1. 增加系统知识库管理菜单。
2. 实现上传和索引。
3. 将角色表单的 `kbTitle` 改为系统知识库多选。
4. 保存和编辑角色时重建关联关系。
5. 增加系统角色能力展示。

### 阶段三：用户端和角色创建链路

1. `addByPresetCharacter()` 改为引用系统知识库，不再创建空知识库。
2. 用户端显示系统知识库只读标签。
3. 自定义角色继续使用个人/公共知识库。
4. 聊天检索增加系统知识库授权判断。

### 阶段四：迁移和清理

1. 为已有系统角色建立迁移清单。
2. 管理员上传真实专业资料。
3. 绑定系统角色。
4. 验证用户端检索结果。
5. 清理旧的空知识库。

---

## 10. 测试方案

### 10.1 管理员测试

- 可以创建系统知识库。
- 可以上传文档并完成索引。
- 可以停用系统知识库。
- 可以将系统知识库绑定到系统角色。
- 可以解除绑定，但不能因为解除绑定删除知识库。
- 删除仍被角色引用的系统知识库时得到明确提示。

### 10.2 普通用户测试

- 用户可以看到系统角色的能力摘要。
- 用户选择系统角色后，不会新建一份空知识库。
- 用户角色可以正常检索系统知识库。
- 用户的个人知识库列表中不出现系统知识库。
- 用户不能通过个人角色编辑接口传入任意系统知识库 ID。
- 用户不能查看系统知识库的文件列表、分段和原文详情。
- 用户仍然可以创建自定义角色并绑定自己的私有知识库。
- 用户仍然可以绑定公开知识库。

### 10.3 安全测试

- 普通用户访问系统知识库详情接口返回无权限或不存在。
- 普通用户访问系统知识库文件接口返回无权限或不存在。
- 修改 URL 中的知识库 UUID 不能绕过权限。
- 直接提交 `isSystem`、`ownerId` 等字段不能提权。
- 停用系统知识库后，所有绑定角色都不再检索它。

### 10.4 数据一致性测试

- 角色预设保存失败时，不留下半条关联记录。
- 删除角色预设不会删除系统知识库。
- 删除用户角色不会删除系统知识库。
- 同一角色重复绑定同一知识库不会产生重复关系。
- 同一系统知识库被多个角色使用时，只执行一份索引。

---

## 11. 回滚和兼容策略

建议增加配置开关：

```text
system-knowledge-base.enabled=false
```

上线初期可以关闭新链路而保留旧字段读取。

发生问题时：

1. 关闭系统知识库功能开关。
2. 系统角色仍可以使用系统提示词和 MCP。
3. 不删除系统知识库、文件或向量。
4. 修复后重新开启。

数据库迁移只增加字段和表，不删除旧列，保证可以回滚应用版本。

---

## 12. 推荐的最终用户体验

### 管理员操作

```text
系统知识库管理
  → 新建“法律法规知识库”
  → 上传法律法规文件
  → 完成索引
  → 创建/编辑“法律顾问”系统角色
  → 绑定“法律法规知识库”
```

### 用户操作

```text
选择“法律顾问”
  → 看到“系统知识库：法律法规知识库（只读）”
  → 直接提问
  → 后端使用系统知识库检索
```

用户不需要上传资料，也不会在自己的知识库列表中管理这套系统资料。

---

## 13. 结论

当前的 `知识库名称` 只是一个自动创建空知识库的旧字段，不能满足系统角色使用专业资料的需求。

推荐的改造方向是：

1. 增加系统知识库作用域。
2. 增加系统角色与系统知识库的多对多关系表。
3. 管理员在独立页面上传和维护系统资料。
4. 系统角色只保存知识库引用关系。
5. 用户使用系统角色时只读检索，不拥有知识库。
6. 用户个人知识库和系统知识库完全分开。
7. 通过统一的服务层和接口权限防止原文泄露和越权绑定。

这样既不会把所有系统资料堆积为管理员的个人知识库，也不需要将系统知识库公开给所有用户，同时能够让多个系统角色和多个用户复用同一套专业资料。

---

## 14. 审查后的最小改动执行版本

本节优先级高于前文的长期演进建议，适合直接在当前代码结构上实施。

### 14.1 变更范围总览

第一阶段只改动以下内容：

```text
数据库：
  adi_knowledge_base 增加 is_system、is_enabled
  adi_character_preset 增加 system_kb_ids

后端：
  KnowledgeBase / KbInfoResp / KbSearchReq / KbEditReq 增加字段
  KnowledgeBaseService 增加系统知识库校验
  CharacterPreset DTO 和 Service 保存系统知识库 ID
  CharacterService 的预设创建链路引用系统知识库
  聊天检索链路允许已授权系统知识库
  知识库详情和文件接口补齐读取权限

管理端：
  复用现有知识库页面创建、上传和索引系统知识库
  复用 /admin/kb/search 给角色表单提供系统知识库选项
  角色表单把“知识库名称”替换为“系统知识库（可选）”多选

用户端：
  保留现有个人/公共知识库选择器
  只增加系统知识库只读标签和能力摘要
  不新建系统知识库页面，不改用户上传流程
```

第一阶段明确不做：

- 不删除 `kb_title`。
- 不删除旧的用户知识库。
- 不批量修改已有用户角色的 `kb_ids`。
- 不新增独立的系统知识库 Controller。
- 不新增独立的系统知识库菜单。
- 不修改公共知识库的可见性规则。
- 不让普通用户直接浏览系统知识库文件。

### 14.2 第一阶段数据库迁移

新增迁移文件：

`server/db_migration/019_system_knowledge_base_minimal.sql`

建议内容：

```sql
BEGIN;

ALTER TABLE adi_knowledge_base
    ADD COLUMN IF NOT EXISTS is_system boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE adi_character_preset
    ADD COLUMN IF NOT EXISTS system_kb_ids varchar(1000) NOT NULL DEFAULT '';

COMMENT ON COLUMN adi_knowledge_base.is_system IS
    '系统知识库，由管理员维护，仅通过系统角色授权使用';

COMMENT ON COLUMN adi_knowledge_base.is_enabled IS
    '系统知识库是否继续参与角色检索';

COMMENT ON COLUMN adi_character_preset.system_kb_ids IS
    '绑定的系统知识库 ID，逗号分隔，第一阶段沿用项目现有 MCP ID 存储模式';

CREATE INDEX IF NOT EXISTS idx_kb_system_enabled_deleted
    ON adi_knowledge_base (is_system, is_enabled, is_deleted);

COMMIT;
```

同时必须更新：

```text
server/db_migration/all_ddl.sql
```

这样新环境初始化和已有环境迁移的结构保持一致。

迁移安全性：

- 新字段都有默认值。
- 现有知识库全部保持 `is_system = false`。
- 现有角色的 `system_kb_ids` 为空，不改变原来的 `kb_title` 兼容逻辑。
- 不写入、不删除 `adi_character.kb_ids`。

### 14.3 后端字段改动

修改以下类：

```text
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/entity/KnowledgeBase.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/KbInfoResp.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/KbEditReq.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/KbSearchReq.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/entity/CharacterPreset.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/CharacterPresetAddReq.java
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/dto/CharacterPresetEditReq.java
```

字段统一命名：

```java
// KnowledgeBase、KbInfoResp、KbEditReq、KbSearchReq
private Boolean isSystem;
private Boolean isEnabled;

// CharacterPreset、CharacterPresetAddReq、CharacterPresetEditReq
private String systemKbIds;
```

注意不要混淆两个 `isSystem`：

- `adi_knowledge_base.is_system` 表示知识库属于系统作用域。
- `adi_character_preset.is_system` 表示角色预设是受保护的内置角色，当前主要用于禁止删除。

新建的管理员角色即使 `CharacterPreset.isSystem=false`，也可以绑定系统知识库；角色是否可删除和知识库是否为系统作用域是两个独立问题。第一阶段不改变现有角色预设的删除语义。

第一阶段可以沿用项目现有 `mcpIds` 的逗号分隔处理方式，避免同时引入实体、Mapper、关联 Service 和复杂事务。

接口兼容要求：

- 新字段可选，旧客户端不传时按默认值处理。
- 旧接口仍接受 `kbTitle`。
- 新管理端保存角色时发送 `systemKbIds`，同时将 `kbTitle` 保持为空。
- 后端返回时可以同时返回 `kbTitle` 和 `systemKbIds`，不破坏旧用户端。

### 14.4 系统知识库创建和上传：复用现有能力

当前已经存在：

- 管理员查询：`POST /admin/kb/search`
- 管理员编辑/创建入口：`POST /admin/kb/edit`
- 用户知识库保存：`POST /knowledge-base/saveOrUpdate`
- 文件上传：`POST /knowledge-base/uploadDocs/{uuid}`
- 索引：`POST /knowledge-base/indexing/{uuid}`

第一阶段不新增新的 Controller，只做以下后端调整：

1. `AdminKbController.edit()` 继续复用 `KnowledgeBaseService.saveOrUpdate()`。
2. `KbEditReq.isSystem = true` 时，后端强制要求当前用户是管理员。
3. 系统知识库强制设置 `isPublic = false`。
4. 系统知识库的 `owner_id` 可以记录管理员账号，用于审计，但权限不再依赖 owner。
5. `/knowledge-base/uploadDocs/{uuid}` 和 `/knowledge-base/indexing/{uuid}` 继续复用现有写权限检查；系统知识库只允许管理员写入。
6. `AdminKbController.search()` 增加 `isSystem` 条件，角色表单只请求 `isSystem=true,isEnabled=true`。

这样不需要新建上传协议，也不需要复制文件存储和索引逻辑。

后端必须增加的保护逻辑：

```java
if (Boolean.TRUE.equals(req.getIsSystem())
        && !Boolean.TRUE.equals(ThreadContext.getCurrentUser().getIsAdmin())) {
    throw new BaseException(A_USER_NOT_AUTH);
}

if (Boolean.TRUE.equals(req.getIsSystem())) {
    knowledgeBase.setIsPublic(false);
}
```

同时，任何更新请求都不能让普通用户通过 JSON 直接提交 `isSystem=true` 完成提权。

### 14.5 保留 `kbTitle` 的兼容分支

修改 `CharacterService.addByPresetCharacter()` 时必须保留旧分支：

```text
如果 systemKbIds 非空：
    校验系统知识库均为启用状态
    将系统知识库 ID 写入用户角色
    不创建新知识库

否则如果 kbTitle 非空：
    继续执行旧的“按当前用户创建空知识库”逻辑

否则：
    创建纯提示词角色
```

这样可以保证：

- 新创建并绑定系统知识库的角色走新逻辑。
- 未迁移的旧角色继续可用。
- 用户已有角色不需要重新创建。
- 可以逐个迁移八个内置系统角色，而不是一次性改写全部数据。

迁移完成后，再将内置角色的 `systemKbIds` 写入并清空 `kbTitle`。清空前要完成用户端验证和数据库备份。

### 14.5.1 角色绑定变更的兼容处理

只在用户选择角色时把系统知识库 ID 写入 `adi_character.kb_ids`，会形成一次性快照：管理员后来给角色增加或解除知识库时，已经创建的用户角色不会自动同步。为避免这个隐患，建议采用“快照 + 运行时校验”的小改动方案：

1. `addByPresetCharacter()` 仍将当时的系统知识库 ID写入 `adi_character.kb_ids`，保证旧查询和前端展示兼容。
2. 聊天前通过现有 `adi_character_preset_rel` 找到用户角色对应的预设，读取该预设当前的 `system_kb_ids`。
3. 聊天检索时，以预设当前绑定结果为准，并与用户角色自己的知识库合并。
4. 管理员解除绑定后，旧用户角色不会继续检索被解除的系统知识库。
5. 管理员新增绑定后，已经使用该角色的用户无需重新添加角色即可获得新知识库能力。
6. 用户编辑角色时，后端保留当前预设授权的系统知识库，不允许通过 `kbIds` 请求将它们删除或替换成其他系统知识库。

这样不需要批量更新所有用户的 `adi_character` 记录，也不会因为角色配置变更而产生大量写操作。

### 14.6 系统知识库授权必须和普通知识库分开

不能简单地把 `filterEnableKb()` 改成“所有 `isSystem=true` 都允许”，否则用户可以直接调用 `/character/add` 或 `/character/edit/{uuid}` 传入任意系统知识库 ID。

第一阶段建议新增一个小范围的内部方法：

```java
private List<Long> filterPresetSystemKbIds(
        Long presetId,
        List<Long> systemKbIds
)
```

只允许：

1. 知识库是系统知识库。
2. 知识库没有删除。
3. 知识库处于启用状态。
4. 该系统知识库由管理员绑定在当前预设上。

用户普通的 `add()` 和 `edit()` 仍然只能绑定自己的或公开的知识库。

聊天检索链路可以按以下方式最小改动：

```text
CharacterChatService
  → 查询角色的 kb_ids
  → 普通用户/公共知识库按原规则过滤
  → 如果角色来自 CharacterPresetRel，额外验证 system_kb_ids
  → 只把验证通过的 KB 交给 CharacterChatHelper.retrieve()
```

不要修改 `CharacterChatHelper.retrieve()` 的向量检索接口，只在进入该方法之前完成授权过滤，这样可以最大程度减少 RAG 代码变化。

### 14.7 返回给用户端的知识库信息

现有 `setKbInfoToDto()` 对非本人、非公共知识库会将 `kbInfo` 置空并标记不可用。系统知识库采用私有模式后，如果直接沿用该逻辑，用户端会看到一个不可用的标签。

第一阶段建议只增加轻量字段：

```java
private Boolean isSystem;
private Boolean isReadOnly;
```

用户端只返回：

- 系统知识库名称；
- `isSystem=true`；
- `isReadOnly=true`。

不返回：

- 文件列表；
- 文件内容；
- 分段和向量；
- owner 信息；
- 管理配置。

系统知识库只读标签可以直接在已有的角色能力摘要中展示，不需要改造整个用户知识库选择器。

### 14.8 读取接口的最小安全修正

启用系统知识库之前，至少修改以下接口，使其调用统一的读取授权方法：

```text
KnowledgeBaseController.info()
KnowledgeBaseItemController.info()
KnowledgeBaseItemController.search()
KnowledgeBaseItemController.chunkSets()
KnowledgeBaseItemController.chunks()
KnowledgeBaseQAController.add()
KnowledgeBaseQAController.list()
```

判断规则：

- 管理员：可以查看系统知识库完整内容。
- 普通用户：不能通过普通知识库详情接口查看系统知识库。
- 普通用户：只有聊天服务在验证角色预设关系后，才可以进行服务器内部检索。

这部分是必要安全修复，不会影响普通用户自己的知识库和公共知识库。

### 14.9 管理端最小前端改动

#### A. 现有知识库页面

修改：

```text
admin-web/src/views/knowledge-base/index.vue
admin-web/src/views/knowledge-base/columns.ts
admin-web/src/api/knowledgeBase.ts
```

仅增加：

1. “新建”按钮，调用现有 `/admin/kb/edit`，不传 ID。
2. “知识库作用域”字段：普通知识库 / 系统知识库。
3. 系统知识库启用开关。
4. 系统知识库列表标签。
5. 文件上传和索引按钮，调用已有上传/索引接口。
6. 列表筛选 `isSystem`。

系统知识库表单默认值必须是：

```text
isSystem = false
isPublic = false
isEnabled = true
```

只有管理员主动选择“系统知识库”后才切换作用域，并且此时前端强制关闭“公开”。

#### B. 角色预设页面

修改：

```text
admin-web/src/views/conversation/preset-conv/PresetConv.vue
admin-web/src/views/conversation/preset-conv/columns.ts
admin-web/src/api/conversation.ts
```

只替换当前的 `kbTitle` 表单项：

```text
原：知识库名称（文本输入）
新：系统知识库（可选，多选下拉）
```

下拉数据调用已有的 `/admin/kb/search`，参数为 `isSystem=true,isEnabled=true`。

其他标题、描述、系统提示词、MCP 选择和角色类型保持不动。

提示文字建议：

> 先在知识库管理中创建并上传系统资料，再在这里绑定。用户只能通过该系统角色使用，不能管理原始资料。

#### C. 用户端

第一阶段只改：

```text
user-web/src/typings/chat.d.ts
user-web/src/views/chat/layout/sider/CreateConv.vue
user-web/src/views/chat/components/Header/EditConvDetail.vue
```

改动范围：

- 增加系统知识库只读类型字段。
- 系统角色能力摘要显示“系统知识库 X 个”。
- 系统知识库标签不显示删除按钮。
- `ConvKnowledgeSelector.vue` 保持原样，由后端保证系统知识库不出现在普通选择列表。
- 不新增用户端系统知识库菜单。

这比新增独立的用户端知识库页面和权限体系更安全，也更符合“系统资料由管理员统一维护”的目标。

### 14.10 第一阶段验收条件

只有以下条件全部通过，才能将内置角色从 `kbTitle` 切换到 `systemKbIds`：

1. 新环境 `all_ddl.sql` 可以创建完整结构。
2. 旧环境执行迁移后，原有用户登录、角色和知识库功能不变。
3. 普通用户看不到 `is_system=true,is_public=false` 的知识库列表。
4. 普通用户无法通过知识库详情、文件、分段和问答接口读取系统资料。
5. 用户选择系统角色不会创建新的空知识库。
6. 系统角色可以检索已绑定并启用的系统知识库。
7. 系统知识库停用后，角色不再检索该知识库。
8. 普通用户提交任意系统 KB ID 到自定义角色接口时会被过滤或拒绝。
9. 用户自有知识库和公共知识库行为不变。
10. 管理员可以上传、索引和维护系统知识库。

### 14.11 长期关系表的引入时机

`adi_character_preset_kb` 关系表仍然是更规范的长期方案，但建议满足以下条件后再引入：

- 系统知识库数量超过逗号字段可承载的范围；
- 需要记录绑定人、排序、版本和有效期；
- 需要按知识库反查所有角色；
- 需要细粒度授权或多租户隔离。

届时可以从 `system_kb_ids` 做一次性迁移，第一阶段不应为了规范化而同时重写角色创建、用户角色关系、检索和管理端页面。

---

## 15. 最终非破坏性检查表

| 检查项 | 结论 | 处理方式 |
|---|---|---|
| 新增系统字段 | 安全 | 默认值为 false/true，不影响旧记录 |
| 删除 `kbTitle` | 有风险 | 第一阶段保留并保留旧分支 |
| 立即迁移所有旧角色 | 有风险 | 改为逐个内置角色迁移 |
| 直接把系统 KB 设为公开 | 不推荐 | 使用 `isSystem` 和角色授权 |
| 允许所有用户绑定系统 KB | 有风险 | 只允许预设授权链路 |
| 新增独立管理 Controller | 可行但非必要 | 第一阶段复用现有 `/admin/kb` |
| 新增独立管理页面 | 可行但改动较大 | 第一阶段扩展现有知识库页面 |
| 新增关联表 | 长期推荐 | 第二阶段再做，第一阶段使用 `system_kb_ids` |
| 删除历史空知识库 | 有数据风险 | 先统计、归档，暂不删除 |
| 修改 RAG 向量代码 | 没必要 | 只修改检索前的授权过滤 |
| 用户端新增知识库管理入口 | 不必要 | 只增加只读标签和能力摘要 |
| 关闭/回滚 | 可行 | 通过功能开关和保留旧字段回退 |

按照这份审查后的执行版本实施，第一阶段只增加系统作用域和角色引用能力，不改变现有用户知识库、公共知识库和自定义角色的基本行为，风险明显低于一次性引入完整的新管理体系。
