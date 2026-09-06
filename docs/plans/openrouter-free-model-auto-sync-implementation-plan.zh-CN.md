# OpenRouter 免费模型自动同步直接实施方案

> 状态：可直接进入开发与上线执行<br>
> 决策：删除原有每 10 分钟主动测活，改为每天固定时刻同步 OpenRouter 免费模型<br>
> 默认执行时间：每天 04:00，时区 `Asia/Shanghai`<br>
> 上线方式：一次性直接切换，不设置观察期、Shadow、Dry Run 或两阶段开关<br>
> 更新日期：2026-08-22

## 1. 目标

本改造完成后，后端不再按 10 分钟周期向所有模型发送真实请求。系统每天固定执行一次 OpenRouter 模型同步，自动完成：

1. 获取当前 OpenRouter 账号实际可见的模型目录。
2. 识别仍为零价格的 `:free` 模型。
3. 根据 OpenRouter 端点延迟、吞吐量和可用率进行第一层筛选。
4. 从 ZhiMesh 部署服务器发起一次最小真实请求，验证模型确实可调用并测量真实响应时间。
5. 优先完成数据库中已有 OpenRouter 模型的验证；以前被禁用但本轮验证通过的模型重新启用。
6. 统计验证后仍可用的免费模型数量；只有数量少于 `min-active-models`（默认 10）时，才从目录中按速度排序补足短缺。
7. 将不再免费、已下线、不可调用或速度不达标的自动管理模型禁用。
8. 保留模型数据库记录和历史引用，不进行物理删除。
9. 记录每次同步、每个模型的判断依据和变更结果。

最终运行链路：

```text
每天 04:00 触发
  → 获取分布式锁
  → 校验 OpenRouter 平台和 API Key
  → 获取账号可见模型目录
  → 过滤 :free、零价格和受支持模态
  → 获取候选模型端点性能
  → 限速执行最小真实请求
  → 统计可用数量，不足 10 个时按最快端点补足短缺
  → 生成完整变更计划
  → 单事务写入模型、状态和审计记录
  → 事务提交后刷新运行时模型上下文
```

## 2. 非目标

本次不做以下事情：

- 不自动管理中转站、OpenAI、DeepSeek、DashScope、SiliconFlow、Ollama 等其他平台。
- 不导入 OpenRouter 的收费模型。
- 已经手工配置在 OpenRouter 平台上的收费模型默认纳入每日资格验证；目录确认其不免费时会被禁用，但不会被物理删除。需要长期保留的模型必须加入 `protected-models`。
- 不自动导入图像生成、语音、Embedding、Rerank 模型。
- 不物理删除 `adi_ai_model` 历史记录。
- 不保证免费模型具备生产 SLA；系统只保证每天重新校验和自动上下架。
- 不使用网页爬虫抓取 OpenRouter 模型页面。
- 不保留原来的“检查全部模型”主动测活入口。

第一版只管理 OpenRouter 的聊天模型：

- 纯文本输入、文本输出：映射为 ZhiMesh `text`。
- 文本和图片输入、文本输出：映射为 ZhiMesh `vision`。
- 包含当前后端不支持的必需输入/输出模态时不导入。

## 3. 当前代码问题与本次处理

### 3.1 每 10 分钟主动调用消耗免费额度

当前 `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/base/Jobs.java` 中的 `scheduledHealthCheck()` 每 10 分钟调用一次 `ModelHealthService.checkAll()`。

这意味着每个启用模型每天最多产生：

```text
24 × 60 ÷ 10 = 144 次主动探测
```

OpenRouter 官方当前说明，普通免费账号的免费模型请求通常合计为 50 次/天；累计购买至少 10 美元 credits 后提高到 1000 次/天。现有探测频率即使只有一个免费模型也可能耗尽基础额度。

处理：

- 删除 `Jobs.scheduledHealthCheck()`。
- 删除 `Jobs` 对 `ModelHealthService` 的注入。
- 删除 `ModelHealthService.checkAll()` 及其主动探测调用链。
- 删除 `AbstractLLMService.performHealthCheck()`。
- 删除管理端 `POST /admin/model/health/check` 和旧状态接口 `GET /admin/model/health`。
- 删除管理前端原“模型测活”按钮，替换成“立即同步 OpenRouter”。

### 3.2 当前连续失败计数无法可靠累积

当前健康缓存 2 分钟过期，但定时任务间隔 10 分钟，计划任务产生的失败计数会在下一次探测前过期，无法可靠达到连续失败阈值。

处理：

- OpenRouter 同步状态持久化到 PostgreSQL。
- 每天的目录判断、探测状态和禁用原因不再依赖 Guava 短缓存。
- `ModelHealthService` 仅保留真实用户调用产生的被动成功/失败记录；被动记录不额外发送请求，因此不消耗额度。

### 3.3 健康状态只以模型名称为 Key

当前 `ModelHealthService` 和 `LLMContext` 的健康判断只传递 `modelName`。当中转站与 OpenRouter 存在同名模型时，可能发生跨平台串状态。

处理：

- 被动运行时状态统一使用 `platform + "::" + modelName` 作为 Key。
- 新增接口统一接收 `platform` 和 `modelName`，或直接使用 `modelId`。
- OpenRouter 同步数据库唯一身份为 `(platform, model_name)`。
- 管理端状态映射使用 `modelId`，不再使用模型名称作为 JSON Map Key。

## 4. 上线硬性前提

上线前必须满足：

1. 数据库中存在名为 `OpenRouter` 的模型平台（或将 `ZHIMESH_OPENROUTER_PLATFORM_NAME` 设置为你数据库中的精确名称）。
2. 平台 `base_url` 为 `https://openrouter.ai/api/v1`，结尾是否带 `/` 均可，由客户端规范化。
3. 平台 `api_key` 已配置且未过期。
4. 平台 `is_openai_api_compatible = true`。
5. 生产实例可以访问 `https://openrouter.ai`。
6. Redis 可用，用于防止多实例重复同步。
7. 建议 OpenRouter 账号已累计购买至少 10 美元 credits，使免费模型额度提高到 1000 请求/天；否则每日真实探测会明显占用 50 次/天的基础额度。
8. 执行迁移前已备份 `adi_ai_model` 和 `adi_model_platform`。

