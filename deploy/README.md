# ZhiMesh 生产部署与发版

CI/CD 链路：**push 到 GitHub main 分支 → Actions 自动构建三个镜像（api / user-web / admin-web）→ 推送到腾讯云 TCR 个人版 → SSH 到服务器 `docker compose pull && up -d`**。

本目录包含服务器侧部署文件：

| 文件 | 用途 |
|---|---|
| `docker-compose.prod.yml` | 生产编排，复制到服务器 `/opt/zhimesh/docker-compose.yml` |
| `.env.example` | 环境变量模板，复制为服务器 `/opt/zhimesh/.env` 并填真实密码 |

## 架构现状

```
用户浏览器
   │
   ▼
nginx 容器（docker run，多项目共用，不在本 compose 内）
   ├── /          → proxy → 127.0.0.1:8081（user-web 容器）
   ├── /admin/    → proxy → 127.0.0.1:8082（admin-web 容器）
   ├── /api/      → proxy → :9999（api 容器，替代原裸跑 JAR）
   └── 其他项目    → 原样不动
   │
   ▼
docker compose 管理的三个容器（restart: unless-stopped）
   api（:9999）─┬→ host.docker.internal:5432 → PostgreSQL 容器（docker run，不动）
               ├→ host.docker.internal:6379 → Redis 容器（docker run，不动）
               └→ /opt/zhimesh/data、/opt/zhimesh/logs（宿主机目录平移挂载）
```

设计要点：

- **镜像是无凭据的**：镜像里只有代码 + JRE，所有配置在服务器 `.env` 中，发版不涉及密码流转
- **有状态数据不进 CI/CD**：数据库/Redis/本地数据目录均不受发版影响，容器随便重建
- **路径平移**：容器内沿用 `/opt/zhimesh/data`、`/opt/zhimesh/logs` 绝对路径，配置值与旧部署逐字一致

## 首次切换（一次性）

前置：已在腾讯云开通 TCR 个人版并建好 3 个仓库；GitHub 已配置 Secrets
（`REGISTRY`、`REGISTRY_NAMESPACE`、`REGISTRY_USERNAME`、`REGISTRY_PASSWORD`、`SSH_HOST`、`SSH_PORT`、`SSH_USER`、`SSH_KEY`）；
服务器已 `docker login ccr.ccs.tencentyun.com`。

```bash
# 1. 放置编排文件（在服务器上）
cp docker-compose.prod.yml /opt/zhimesh/docker-compose.yml

# 2. 创建 .env：以 .env.example 为底，把旧 /opt/zhimesh/config/zhimesh.env
#    的真实值填进去（DB 密码、AES key 等；host 两项保持 host.docker.internal 不动）

# 3. 首次拉取镜像（此时旧 JAR 还在跑，不冲突）
cd /opt/zhimesh && docker compose pull

# 4. 切换后端（停旧 JAR → 起容器，中间几秒~几十秒停机）
kill $(cat /opt/zhimesh/app/zhimesh.pid)     # 或 kill 旧进程号
docker compose up -d

# 5. 验证后端
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9999/   # 任意 HTTP 码即存活
docker compose ps
docker compose logs -f api      # 确认无 ERROR 后 Ctrl+C
```

切换 nginx（容器内配置中本项目相关的部分，其余项目不动）：

```nginx
# location / 由"指向宿主机 dist 目录"改为反代 user-web 容器
location / {
    proxy_pass http://<api现在指向的宿主机地址>:8081;   # 与 /api/ 同款地址，仅换端口
    proxy_set_header Host $host;
}

# /admin/ 同理指向 :8082
location /admin/ {
    proxy_pass http://<同上地址>:8082/;
    proxy_set_header Host $host;
}
```

> `<api现在指向的宿主机地址>`：你的 nginx 在容器里，`/api/` 现在能代理到宿主机 9999，
> 用的什么地址（如 `172.17.0.1` 或宿主机内网 IP）这里就照抄，只把端口换成 8081/8082。
> `/api/` 本身不用改——目标仍是 9999，只是背后从裸 JAR 换成了容器。

改完后 `nginx -s reload`（平滑加载，不断连接），浏览器验证 `/`、`/admin/`、`/api/`
以及 nginx 上的其他项目均正常。旧 JAR（`/opt/zhimesh/app/`）和旧 dist 目录原地封存作为回滚备份。

## 日常发版

```bash
git push zhimesh zhimesh-root:main    # 触发流水线，几分钟后自动上线
```

也可在 GitHub → Actions → deploy → Run workflow 手动触发。

## 回滚

```bash
# 回滚到某个历史版本（tag 形如 sha-<git短哈希>，Actions 构建日志或 TCR 控制台可查）
cd /opt/zhimesh
sed -i 's#:latest#:sha-xxxxxxx#' docker-compose.yml
docker compose up -d

# 回到最新：把 tag 改回 latest 再 up -d

# 彻底退回裸跑 JAR 时代（应急）：
docker compose down
cd /opt/zhimesh/app && java -jar zhimesh.jar --spring.profiles.active=prod   # 按旧启动命令
```

## 常用运维命令

```bash
cd /opt/zhimesh
docker compose ps                  # 容器状态
docker compose logs -f api         # 看后端日志（也可直接 tail /opt/zhimesh/logs）
docker compose restart api         # 重启后端
docker compose pull && docker compose up -d   # 手动发版（不经过 CI）
```

## 注意事项

- 数据库结构迁移（`server/db_migration/` 的 SQL）**不在发版流水线内**，仍按需手动执行
- `ZHIMESH_ENCRYPT_AES_KEY` 必须与旧部署一致，否则历史加密数据无法解密
- 服务器 `.env` 与 GitHub Secrets 是两套独立配置：前者给应用（DB 密码等），后者给流水线（推镜像、SSH 登录）
- 发版只重建 api / user-web / admin-web 三个容器；PostgreSQL、Redis、RabbitMQ、nginx 及其他项目不受影响
