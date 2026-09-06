# 服务器部署与接口性能测试

本文以 Linux 服务器、Docker Compose、外部 PostgreSQL 为基准。生产服务器的 IP、域名、数据库地址和账号不应写入仓库，应填写到服务器上的 `docker/.env`。

## 一、服务器上需要修改的配置

### 1. Docker 环境变量

```bash
cd docker
cp .env.example .env
chmod 600 .env
```

至少修改：

| 配置 | 服务器填写内容 |
| --- | --- |
| `ZHIMESH_DB_HOST` | PostgreSQL 的容器可达地址，不能随意写 `localhost` |
| `ZHIMESH_DB_USERNAME` / `ZHIMESH_DB_PASSWORD` | 应用数据库账号 |
| `ZHIMESH_ENCRYPT_AES_KEY` | 随机生成的 16 字符密钥，部署后不要随意更换 |
| `ZHIMESH_HOST` | 正式域名，不带协议 |
| `ZHIMESH_FRONTEND_URL` | 如 `https://ai.example.com` |
| `ZHIMESH_BACKEND_URL` | 如 `https://ai.example.com/api` |
| `HTTP_BIND_ADDRESS` | 直接对外提供 HTTP 时用 `0.0.0.0`；前面另有 HTTPS 反代时建议 `127.0.0.1` |
| `HTTP_PORT` | 网关监听端口，例如 `80` 或仅供外层反代使用的 `8080` |
| `JAVA_OPTS` | 按服务器内存调整 JVM 堆；必须给 Docker、系统和本地模型留出空间 |
| `ZHIMESH_EMBEDDING_MODEL` | 实际使用的嵌入模型；更换后必须重建向量数据 |
| `ZHIMESH_VECTOR_DATABASE` / `ZHIMESH_GRAPH_DATABASE` | `pgvector`/`neo4j` 与 `apache-age`/`neo4j` 的实际选择 |

SSE 性能脚本会为每个样本创建独立会话，因此 `ZHIMESH_CONVERSATION_ENABLED` 必须为 `true`。

如果 PostgreSQL 安装在同一台 Linux 主机上，可使用 `host.docker.internal`；Compose 已增加 `host-gateway` 映射。但 PostgreSQL 仍须监听 Docker 网桥可达地址，并在 `pg_hba.conf` 中仅授权所需网段和账号。若数据库在其它服务器，直接填写其内网 IP 或 DNS。

### 2. 数据库

1. 安装 PostgreSQL 16 和实际使用的扩展：pgvector，以及采用 AGE 图检索时的 Apache AGE。
2. 新库依次执行 `all_ddl.sql`、`all_dml.sql`、一个语言 DML。
3. 执行 `verify_schema.sql`，确认 `missing_required_table_count=0`。
4. 不要把数据库的 5432 端口直接暴露到公网。

详见[数据库初始化与迁移](../../server/db_migration/README.zh-CN.md)。

### 3. 域名、HTTPS 与防火墙

当前项目内网关提供 HTTP。正式公网部署建议由宿主机 Nginx、Caddy 或云负载均衡终止 HTTPS，再转发到 `127.0.0.1:8080`。只开放 80/443 和必要的 SSH 管理来源；Redis、PostgreSQL、API 容器端口均不应直接暴露公网。

若使用云负载均衡，还要确认它支持长连接，并将 SSE 空闲超时设置为高于单次回答最长时间。项目 Nginx 已关闭 `/api/` 的代理缓冲并设置 600 秒读写超时。

### 4. 业务数据

全新服务器不会拥有本地开发库中的 UUID。部署后必须重新完成：

1. 修改默认管理员密码并配置实际模型平台/API Key；
2. 创建专用普通测试用户；
3. 创建知识库、上传相同测试文档，等待向量化和图谱化全部完成；
4. 创建绑定该知识库的专用 Character；
5. 记录服务器数据库中的 Character UUID；旧机器上的 UUID 不能直接假定有效。

如果使用独立的 BGE Reranker、Ollama 或 Neo4j，它们在数据库/环境变量中的地址必须能从 `api` 容器访问。服务运行在宿主机时不要填写容器内的 `127.0.0.1`，应使用 `host.docker.internal` 或内网地址。

## 二、启动与部署检查

```bash
cd docker
docker compose config
docker compose up -d --build
docker compose ps
docker compose logs --tail=200 api
curl -f http://127.0.0.1/api/auth/search-engine/list
```

之后分别检查用户端 `/`、管理端 `/admin/` 和 API `/api/`。只有数据库迁移、应用启动、模型调用、知识库检索均正常后才能做性能测试。