平台预检 SQL：

```sql
SELECT id, name, title, base_url,
       api_key <> '' AS has_api_key,
       is_openai_api_compatible
FROM adi_model_platform
WHERE name = 'OpenRouter';
```

模型重复预检 SQL：

```sql
SELECT platform, name, count(*)
FROM adi_ai_model
GROUP BY platform, name
HAVING count(*) > 1;
```

如果第二条 SQL 返回记录，必须先合并重复数据及其引用，再创建复合唯一索引。

## 5. OpenRouter 官方接口

### 5.1 账号可见模型目录

首选接口：

```http
GET https://openrouter.ai/api/v1/models/user
Authorization: Bearer <OPENROUTER_API_KEY>
```

必须使用账号可见目录，而不是公共网页列表。该接口会考虑账号的 provider preferences、隐私设置和 guardrails；目录中不存在的模型不能假设当前 API Key 可以调用。

按 OpenRouter 当前官方契约，`/models/user` 一次返回完整的 `data` 数组，没有文档化的 `total_count` 或分页参数。实现必须校验响应体大小、`data` 为非空数组、每项存在模型 ID 且 ID 不重复；任一条件不满足都视为目录整体失败。不得依赖官方未声明的分页字段，否则正常响应会被误判为目录损坏。

如果 `/models/user` 返回 401、403、404、5xx、超时、空数据或无法解析，本次运行整体失败，禁止修改任何模型启用状态。不得自动回退到网页爬取。

### 5.2 单模型端点信息

优先使用模型目录返回的 `links.details`，例如：

```http
GET https://openrouter.ai/api/v1/models/{author}/{slug}/endpoints
Authorization: Bearer <OPENROUTER_API_KEY>
```

端点信息用于读取：

- `latency_last_30m.p50`
- `throughput_last_30m.p50`
- `uptime_last_1d`
- 端点级 `pricing`
- 支持参数
- 上下文长度

只允许请求 `https://openrouter.ai` 下以 `/api/v1/models/` 开头的详情路径，禁止无条件请求服务端返回的任意 URL，避免 SSRF。

### 5.3 当前 API Key 信息

每次同步开始时调用：

```http
GET https://openrouter.ai/api/v1/key
Authorization: Bearer <OPENROUTER_API_KEY>
```

用于提前识别 API Key 失效、禁用或额度配置异常。不得在日志或同步状态表中保存完整 API Key，只允许保存脱敏 label。

### 5.4 最小真实探测

探测接口：

```http
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <OPENROUTER_API_KEY>
Content-Type: application/json
Accept: text/event-stream
```

请求示例：

```json
{
  "model": "provider/model:free",
  "messages": [
    {
      "role": "user",
      "content": "Reply with OK only."
    }
  ],
  "max_tokens": 8,
  "temperature": 0,
  "stream": true
}
```

参数发送规则：

- 模型声明支持 `max_tokens` 时才发送 `max_tokens`。
- 模型声明支持 `temperature` 时才发送 `temperature`。
- 不发送 tools、plugins、web search、response repair 等可能产生额外费用或改变路由的能力。
- 使用原始 SSE 流记录从请求发出到第一个 `content` 或 `reasoning` delta 的时间作为 TTFT。
- 收到合法结束事件后记录总耗时。
- HTTP 200 但没有任何合法 choice/delta/finish 信息视为失败。

## 6. 免费资格判定

一个模型只有同时满足以下条件，才进入性能筛选：

1. `id` 以 `:free` 结尾。
2. `pricing.prompt = 0`。
3. `pricing.completion = 0`。
4. `pricing` 中其他所有已出现且可解析的计费项均为 0。
5. `expiration_date` 为空或晚于当前时间。
6. 输出模态包含且只使用后端支持的文本输出。
7. 输入模态至少包含文本，且可以映射为 `text` 或 `vision`。
8. `context_length >= min-context-tokens`。
9. 至少有一个端点，且端点级价格也满足零价格要求。

价格必须使用 `BigDecimal` 精确比较，不允许转成 `double` 后判断，也不允许把缺失、空字符串、`NaN` 或解析异常当成 0。

判断规则：

```text
prompt 和 completion 必须存在且精确等于 0
其他已出现的价格字段必须可解析且精确等于 0
任何已出现字段大于 0或无法解析 → 不免费
```

只根据 `is_free` 旧数据库字段判断是禁止的；数据库字段是上一次同步结果，OpenRouter 目录和端点价格才是本次事实来源。

## 7. 模型能力映射

| OpenRouter 元数据 | ZhiMesh 字段 | 映射规则 |
| --- | --- | --- |
| `id` | `name` | 原样保存，包括 `:free` |
| `name` | `title` | 最长 255 字符，超出截断 |
| `description` | `remark` | 最长 1000 字符，超出截断 |
| `context_length` | `max_input_tokens` | 与 top provider context 取较小值 |
| 仅文本输入 | `type` | `text` |
| 输入包含图片 | `type` | `vision` |
| 输入模态 | `input_types` | `text` 或 `text,image` |
| 文本输出 | `response_format_types` | 至少 `text` |
| 支持 `response_format` 或 `structured_outputs` | `response_format_types` | 增加 `json_object` |
| 价格全部为 0 | `is_free` | `true` |
| 通过全部筛选和真实探测 | `is_enable` | `true` |

第一版保守处理：

- `is_support_web_search = false`，避免自动启用额外计费能力。
- `is_thinking_closable = false`，除非后续能从官方字段明确证明可以关闭。
- `is_reasoner` 只在官方 `supported_parameters` 明确包含 `reasoning` 时设置为 `true`，不根据名称包含 `R1`、`Thinking` 等字符串猜测。
- 原始目录和端点元数据保存在同步状态表，不把所有第三方字段塞进运行时 `properties`。

## 8. 性能筛选规则

默认阈值全部配置化：

