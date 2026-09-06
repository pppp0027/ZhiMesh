# 后端目录结构

后端采用 Maven 多模块结构。当前整理只调整目录和依赖归属，不修改 Java 包名、Spring 扫描范围、接口路径、数据库表或运行配置。

```text
server/
├── .mvn/
│   ├── maven.config          Maven 项目级参数
│   └── local-repo/           仅存放无法从公共仓库获取的两个定制依赖
├── zhimesh-common/               领域模型、持久层、服务、模型接入和 RAG 实现
├── zhimesh-chat/                 用户端 HTTP 接口
├── zhimesh-admin/                管理端 HTTP 接口
├── zhimesh-bootstrap/            Spring Boot 启动入口和环境配置
├── data/                     本地运行数据，不是源码
├── db_migration/             PostgreSQL 数据库迁移脚本
├── docker/                   后端容器部署文件
├── docs/
│   ├── diagnostics/          历史诊断日志
│   └── flowcharts/           关键业务流程说明
├── Dockerfile
└── pom.xml                   模块、Java版本和依赖版本管理
```

## 模块依赖方向

```text
zhimesh-bootstrap
├── zhimesh-chat
│   └── zhimesh-common
└── zhimesh-admin
    └── zhimesh-common
```

- `zhimesh-common`承载共享业务实现，因此第三方业务依赖在它自己的`pom.xml`声明。
- `zhimesh-chat`只放用户端Controller及其直接依赖。
- `zhimesh-admin`只放管理端Controller及其直接依赖。
- `zhimesh-bootstrap`只负责组合模块并启动Spring Boot。
- 根`pom.xml`不再让所有子模块隐式继承整套业务依赖。

## Maven缓存

项目原来的`server/.m2-repository`约616 MB，而且被误提交到Git。它现在迁移为：

```text
<项目根目录>/.cache/maven-repository
```

该目录已被Git忽略，由`server/.mvn/maven.config`自动指定。它只是本机缓存，删除后Maven可以重新下载，不属于后端源码。

`server/.mvn/local-repo`不同：这里只有Apache AGE JDBC和Happy Captcha两个项目需要、公共仓库不稳定的定制构件，应当保留。

## 构建与启动

所有Maven命令从`server`目录执行：

```powershell
cd D:\My-Study\iedaProjects\zhimesh\server

# 完整构建
mvn clean package -DskipTests

# 构建并启动开发环境
mvn -pl zhimesh-bootstrap -am package -DskipTests
java -jar zhimesh-bootstrap/target/zhimesh-bootstrap-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

也可以继续在IDE中直接运行：

```text
com.pppp.zhimesh.BootstrapApplication
```

本地真实配置仍然位于：

```text
zhimesh-bootstrap/src/main/resources/application-dev.yml
```

该文件被Git忽略，整理过程中没有移动或覆盖。
