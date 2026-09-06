# 项目文档中心

除各子项目的最短启动说明外，跨模块说明、设计记录和实施材料统一放在本目录。新增文档时按内容归类，不再放入 `server/docs` 或项目根目录。

## 分类索引

### 项目与开发

- [项目目录与包职责](development/project-layout.zh-CN.md)
- [后端目录结构](development/backend-structure.zh-CN.md)
- [数据库结构与真实状态](database/schema.zh-CN.md)
- [Docker 部署](../docker/README.zh-CN.md)
- [服务器部署与接口性能测试](guides/server-deployment-and-benchmark.zh-CN.md)
- [数据库初始化与迁移](../server/db_migration/README.zh-CN.md)

### 架构与流程

- [RAG 知识库入库完整流程](architecture/flowcharts/RAG知识库入库完整流程.md)
- [一次聊天请求完整流程](architecture/flowcharts/一次聊天请求完整流程.md)
- 现有中文架构文档：[`cn/`](cn/)
- Existing English architecture docs: [`en/`](en/)

### 使用与实验指南

- [RAG 系统四维量化实验执行指南](guides/rag-system-four-dimension-quantification-execution-guide.zh-CN.md)
- [bge-reranker-base 部署说明](guides/bge-reranker-base.md)
- [RAGAS 评估包说明](../ragas-evaluation/README.md)

### 改造计划

- [Character 与 Conversation 轻量化改造计划](plans/agent-conversation-refactoring-plan.zh-CN.md)
- [检索 Query Embedding 复用与远程 Embedding 模型接入优化计划](plans/retrieval-embedding-optimization-plan.zh-CN.md)
- [RAG 意图识别与检索路由四阶段实施计划](plans/intent-routing-four-stage-implementation-plan.zh-CN.md)
- [OpenRouter 免费模型自动同步直接实施方案](plans/openrouter-free-model-auto-sync-implementation-plan.zh-CN.md)

### 实施报告

- [Character / Conversation 改造实施报告](reports/agent-conversation-refactoring-implementation-report.zh-CN.md)
- [图谱检索链路优化报告](reports/rag/graph-retrieval-optimization-report.zh-CN.md)
- [混合检索融合与重排优化复盘](reports/rag/hybrid-fusion-optimization-report.zh-CN.md)

图片等共享静态资源继续放在 [`image/`](image/)；不要把日志、数据库文件、构建产物或临时补丁放入文档目录。