| 指标 | 默认值 | 作用 |
| --- | ---: | --- |
| 最小上下文 | 8192 tokens | 排除上下文过小模型 |
| OpenRouter 端点 p50 延迟 | 不高于 4000 ms | 第一层速度筛选 |
| OpenRouter 端点 p50 吞吐量 | 不低于 12 tokens/s | 排除生成过慢端点 |
| OpenRouter 最近 1 天 uptime | 不低于 98% | 排除高故障端点 |
| ZhiMesh 实测 TTFT | 不高于 6000 ms | 验证部署网络的真实响应速度 |
| ZhiMesh 实测总耗时 | 不高于 15000 ms | 限制最小回答的完整等待时间 |
| HTTP 连接超时 | 5000 ms | 快速识别连接失败 |
| 单请求硬超时 | 20000 ms | 防止任务线程长期占用 |

模型有多个免费端点时：

1. 先丢弃非零价格端点。
2. 丢弃 uptime、延迟或吞吐量缺失的端点。
3. 按 p50 延迟升序排列。
4. 使用满足全部门槛的最佳端点数据作为模型目录性能。
5. 即使目录性能通过，也必须完成一次从 ZhiMesh 服务器发起的真实探测。

目录指标单位必须在 DTO 转换层统一为毫秒。OpenRouter 示例中的 latency 是秒值，禁止不转换就与毫秒阈值比较。

## 9. 探测限速与额度保护

每日同步仍会产生真实请求，因此必须内置限速：

- 并发数固定为 1。
- 两次探测开始时间默认间隔 4000 ms，即最多约 15 次/分钟。
- 每次运行硬上限默认 100 个真实探测。
- 同步时间选在 04:00，降低与用户请求争抢 OpenRouter 每分钟额度的概率。
- 收到 429 后立即停止本轮剩余真实探测。
- 429 不增加模型失败次数，不禁用任何未完成探测的模型。
- 超出本轮探测上限的候选模型不新增；已有模型保持同步前状态。

探测顺序：

1. 当前已启用且自动管理的 OpenRouter 模型。
2. 当前已禁用但重新满足目录条件的自动管理模型。
3. 新发现模型，按端点 p50 延迟升序处理。

这样即使发生额度不足，也优先保证当前模型池的判断完整。

## 10. 错误分类和上下架规则

| 情况 | 本次动作 | 是否修改失败计数 |
| --- | --- | ---: |
| 模型不再出现在完整账号目录 | 立即禁用 | 否 |
| 不再是 `:free` 或任一价格大于 0 | 立即禁用 | 否 |
| 模型已过期 | 立即禁用 | 否 |
| 模态不再受支持 | 立即禁用 | 否 |
| 没有零价格端点 | 立即禁用 | 否 |
| 官方端点延迟、吞吐量或 uptime 不达标 | 立即禁用 | 否 |
| 真实调用 404、模型不存在、无可用 provider | 立即禁用 | 是 |
| 真实调用超时、5xx、无合法响应 | 本次禁用，次日成功可自动恢复 | 是 |
| 真实 TTFT 或总耗时超限 | 本次禁用，次日重新评估 | 是 |
| 真实调用成功且速度达标 | 新增、启用或保持启用 | 清零 |
| 401/403 | 整次同步失败，不修改任何模型 | 否 |
| 429 | 停止后续探测；已完成结果可提交，未探测模型保持原状态 | 否 |
| 目录接口超时、5xx、空数据、JSON 不合法 | 整次同步失败，不修改任何模型 | 否 |
| Redis 锁未获取 | 标记 `SKIPPED_LOCKED`，不执行 | 否 |

“立即禁用”指将 `adi_ai_model.is_enable` 更新为 `false` 并在事务提交后从运行时上下文移除，不是物理删除。

当目录整体不可信时必须 fail-safe：保持现有模型状态。只有“成功获取且通过完整结构校验的账号目录”才能作为模型消失或价格变化的证据。

## 11. 自动管理边界

### 11.1 可以被自动修改的模型

自动同步只查询配置的 OpenRouter 平台名，但对该平台的模型采用“默认接管、显式保护”的边界：

- 新发现且通过目录资格判断的 `:free` 模型自动写入 `is_managed = true`。
- 已存在的 OpenRouter 模型默认视为自动管理对象，即使旧的 `is_free` 标记错误或为空；这样每天会重新验证它是否仍免费、可调用且速度达标。
- `protected-models` 配置列表中的模型，或已有状态记录 `is_managed = false` 的模型，不由同步器上下架或改写运行配置。

### 11.2 首次运行时接管现有模型

首次运行时，对尚无同步状态记录的现有 `OpenRouter` 模型执行：

- 配置在 `protected-models` 中：创建状态记录并设 `is_managed = false`，保留现状。
- 其他现有 OpenRouter 模型：创建状态记录并设 `is_managed = true`，立即按完整目录、端点和真实探测结果判断；若确认收费、下线、不可调用或过慢，则只设置 `is_enable = false`。
- 只有新发现的免费模型才允许新增数据库行；收费模型不会因目录发现而新增。

### 11.3 永不修改的对象

- `platform != OpenRouter` 的所有模型（这里的比较值以 `ZHIMESH_OPENROUTER_PLATFORM_NAME` 为准）。
- `is_managed = false` 的 OpenRouter 模型。
- 配置在 `protected-models` 中的模型。
- 中转站模型，即使模型名称与 OpenRouter 相同。

手动将一个自动管理模型重新启用，只影响当前数据库状态；下一次每日同步仍会重新验证，验证失败时会再次禁用。若要永久不受自动同步影响，请加入 `ZHIMESH_OPENROUTER_PROTECTED_MODELS`。

## 12. 数据库迁移

当前最新迁移为 `034`，本改造新增：

```text
server/db_migration/035_openrouter_free_model_sync.sql
```

### 12.1 扩大模型名称长度并增加唯一身份

OpenRouter 模型 ID 包含 provider、slug 和 variant，现有 `varchar(45)` 过小。迁移应执行：

```sql
ALTER TABLE adi_ai_model
    ALTER COLUMN name TYPE varchar(255),
    ALTER COLUMN title TYPE varchar(255);

CREATE UNIQUE INDEX uk_ai_model_platform_name
    ON adi_ai_model (platform, name);
```

