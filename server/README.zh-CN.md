# ZhiMesh 后端服务

> **中文（当前文档）** | [English](README.md)

本目录是 zhimesh 的 Java 后端，多模块 Maven 工程。项目总览见[根目录 README](../README.md)，更细的代码边界见[后端目录结构](../docs/development/backend-structure.zh-CN.md)。

`README.zh-CN.md` 是中文版，`README.md` 是内容对应的英文版；两者不是新旧版本。

## 模块

| 模块 | 职责 |
| --- | --- |
| `zhimesh-common` | 实体、Mapper、公共配置、模型/向量/图存储适配和公共服务 |
| `zhimesh-chat` | 用户侧聊天、知识库、角色、会话、工作流和 MCP 接口 |
| `zhimesh-admin` | 管理侧用户、模型、系统配置和内部 RAG 评估接口 |
| `zhimesh-bootstrap` | Spring Boot 启动模块及环境配置 |
| `db_migration` | 全量初始化、增量迁移和数据库结构校验脚本 |
| `.mvn/local-repo` | 构建所需的项目内 Maven 制品，不是普通缓存 |

## 当前技术基线

- JDK 17
- Spring Boot 3.5.14
- LangChain4j 1.14.1
- PostgreSQL 16
- pgvector；图检索使用 Apache AGE 或 Neo4j
- Redis

## 数据库

全新数据库按顺序执行：

1. `db_migration/all_ddl.sql`
2. `db_migration/all_dml.sql`
3. `db_migration/all_dml_cn.sql` 或 `db_migration/all_dml_en.sql`，二选一
4. `db_migration/verify_schema.sql`

已有数据库不能执行 `all_*` 覆盖，应按编号执行增量迁移。完整说明见[数据库初始化与迁移](db_migration/README.zh-CN.md)，当前静态表和运行时向量表说明见[数据库结构](../docs/database/schema.zh-CN.md)。

应用会按嵌入模型和维度动态创建 pgvector 表，因此这些表不会固定写入 `all_ddl.sql`。

## 本地开发

复制开发配置：

```bash
cd server
cp zhimesh-bootstrap/src/main/resources/application-dev.yml.example \
  zhimesh-bootstrap/src/main/resources/application-dev.yml
```

修改 `application-dev.yml` 中的 PostgreSQL、Redis、向量库、图库和本地数据目录。该文件包含本地凭据，已经加入忽略规则，不应提交。

构建和测试：

```bash
mvn -B -ntp test
mvn -B -ntp clean package -DskipTests
```

开发模式启动：

```bash
java -jar zhimesh-bootstrap/target/zhimesh-bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

默认后端端口为 `9999`。

## 生产环境：直接运行 JAR

生产配置使用环境变量注入，不修改并提交 `application-prod.yml`。常用变量：

| 变量 | 说明 |
| --- | --- |
| `ZHIMESH_DB_HOST`、`ZHIMESH_DB_PORT`、`ZHIMESH_DB_NAME` | PostgreSQL 连接 |
| `ZHIMESH_DB_USERNAME`、`ZHIMESH_DB_PASSWORD` | PostgreSQL 凭据 |
| `ZHIMESH_REDIS_HOST`、`ZHIMESH_REDIS_PORT`、`ZHIMESH_REDIS_PASSWORD` | Redis 连接 |
| `ZHIMESH_ENCRYPT_AES_KEY` | 16 字符 AES 密钥；必须与数据库中已加密配置使用的密钥一致 |
| `ZHIMESH_EMBEDDING_MODEL` | 当前嵌入模型；更换后需要重建向量数据 |
| `ZHIMESH_VECTOR_DATABASE` | `pgvector` 或 `neo4j` |
| `ZHIMESH_GRAPH_DATABASE` | `apache-age` 或 `neo4j` |
| `ZHIMESH_LOCAL_BASE_DATA_PATH` | 上传文件、图片和聊天记忆目录 |
| `ZHIMESH_CONVERSATION_ENABLED` | 是否启用独立 Conversation；性能脚本需要设为 `true` |

当 JAR 运行在服务器宿主机，而 PostgreSQL/Redis 运行在 Docker 中时，应连接容器映射到宿主机的端口，例如 `127.0.0.1:5432` 和 `127.0.0.1:6379`，不要使用仅在 Docker 网络中可解析的服务名。

启动：

```bash
java -Xms512m -Xmx1536m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -jar zhimesh-bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

正式环境建议使用 systemd 或其它进程管理器加载权限为 `600` 的环境变量文件，不要把密码直接写入启动脚本。

## Docker 部署

项目只维护根目录的一套 Compose，不再维护 `server/docker`。见[Docker 部署说明](../docker/README.zh-CN.md)。

## 启动检查与性能测试

基础连通性：

```bash
curl -f http://127.0.0.1:9999/auth/search-engine/list
```

启动成功后还应验证模型调用、知识库向量检索、图检索和重排服务。服务器部署和 SSE 接口性能测试见[服务器部署与接口性能测试](../docs/guides/server-deployment-and-benchmark.zh-CN.md)。直接访问 JAR 时，性能脚本的 `ZHIMESH_API_BASE_URL` 使用 `http://127.0.0.1:9999`；经过项目网关时使用 `http://127.0.0.1/api`。
