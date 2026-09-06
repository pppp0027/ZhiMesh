# Docker 部署

本目录是项目唯一的容器编排入口，负责启动后端、用户端、管理端、Redis 和统一 Nginx 网关。PostgreSQL、pgvector 与 Apache AGE 由外部数据库提供，避免在同一个镜像中混装扩展导致版本不可控。

## 前置条件

- Docker Engine 24+，并支持 Compose V2（`docker compose`）
- 可访问的 PostgreSQL 16 实例
- 按需安装 pgvector；使用 Apache AGE 图检索时安装 AGE
- 数据库已按 [`server/db_migration`](../server/db_migration/README.zh-CN.md) 初始化

## 启动

```bash
cd docker
cp .env.example .env
# 编辑 .env，至少替换数据库账号、密码和 16 位 AES 密钥
docker compose config
docker compose up -d --build
```

默认入口：

- 用户端：`http://localhost/`
- 管理端：`http://localhost/admin/`
- API：`http://localhost/api/`

可通过 `.env` 中的 `HTTP_PORT` 修改宿主机端口。

## 服务与数据

| 服务 | 作用 | 持久化 |
| --- | --- | --- |
| `gateway` | 对外暴露统一 HTTP 入口 | 无 |
| `api` | Spring Boot 后端 | `app-data`、`app-logs` |
| `user-web` | 用户端静态站点 | 无 |
| `admin-web` | 管理端静态站点 | 无 |
| `redis` | 缓存与会话依赖 | `redis-data` |

Compose 不再设置固定 `container_name`，因此可在同一主机部署多个项目副本。Redis 默认不映射宿主机端口，也不设置密码，仅允许 Compose 内部网络访问。
Redis 开启 AOF，并使用 `noeviction`，避免内存压力下静默淘汰短期记忆；生产环境仍需按容量规划、备份和故障恢复要求配置独立 Redis 或托管 Redis。

## 常用命令

```bash
docker compose ps
docker compose logs -f api
docker compose pull
docker compose up -d --build
docker compose down
```

`docker compose down` 不会删除命名卷；只有明确确认数据不再需要时才使用 `docker compose down -v`。

## 配置原则

- `.env` 与 `.env.*` 已加入忽略规则，不要提交真实密码或密钥。
- 镜像版本可在 `.env` 中覆盖；升级前应先在测试环境构建并执行健康检查。
- `host.docker.internal` 适用于 Docker Desktop。Linux 部署时应把 `ZHIMESH_DB_HOST` 改为数据库可达地址。
- Compose 已为 Linux 添加 `host-gateway` 映射；若 PostgreSQL 位于本机，仍需正确配置 PostgreSQL 监听地址和 `pg_hba.conf`。
- API 流式响应由 Nginx 关闭代理缓冲，并将读写超时设为 600 秒。
- 短期记忆永久使用 Redis；部署前必须确认 Redis 健康、AOF/持久化策略和容量满足要求。旧 MapDB 测试记忆不会读取或迁移。

服务器域名、HTTPS、数据库网络和部署后性能测试的完整清单见[服务器部署与接口性能测试](../docs/guides/server-deployment-and-benchmark.zh-CN.md)。
短期记忆的运行边界与验收步骤见[短期记忆 Redis 实施报告](../docs/reports/short-term-memory-redis-implementation-report.zh-CN.md)。