创建唯一索引前必须执行第 4 节的重复预检。

### 12.2 新增模型同步状态表

目标结构：

```sql
CREATE TABLE adi_openrouter_model_state
(
    id                         bigserial primary key,
    platform                   varchar(45)   not null,
    model_name                 varchar(255)  not null,
    model_id                   bigint,
    is_managed                 boolean       not null default true,
    lifecycle_status           varchar(32)   not null default 'DISCOVERED',
    catalog_status             varchar(32)   not null default 'UNKNOWN',
    probe_status               varchar(32)   not null default 'NOT_RUN',
    last_decision              varchar(64)   not null default '',
    disable_reason             varchar(1000) not null default '',
    catalog_latency_p50_ms     integer,
    catalog_throughput_p50     numeric(12, 2),
    catalog_uptime_1d          numeric(7, 3),
    actual_ttft_ms             integer,
    actual_total_latency_ms    integer,
    consecutive_failures       integer       not null default 0,
    last_error_code            varchar(64)   not null default '',
    last_error_message         varchar(1000) not null default '',
    last_seen_at               timestamp,
    last_probe_at              timestamp,
    last_success_at            timestamp,
    last_disabled_at           timestamp,
    raw_metadata               jsonb         not null default '{}',
    create_time                timestamp     not null default CURRENT_TIMESTAMP,
    update_time                timestamp     not null default CURRENT_TIMESTAMP,
    CONSTRAINT uk_openrouter_model_state UNIQUE (platform, model_name),
    CONSTRAINT fk_openrouter_model_state_model
        FOREIGN KEY (model_id) REFERENCES adi_ai_model (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_openrouter_model_state_model_id
    ON adi_openrouter_model_state (model_id)
    WHERE model_id IS NOT NULL;
```

状态表保存所有发现记录。未通过性能筛选的新候选可以只有 `model_name` 而没有 `model_id`；只有通过真实探测时才创建 `adi_ai_model`。

### 12.3 新增同步运行审计表

```sql
CREATE TABLE adi_openrouter_sync_run
(
    id                    bigserial primary key,
    uuid                  varchar(32)   not null unique,
    trigger_type          varchar(24)   not null,
    status                varchar(32)   not null,
    catalog_count         integer       not null default 0,
    free_count            integer       not null default 0,
    eligible_count        integer       not null default 0,
    probed_count          integer       not null default 0,
    added_count           integer       not null default 0,
    updated_count         integer       not null default 0,
    enabled_count         integer       not null default 0,
    disabled_count        integer       not null default 0,
    skipped_count         integer       not null default 0,
    started_at            timestamp     not null,
    completed_at          timestamp,
    error_code            varchar(64)   not null default '',
    error_message         varchar(1000) not null default '',
    summary               jsonb         not null default '{}',
    create_time           timestamp     not null default CURRENT_TIMESTAMP,
    update_time           timestamp     not null default CURRENT_TIMESTAMP
);

CREATE INDEX idx_openrouter_sync_run_started_at
    ON adi_openrouter_sync_run (started_at DESC);
```

`summary` 必须保存本次每个变更的 `modelId/modelName/action/reason/before/after`，但不能保存 API Key、请求 Authorization header 或完整用户业务内容。

### 12.4 触发器、全量脚本和校验脚本

结构变化必须同步更新：

- `server/db_migration/all_ddl.sql`
- `server/db_migration/all_dml.sql`
- `server/db_migration/verify_schema.sql`
- `server/db_migration/README.md`
- `server/db_migration/README.zh-CN.md`
- `docs/database/schema.zh-CN.md`

两张新表都应复用现有 `update_modified_column()` 触发器维护 `update_time`。

全新安装的 `all_dml.sql` 应创建不带 API Key 的 OpenRouter 平台基础记录：

```sql
INSERT INTO adi_model_platform
    (name, title, remark, base_url, is_openai_api_compatible)
VALUES
    ('OpenRouter', 'OpenRouter', 'OpenRouter OpenAI-compatible API',
     'https://openrouter.ai/api/v1', true);
```

已有数据库的迁移不得覆盖现有 OpenRouter `api_key`、代理开关或管理员自定义标题。

## 13. 配置项

在 `ZhiMeshProperties` 中新增 `OpenRouterSync`，并在 `application.yml` 中加入：

```yaml
zhimesh:
  openrouter-sync:
    enabled: ${ZHIMESH_OPENROUTER_SYNC_ENABLED:true}
    platform-name: ${ZHIMESH_OPENROUTER_PLATFORM_NAME:OpenRouter}
    cron: "${ZHIMESH_OPENROUTER_SYNC_CRON:0 0 4 * * *}"
    zone: ${ZHIMESH_OPENROUTER_SYNC_ZONE:Asia/Shanghai}
    health-check-enabled: ${ZHIMESH_OPENROUTER_HEALTH_CHECK_ENABLED:true}
    health-check-cron: "${ZHIMESH_OPENROUTER_HEALTH_CHECK_CRON:0 0 0,9,14,19 * * *}"
    health-check-min-active-models: ${ZHIMESH_OPENROUTER_HEALTH_CHECK_MIN_ACTIVE_MODELS:5}
    health-check-max-probes-per-run: ${ZHIMESH_OPENROUTER_HEALTH_CHECK_MAX_PROBES_PER_RUN:20}
    active-run-timeout-minutes: ${ZHIMESH_OPENROUTER_ACTIVE_RUN_TIMEOUT_MINUTES:35}
    rate-limit-backoff-minutes: ${ZHIMESH_OPENROUTER_RATE_LIMIT_BACKOFF_MINUTES:10}
    min-context-tokens: ${ZHIMESH_OPENROUTER_MIN_CONTEXT_TOKENS:8192}
    max-catalog-p50-latency-ms: ${ZHIMESH_OPENROUTER_MAX_CATALOG_P50_LATENCY_MS:4000}
    min-catalog-throughput-tps: ${ZHIMESH_OPENROUTER_MIN_THROUGHPUT_TPS:12}
    min-catalog-uptime-1d: ${ZHIMESH_OPENROUTER_MIN_UPTIME_1D:98}
    max-probe-ttft-ms: ${ZHIMESH_OPENROUTER_MAX_PROBE_TTFT_MS:6000}
    max-probe-total-ms: ${ZHIMESH_OPENROUTER_MAX_PROBE_TOTAL_MS:15000}
    connect-timeout-ms: ${ZHIMESH_OPENROUTER_CONNECT_TIMEOUT_MS:5000}
    request-timeout-ms: ${ZHIMESH_OPENROUTER_REQUEST_TIMEOUT_MS:20000}
    probe-interval-ms: ${ZHIMESH_OPENROUTER_PROBE_INTERVAL_MS:4000}
    max-probes-per-run: ${ZHIMESH_OPENROUTER_MAX_PROBES_PER_RUN:100}
    catch-up-after-hours: ${ZHIMESH_OPENROUTER_CATCH_UP_AFTER_HOURS:26}
    min-active-models: ${ZHIMESH_OPENROUTER_MIN_ACTIVE_MODELS:10}
    protected-models: ${ZHIMESH_OPENROUTER_PROTECTED_MODELS:}
```