## 三、服务器性能脚本配置

### 1. 准备 Python 和数据集

服务器需要 Python 3.10+ 和 `venv`。一键脚本只安装 `requirements-benchmark.txt` 中的轻量依赖，不需要安装完整 RAGAS 评分环境。把 121 题 JSONL 数据集复制到：

```text
ragas-evaluation/datasets/ragas_eval_dataset_final.jsonl
```

脚本不再依赖 Windows 绝对路径，也可以用 `RAGAS_DATASET` 或 `--dataset` 指向其它位置。

### 2. 设置测试凭据

```bash
cd ragas-evaluation
cp .env.example .env
chmod 600 .env
```

填写：

```dotenv
ZHIMESH_API_BASE_URL=http://127.0.0.1/api
RAGAS_DATASET=datasets/ragas_eval_dataset_final.jsonl

# 本次串行与并发矩阵统一使用此文件；每个账号对应一个独立Character：
ZHIMESH_BENCHMARK_ACCOUNTS_FILE=analysis/quantification/configs/concurrency-accounts.server.json
```

设置了多账号文件后，不需要再填写 `ZHIMESH_USER_TOKEN`、`ZHIMESH_USER_EMAIL`、`ZHIMESH_USER_PASSWORD` 或 `ZHIMESH_BENCHMARK_CHARACTER_UUID`；串行基线自动使用账号文件中的第一个账号。

不要使用管理员账号执行 SSE 压测，也不要提交 `.env` 或真实的 `concurrency-accounts.server.json`。仓库只提交不含密码的 `.example.json` 模板。

如果测试账号因连续密码错误进入验证码状态，自动登录会失败；应先解除该账号的登录限制，再执行预检，不能在正式轮次中临时换账号。

### 3. 先执行预检

```bash
bash analysis/quantification/run_server_benchmark.sh check
```

预检会逐个验证10个账号的登录、Character UUID、网关 API 和 Conversation 接口，但不会发送聊天请求。

### 4. 正式测试

一次执行完整测试矩阵（并发1、5、10各跑1轮；每轮100个计时请求）：

```bash
bash analysis/quantification/run_server_benchmark.sh matrix
```

每轮之间默认冷却60秒。结果分别写入同一个 `performance-matrix-<时间戳>/c<并发>-r<轮次>/` 目录。并发模式默认至少为每个实际参测账号预热1次；预热请求不计入100个计时请求。失败请求保留在结果中并计入失败率。

如需单独补跑并发测试：

```bash
SSE_CONCURRENCY=5 SSE_REQUESTS=100 \
  bash analysis/quantification/run_server_benchmark.sh concurrency
```

当前项目限制单用户最多同时运行1条SSE，因此并发10必须使用10个不同账号。脚本按账号轮询分配请求，并给每个账号设置独立异步锁；同一账号不会同时运行两条SSE。账号不足时，脚本会在发请求前直接退出。

中断与后续请求恢复：

```bash
bash analysis/quantification/run_server_benchmark.sh interrupt
```

结果按时间戳写入 `analysis/quantification/results/runs/`。脚本会记录账号序号、测试邮箱、HTTP 状态、TTFT、完整延迟、成功率、实际并发 QPS、错误类型和截断后的错误信息，不保存 Token、密码、问题正文或答案正文。

也可绕过启动脚本直接执行 Python：

```bash
.venv/bin/python analysis/quantification/scripts/sse_benchmark.py \
  --config analysis/quantification/configs/quantification-config.server.example.json \
  --base-url http://127.0.0.1/api \
  --dataset datasets/ragas_eval_dataset_final.jsonl \
  --accounts-file analysis/quantification/configs/concurrency-accounts.server.json \
  --mode serial --sample-count 100 --rounds 1
```

## 四、结果边界

- `http://127.0.0.1/api` 测量网关、后端、数据库和模型调用，不包含用户到服务器的公网 RTT 与 TLS 开销。
- `https://正式域名/api` 测量更完整的外部链路，但结果会受到测试机网络影响。
- 脚本测量的是包含真实 RAG 与模型生成的业务性能，不是纯 HTTP 框架吞吐量。
- 并发数还会受到系统限流、模型平台限流和账号额度影响。压测前应确认测试用户额度，并避免在真实用户高峰期运行。
- 脚本不采集 CPU、内存、JVM、连接池和 PostgreSQL 指标；正式报告应同步采集 `docker stats`、主机监控和数据库监控。

满足上述数据、账号、Character、模型与知识库前置条件后，部署完成即可直接运行脚本测试接口性能；仅仅启动容器还不够。
