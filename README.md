# ZhiMesh

ZhiMesh 是一个基于 Java 17、Spring Boot、LangChain4j 与 Vue 3 的 AI 应用平台，提供聊天、知识库 RAG、角色长期记忆、工作流、模型平台管理、绘图、语音和 MCP 集成，并包含独立的 RAGAS 评估与接口性能测试工具。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| [`server/`](server/) | Spring Boot 后端，多模块 Maven 工程 |
| [`admin-web/`](admin-web/) | 管理后台 |
| [`user-web/`](user-web/) | 用户端 |
| [`docker/`](docker/) | 统一 Docker Compose 与 Nginx 网关 |
| [`docs/`](docs/) | 架构、指南、计划、报告和数据库说明 |
| [`ragas-evaluation/`](ragas-evaluation/) | RAGAS 实验、结果整理与 API 性能测试 |

更细的目录职责见[项目目录与包职责](docs/development/project-layout.zh-CN.md)，全部说明材料见[文档中心](docs/README.md)。

## 技术栈

- 后端：Java 17、Spring Boot 3.5.x、LangChain4j 1.14.x、MyBatis-Plus
- 前端：Vue 3、TypeScript、Vite、Pinia
- 数据：PostgreSQL 16、pgvector、Redis；图检索可选 Apache AGE 或 Neo4j
- 部署：Docker Compose、Nginx
- 评估：Python、RAGAS、接口延迟/吞吐量测试

## 快速开始

### 1. 初始化数据库

按照[数据库初始化与迁移说明](server/db_migration/README.zh-CN.md)创建 PostgreSQL 数据库并执行全量脚本。当前结构和运行时向量表说明见[数据库结构](docs/database/schema.zh-CN.md)。

### 2. 启动后端

```bash
cd server
cp zhimesh-bootstrap/src/main/resources/application-dev.yml.example zhimesh-bootstrap/src/main/resources/application-dev.yml
# 编辑数据库、Redis、向量库和图数据库配置
mvn -pl zhimesh-bootstrap -am package -DskipTests
java -jar zhimesh-bootstrap/target/zhimesh-bootstrap-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

### 3. 启动两个前端

根目录不是 Node workspace，两个前端应分别执行：

```bash
cd admin-web
pnpm install
pnpm dev
```

```bash
cd user-web
pnpm install
pnpm dev
```

### 4. 使用 Docker

```bash
cd docker
cp .env.example .env
# 填写数据库凭据和 AES 密钥
docker compose up -d --build
```

完整说明见 [Docker 部署文档](docker/README.zh-CN.md)。Compose 默认提供用户端 `/`、管理端 `/admin/` 和后端 `/api/`。

## 开发约定

- 不提交真实 `.env`、数据库文件、日志、IDE 配置、`node_modules`、`dist`、`target` 和运行时上传目录。
- 数据库结构变化必须同步更新全量 DDL、编号迁移、实体/Mapper 与 `verify_schema.sql`。
- 跨模块文档统一放在 `docs/` 对应分类；包内 README 只保留该包的入口说明。
- 管理员初始账号只用于本地初始化，首次部署后必须立即修改默认密码和所有示例密钥。

## 许可证与贡献

项目采用 [MIT License](LICENSE)。贡献流程见 [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md)，行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