配置校验：

- 所有时间和数量必须大于 0。
- `request-timeout-ms` 必须大于 `max-probe-total-ms`。
- `probe-interval-ms` 不得小于 3000，避免轻易突破免费模型每分钟额度。
- uptime 范围必须是 `[0, 100]`。
- 配置非法时应用启动失败，不得悄悄使用 0 或无限超时。
- `protected-models` 按英文逗号分隔并去空格。
- `min-active-models` 是每日验证后希望保持的最少可用免费模型数，默认值为 10；同步只补足短缺，不会为了达到目标一次性导入全部合格目录模型。
- `health-check-cron` 默认在 00:00、09:00、14:00、19:00 执行轻量测活，覆盖每日 04:00 全量同步后的连续 5 小时检查点。
- 轻量测活只探测当前已经启用的 OpenRouter 免费文本模型；`health-check-min-active-models` 默认是 5，低于该数量时才排队一次完整目录发现/同步。
- 轻量测活最多探测 `health-check-max-probes-per-run` 个模型；网络探测全部完成后才一次性提交启用状态，未完成探测的模型保持原状态。
- `active-run-timeout-minutes` 必须大于 30；超过该时间仍处于 `QUEUED/RUNNING` 的审计记录会自动标记为 `FAILED/STALE_RUN_RECOVERED`，避免服务器异常退出后永久阻塞任务。
- OpenRouter 返回 429 且库存低于安全线时，不立即重复请求目录；默认等待 `rate-limit-backoff-minutes=10` 后合并触发一次恢复任务，若届时仍有任务运行则继续延后。同一批恢复最多追加一次 429 重试，第二次仍受限时等待下一固定调度，禁止无限循环消耗额度。

不提供 `dry-run` 配置。本方案上线即执行真实同步，但保留 `enabled` 作为紧急停止开关。

## 14. 定时任务与补偿执行

新增 `OpenRouterModelSyncJob`：

```java
@Scheduled(
    cron = "${zhimesh.openrouter-sync.cron:0 0 4 * * *}",
    zone = "${zhimesh.openrouter-sync.zone:Asia/Shanghai}"
)
public void scheduledSync() {
    openRouterModelSyncService.runScheduled();
}
```

要求：

- 类使用 `@ConditionalOnProperty(prefix = "zhimesh.openrouter-sync", name = "enabled", havingValue = "true")`。
- 同步服务使用 Redis 锁 `zhimesh:job:openrouter-model-sync`。
- 锁租约固定 1800 秒；单次任务总时长硬限制为 25 分钟，确保不会在任务仍运行时锁先过期。
- 获取锁失败只写 `SKIPPED_LOCKED` 审计，不重复执行。
- 定时任务与管理端手动触发调用同一业务入口，不能复制两份同步逻辑。

同时新增轻量测活任务：

- 默认时间为 `00:00`、`09:00`、`14:00`、`19:00`（`Asia/Shanghai`）。
- 测活期间不修改数据库、不刷新运行时上下文，用户继续使用现有模型。
- 批量测活完成后，如果仍有至少 5 个可用模型，则在一个短事务中应用结果并刷新运行时上下文。
- 如果应用失败结果会导致可用库存少于 5 个，则暂缓下架，先触发完整目录恢复；完整任务在一个事务中同时加入替代模型、恢复旧模型并下架失败模型。
- 如果可用免费文本模型少于 5 个，异步触发完整 OpenRouter 目录同步；完整同步期间保留当前仍可用的运行时模型。
- 429 只停止当前批次后续探测，已完成结果保留，未探测模型不下架；恢复目录请求在退避时间后执行，不做紧邻的重复请求。

为了处理服务器在 04:00 停机的情况，在 `ApplicationReadyEvent` 后检查最近一次 `SUCCESS`：

- 单实例启动时先回收启动时间以前遗留的 `QUEUED/RUNNING` 任务；日常调度还会回收超过 35 分钟的异常活动任务。
- 直接查询当前启用的 OpenRouter 免费文本模型；少于 5 个时无条件触发 `STARTUP_RECOVERY`，不受最近任务时间影响。
- 最近成功任务只统计真正加载目录的任务；`HEALTH_CHECK` 不能替代完整目录同步。
- 最近成功时间超过 `catch-up-after-hours`，启动 60 秒后补执行一次。
- 最近已成功同步则不补执行。
- 补执行同样获取分布式锁。
- 补执行类型记录为 `STARTUP_CATCH_UP`。

## 15. 事务和运行时刷新

网络请求与数据库事务必须分离：

```text
网络阶段：目录、端点、探测、生成内存中的 SyncPlan
数据库阶段：一个短事务应用完整 SyncPlan
提交之后：刷新运行时模型上下文
```

禁止在持有数据库事务期间等待 OpenRouter 响应。

数据库阶段必须在一个 `@Transactional` 方法中：

