# 项目目录与包职责

本文说明根目录各文件夹的边界。`ragas-evaluation` 的实验结构由该包自己的 README 维护，不在此重复展开。

| 目录 | 职责 | 主要入口/说明 |
| --- | --- | --- |
| `server/` | Java 后端，多模块 Maven 工程 | `zhimesh-bootstrap` 启动；详见[后端目录结构](backend-structure.zh-CN.md) |
| `admin-web/` | Vue 3 管理后台 | `pnpm dev` / `pnpm build` |
| `user-web/` | Vue 3 用户端 | `pnpm dev` / `pnpm build` |
| `docker/` | 唯一的容器编排与统一网关配置 | [Docker 部署说明](../../docker/README.zh-CN.md) |
| `docs/` | 架构、指南、计划、报告和数据库说明 | [文档索引](../README.md) |
| `ragas-evaluation/` | RAGAS 实验、结果与接口性能测试 | [包内说明](../../ragas-evaluation/README.md) |

## 后端模块

| 模块 | 作用 |
| --- | --- |
| `zhimesh-common` | 实体、Mapper、公共配置、模型/向量/图存储适配及通用能力 |
| `zhimesh-chat` | 用户侧聊天、知识库、工作流、角色记忆等业务接口 |
| `zhimesh-admin` | 管理侧用户、模型、配置、知识库等管理接口 |
| `zhimesh-bootstrap` | Spring Boot 启动模块及环境配置 |
| `db_migration` | 全量初始化、增量迁移和结构校验 SQL |
| `.mvn/local-repo` | 构建所需的项目内本地 Maven 制品，不属于普通缓存，不应删除 |

运行时上传文件和聊天记忆数据库由 `server/data/` 产生，该目录不作为源码提交。构建产物 `target/`、`dist/`、`node_modules/`，IDE 配置和本地缓存也不属于项目源码。

## 前端边界

两个前端均独立安装依赖和执行脚本，根目录没有统一的 Node workspace：

```bash
cd admin-web && pnpm install && pnpm dev
cd user-web && pnpm install && pnpm dev
```

前端各自的 Dockerfile 仅负责生成静态镜像；路由与后端反向代理统一由 `docker/nginx/nginx.conf` 管理。