1. 锁定本次涉及的状态行。
2. Upsert `adi_openrouter_model_state`。
3. 对通过探测的新模型插入 `adi_ai_model`。
4. 对已有自动管理模型更新元数据和 `is_enable`。
5. 写入同步运行统计与变更摘要。
6. 提交事务。

事务成功后只调用一次 `AiModelService.init()`，不能每增加或禁用一个模型就重建一次全部模型上下文。事务失败时不刷新上下文。

用户端已打开的页面也必须收敛到最新模型列表：前端在页面可见时每 60 秒轻量轮询
`GET /model/llms`，并在浏览器标签页重新变为可见时立即刷新。刷新结果通过现有
`appStore.setLLMs()` 更新当前选择；如果原选择已被下架则自动切换到新的第一个可用文本模型。

同步必须幂等：相同目录和探测结果连续执行两次，第二次 `added/updated/enabled/disabled` 均应为 0，除审计时间外不能产生业务差异。

## 16. 后端代码改动清单

### 16.1 删除主动测活

修改：

- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/base/Jobs.java`
  - 删除 `ModelHealthService` import、字段和 `scheduledHealthCheck()`。
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/service/ModelHealthService.java`
  - 删除 `check()`、`checkAll()`、`shouldProbe()` 和主动请求相关代码。
  - 删除只服务于旧管理端探测页面的 `getAllStatuses()` 和 `defaultHealthy()`。
  - 保留被动 `recordFailure/recordSuccess/isHealthy`。
  - 缓存不存在时，只要对应运行时服务仍存在就默认健康；真实调用连续失败后才短暂熔断。
  - 方法签名改为携带 `platform` 或 `modelId`。
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/languagemodel/AbstractLLMService.java`
  - 删除 `performHealthCheck()`。
  - 被动记录调用改为传递平台和模型名。
- `server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/helper/LLMContext.java`
  - 健康查询改成 `(platform, modelName)`。
- `server/zhimesh-admin/src/main/java/com/pppp/zhimesh/admin/controller/AdminModelController.java`
  - 删除 `/health/check` 和 `/health` 两个旧接口。
  - 管理端改读新的 OpenRouter 同步状态接口；非 OpenRouter 模型只展示持久化启用状态。

### 16.2 新增 OpenRouter 集成

建议新增包：

```text
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/openrouter/
  OpenRouterClient.java
  OpenRouterCatalogService.java
  OpenRouterProbeService.java
  OpenRouterEligibilityService.java
  OpenRouterModelSyncService.java
  OpenRouterModelSyncPersistenceService.java
  OpenRouterModelSyncJob.java
  data/
    OpenRouterModel.java
    OpenRouterEndpoint.java
    OpenRouterProbeResult.java
    OpenRouterSyncDecision.java
    OpenRouterSyncPlan.java
    OpenRouterSyncResult.java
```

持久化对象：

```text
server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/entity/
  OpenRouterModelState.java
  OpenRouterSyncRun.java

server/zhimesh-common/src/main/java/com/pppp/zhimesh/common/mapper/
  OpenRouterModelStateMapper.java
  OpenRouterSyncRunMapper.java
```

客户端要求：

- Java 17。
- 复用项目代理配置；只有 OpenRouter 平台显式启用代理时才走代理。
- 统一规范化 base URL，避免双斜杠。
- 每个响应设置大小上限，目录和端点 JSON 单响应默认不超过 10 MB。
- 不自动跟随到非 `openrouter.ai` 域名。
- 日志禁止输出 Authorization header 和 API Key。
- HTTP 错误转换为结构化错误码，不使用字符串 contains 作为主要分类方法。

### 16.3 配置和服务方法

修改：

- `ZhiMeshProperties.java`：新增 `OpenRouterSync` 配置对象。
- `application.yml`、`application-prod.yml`、`application-dev.yml.example`：补充环境变量。
- `AiModelService.java`：新增按 `(platform, name)` 查询和批量同步需要的方法。
- 不得在同步逻辑中调用现有逐条 `addOne()/disable()/enable()` 循环，因为它们会反复刷新运行时上下文。

## 17. 管理端 API

新增控制器：

```text
server/zhimesh-admin/src/main/java/com/pppp/zhimesh/admin/controller/AdminOpenRouterModelSyncController.java
```

所有接口只允许管理员调用。

### 17.1 手动立即同步

```http
POST /admin/openrouter-model-sync/run
```

行为：

- 创建 `QUEUED` 运行记录。
- 提交到 `backgroundExecutor`。
- 立即返回 run UUID，不占用 HTTP 请求线程等待完整同步。
- 如果已有任务运行，返回已有运行 ID 或明确的 `ALREADY_RUNNING`，不能排队重复执行。

响应核心字段：

```json
{
  "runId": "32-char-uuid",
  "status": "QUEUED"
}
```

### 17.2 查询运行结果

```http
GET /admin/openrouter-model-sync/runs/{runId}
GET /admin/openrouter-model-sync/latest
```

返回：状态、开始结束时间、目录数、免费数、探测数、新增数、启用数、禁用数、跳过数和脱敏错误信息。

### 17.3 查询模型同步状态

```http
GET /admin/openrouter-model-sync/models
```

按 `modelId` 返回：

- 是否自动管理。
- 生命周期状态。
- 最近目录状态。
- 最近探测状态。
- 官方 p50 延迟、吞吐量、uptime。
- 实测 TTFT、总耗时。
- 最近禁用原因和时间。

## 18. 管理前端改动

修改：

- `admin-web/src/api/aiModel.ts`
  - 删除 `triggerHealthCheck()`。
  - 不再调用旧的全局 health API。
- 新增 `admin-web/src/api/openRouterModelSync.ts`。
- `admin-web/src/views/ai-model/index.vue`
  - 将“模型测活”按钮替换为“立即同步 OpenRouter”。
  - 点击前明确提示本操作会产生免费模型请求。
  - 提交后轮询 run 状态，完成后刷新模型表。
- `admin-web/src/views/ai-model/columns.ts`
  - OpenRouter 模型显示同步状态和最后禁用原因。
  - 非 OpenRouter 模型只显示启用/禁用，不伪装成刚刚通过健康探测。
- 中英文 locale 增加同步、受限、过慢、不再免费、调用失败、最后同步时间等文案。

状态展示建议：

| 状态 | 文案 |
| --- | --- |
| `ENABLED` | 已自动启用 |
| `CATALOG_NOT_FREE` | 已不再免费 |
| `CATALOG_TOO_SLOW` | 官方性能不达标 |
| `PROBE_TOO_SLOW` | 本机实测过慢 |
| `PROBE_FAILED` | 调用失败 |
| `RATE_LIMITED` | 本轮受限，状态未变 |
| `PROTECTED` | 手工保护，不自动修改 |
| `NOT_MANAGED` | 非自动管理模型 |

## 19. 核心同步伪代码

```java
SyncResult run(triggerType) {
    Run run = createRun(triggerType);
    if (!tryAcquireRedisLock()) {
        return finish(run, SKIPPED_LOCKED);
    }

    try {
        Platform platform = requireOpenRouterPlatform();
        validateCurrentKey(platform);

        Catalog catalog = client.listUserModels(platform);
        catalog.requireCompleteAndValid();

        List<Candidate> candidates = catalog.models().stream()
                .map(eligibilityService::checkFreeAndModality)
                .filter(Candidate::catalogEligible)
                .map(candidate -> catalogService.attachBestEndpoint(candidate, platform))
                .toList();

        List<ProbeTarget> targets = prioritizeExistingThenNew(candidates);
        Map<ModelIdentity, ProbeResult> probes = probeWithRateLimit(targets);

        SyncPlan plan = buildPlan(
                catalog,
                candidates,
                probes,
                currentManagedModels,
                protectedModels
        );

        persistenceService.applyInOneTransaction(run, plan);
        aiModelService.init();
        return finish(run, plan.wasRateLimited() ? PARTIAL_RATE_LIMIT : SUCCESS);
    } catch (CatalogOrCredentialException error) {
        // 目录或平台级错误：不改模型
        return finishWithoutModelMutation(run, FAILED, error);
    } finally {
        releaseRedisLock();
    }
}
```

必须区分：

- 目录事实：模型是否存在、是否免费、是否过期。
- 目录性能：OpenRouter 最近窗口统计。
- 本机探测：从当前部署网络实测。
- 平台级错误：API Key、OpenRouter 整体不可达、目录损坏。

## 20. 测试要求

### 20.1 单元测试

至少覆盖：

- `:free` 且所有价格为 0时通过。
- prompt 或 completion 非 0时拒绝。
- 可选价格字段非 0时拒绝。
- 价格缺失、空值、非法数字时拒绝。
- text 和 vision 模态映射正确。
- 不支持的输出模态不导入。
- latency 秒到毫秒转换正确。
- 多端点能选择满足门槛的最快零价格端点。
- endpoint 指标缺失时不误判为优秀。
- 401/403、429、404、5xx、timeout 分类正确。
- 429 不禁用模型。
- 整体目录失败生成空变更计划。
- 非 OpenRouter 平台永不进入计划。
- protected model 永不修改。
- 相同 `(platform, name)` 不重复插入。
- 同一计划重复执行保持幂等。
- 状态 Key 使用平台和模型名，不跨平台串状态。

### 20.2 HTTP 集成测试

使用本地测试 HTTP Server 模拟：

- 正常目录、端点和 SSE 响应。
- SSE 首块延迟和完整响应延迟。
- 截断 SSE。
- 超大 JSON 响应。
- 重定向到非 OpenRouter 域名。
- 429 后停止后续探测。
- API Key 失效时零模型变更。

测试中不得调用真实 OpenRouter，避免 CI 消耗额度和引入不稳定网络依赖。

### 20.3 数据库与事务测试

- 迁移 035 可以在 034 后成功执行。
- `verify_schema.sql` 所有缺失计数为 0。
- 同步中途数据库异常时全部业务变更回滚。
- 事务失败时不刷新 `LLMContext`。
- 新模型写入后能由 `OpenAiCompatibleLLMService` 构建。
- 禁用后用户模型列表不再返回该模型。
- 历史聊天记录仍能保留原 model ID。

### 20.4 构建命令

```powershell
cd server
mvn -pl zhimesh-common,zhimesh-admin,zhimesh-bootstrap -am test

cd ..\admin-web
pnpm run build
```

## 21. 一次性直接上线步骤

本节是正式执行顺序，不是两阶段上线。

### 步骤 1：生产前备份和快照

```sql
CREATE TABLE backup_adi_ai_model_before_openrouter_sync_20260822 AS
SELECT * FROM adi_ai_model;

CREATE TABLE backup_adi_model_platform_before_openrouter_sync_20260822 AS
SELECT * FROM adi_model_platform;
```

备份表名应替换为真实上线日期。确认备份后再继续。

### 步骤 2：预检平台和重复模型

执行第 4 节 SQL。要求：

- OpenRouter 平台恰好一条。
- API Key 已配置。
- 复合模型身份无重复。
- 中转站使用独立 platform name。

如有不希望自动接管的 OpenRouter 免费模型，将其加入 `ZHIMESH_OPENROUTER_PROTECTED_MODELS`。

### 步骤 3：执行迁移 035

```powershell
cd server\db_migration
psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <database> -f 035_openrouter_free_model_sync.sql
psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d <database> -f verify_schema.sql
```

任何 SQL 失败立即停止，不部署新应用。

### 步骤 4：部署后端和管理前端

部署时明确设置：

```text
ZHIMESH_OPENROUTER_SYNC_ENABLED=true
ZHIMESH_OPENROUTER_SYNC_CRON=0 0 4 * * *
ZHIMESH_OPENROUTER_SYNC_ZONE=Asia/Shanghai
```

确认启动日志中不再出现：

```text
Scheduled health check starting
Starting health check for ... models
```

### 步骤 5：部署后立即执行首次同步

通过管理端“立即同步 OpenRouter”或调用：

```http
POST /admin/openrouter-model-sync/run
```

首次同步直接执行真实新增、启用和禁用，不进行 Dry Run。

轮询运行结果直到 `SUCCESS` 或 `PARTIAL_RATE_LIMIT`。如为 `FAILED`，按照错误分类处理；目录级失败时模型状态应完全不变。

### 步骤 6：数据库验收

```sql
SELECT status, catalog_count, free_count, eligible_count, probed_count,
       added_count, enabled_count, disabled_count, error_code,
       started_at, completed_at
FROM adi_openrouter_sync_run
ORDER BY id DESC
LIMIT 1;

SELECT m.id, m.platform, m.name, m.type, m.is_free, m.is_enable,
       s.lifecycle_status, s.catalog_status, s.probe_status,
       s.actual_ttft_ms, s.actual_total_latency_ms, s.disable_reason
FROM adi_openrouter_model_state s
LEFT JOIN adi_ai_model m ON m.id = s.model_id
ORDER BY m.is_enable DESC NULLS LAST, s.model_name;
```

验证非 OpenRouter 平台未被修改：

```sql
SELECT count(*)
FROM adi_ai_model current_model
JOIN backup_adi_ai_model_before_openrouter_sync_20260822 old_model
  ON old_model.id = current_model.id
WHERE current_model.platform <> 'OpenRouter'
  AND (current_model.is_enable, current_model.is_free, current_model.name,
       current_model.title, current_model.type)
      IS DISTINCT FROM
      (old_model.is_enable, old_model.is_free, old_model.name,
       old_model.title, old_model.type);
```

结果必须为 0。

### 步骤 7：运行验收

- 管理端可以看到同步成功摘要。
- 新增模型出现在模型列表。
- 被禁用模型不出现在用户可选模型列表。
- 随机选择至少一个新模型完成一次真实聊天。
- 中转站模型仍可正常选择和调用。
- 管理端不再存在“检查全部模型”按钮。
- 次日 04:00 产生新的 `SCHEDULED` 成功记录。

## 22. 验收标准

全部满足才算完成：

1. 连续运行 30 分钟日志中没有 10 分钟主动测活。
2. 每天 04:00 执行一次完整同步，00:00、09:00、14:00、19:00 执行轻量测活；多实例中每个批次只有一个实例获得锁。
3. OpenRouter 目录失败不会禁用现有模型。
4. 价格变为非零的自动管理模型在同一次成功同步中被禁用。
5. 429 不会导致模型批量下架。
6. 新模型只有在目录性能和本机实测同时达标后才启用。
7. 不达标模型保留数据库和审计记录，但用户不可选。
8. 同一同步任务重复执行不会创建重复模型。
9. 中转站及其他平台模型的关键字段零变更。
10. API Key 不出现在日志、异常响应和同步 `summary` 中。
11. 后端测试和管理前端构建全部通过。
12. `verify_schema.sql` 缺失计数全部为 0。
13. 每日 04:00 后的轻量测活不会在探测过程中清空用户当前可用模型。
14. 轻量测活发现可用免费文本模型少于 5 个时，会自动触发一次完整目录恢复同步。

## 23. 紧急停止与回滚

### 23.1 只停止后续自动同步

设置并重启：

```text
ZHIMESH_OPENROUTER_SYNC_ENABLED=false
```

该操作不会恢复旧的 10 分钟主动测活，也不会修改当前模型状态。

### 23.2 恢复同步前模型启用状态

发生错误批量上下架时，先停止同步，再根据备份恢复已有模型的启用和免费标记：

```sql
UPDATE adi_ai_model current_model
SET is_enable = old_model.is_enable,
    is_free = old_model.is_free
FROM backup_adi_ai_model_before_openrouter_sync_20260822 old_model
WHERE old_model.id = current_model.id;
```

对于本次新插入且需要撤销的模型，不进行物理删除，统一设置 `is_enable = false`，以免已经产生的聊天记录失去 model ID：

```sql
UPDATE adi_ai_model
SET is_enable = false
WHERE platform = 'OpenRouter'
  AND id NOT IN (
      SELECT id FROM backup_adi_ai_model_before_openrouter_sync_20260822
  );
```

恢复后重启应用或调用内部模型重新初始化逻辑，确保运行时上下文与数据库一致。

### 23.3 代码回滚

代码回滚时可以暂时保留迁移 035 新增的表和扩大的 varchar 字段，它们不会影响旧代码。不要恢复 10 分钟测活，除非明确接受其额度消耗。

## 24. 实现完成清单

- [x] 删除 10 分钟主动测活任务。
- [x] 删除管理端全量主动测活接口和按钮。
- [x] 保留并修复零额外请求的被动运行时失败记录。
- [x] 新增迁移 035、全量 DDL、校验 SQL 和数据库说明。
- [x] 新增 OpenRouter 目录、端点和真实探测客户端。
- [x] 实现免费价格、模态和性能判定。
- [x] 实现限速、429 停止和平台级错误保护。
- [x] 实现自动管理边界和 protected models。
- [x] 实现单事务 Upsert 和提交后一次性刷新模型上下文。
- [x] 实现每天 04:00 定时任务、Redis 锁和启动补偿。
- [x] 实现同步运行和模型状态审计。
- [x] 实现管理端手动同步、结果查询和状态展示。
- [x] 完成判定、错误分类、限流、所有权、启动补偿、幂等持久化单元测试，以及后端全模块和管理前端生产构建。
- [ ] 按第 21 节完成直接上线和首次真实同步。
- [ ] 验证次日 04:00 自动执行成功。

## 25. 官方参考

- [OpenRouter：List all models and their properties](https://openrouter.ai/docs/api/api-reference/models/get-models)
- [OpenRouter：List models filtered for the current user](https://openrouter.ai/docs/api/api-reference/models/list-models-user)
- [OpenRouter：List all endpoints for a model](https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model)
- [OpenRouter：Free Variant](https://openrouter.ai/docs/guides/routing/model-variants/free)
- [OpenRouter：Get current API key](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key)
- [OpenRouter：API Credit & Rate Limits](https://openrouter.ai/docs/api_reference/limits)
- [OpenRouter FAQ](https://openrouter.ai/docs/faq)
