# ZhiMesh backend service

> [中文](README.zh-CN.md) | **English (current document)**

This directory contains the Java backend for zhimesh as a multi-module Maven project. See the [root README](../README.md) for the project overview and the [backend structure guide](../docs/development/backend-structure.zh-CN.md) for detailed boundaries.

`README.md` is the English edition and `README.zh-CN.md` is the corresponding Chinese edition. They are not different software versions.

## Modules

| Module | Responsibility |
| --- | --- |
| `zhimesh-common` | Entities, mappers, shared configuration, model/vector/graph adapters, and common services |
| `zhimesh-chat` | User-facing chat, knowledge-base, character, conversation, workflow, and MCP APIs |
| `zhimesh-admin` | Administration APIs and the internal RAG evaluation endpoint |
| `zhimesh-bootstrap` | Spring Boot entry point and environment configuration |
| `db_migration` | Full initialization, incremental migrations, and schema verification |
| `.mvn/local-repo` | Project-local Maven artifacts required by the build; not a disposable cache |

## Current baseline

- JDK 17
- Spring Boot 3.5.14
- LangChain4j 1.14.1
- PostgreSQL 16 with pgvector
- Apache AGE or Neo4j for graph retrieval
- Redis

## Database

For a fresh database, run `all_ddl.sql`, `all_dml.sql`, exactly one language DML, and finally `verify_schema.sql`. For an existing database, apply numbered migrations instead of the `all_*` snapshots.

See the [database migration guide](db_migration/README.md) and the [current schema guide](../docs/database/schema.zh-CN.md). pgvector tables are created dynamically for the configured embedding model and dimension, so they are intentionally absent from the static DDL.

## Local development

```bash
cd server
cp zhimesh-bootstrap/src/main/resources/application-dev.yml.example \
  zhimesh-bootstrap/src/main/resources/application-dev.yml

mvn -B -ntp test
mvn -B -ntp clean package -DskipTests

java -jar zhimesh-bootstrap/target/zhimesh-bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

Configure PostgreSQL, Redis, storage, vector retrieval, and graph retrieval in the local `application-dev.yml`. It contains local credentials and must not be committed. The default backend port is `9999`.

## Production JAR

Production settings are injected through environment variables. Important variables include:

- `ZHIMESH_DB_HOST`, `ZHIMESH_DB_PORT`, `ZHIMESH_DB_NAME`, `ZHIMESH_DB_USERNAME`, `ZHIMESH_DB_PASSWORD`
- `ZHIMESH_REDIS_HOST`, `ZHIMESH_REDIS_PORT`, `ZHIMESH_REDIS_PASSWORD`
- `ZHIMESH_ENCRYPT_AES_KEY` — exactly 16 characters and identical to the key used for encrypted database configuration
- `ZHIMESH_EMBEDDING_MODEL`, `ZHIMESH_VECTOR_DATABASE`, `ZHIMESH_GRAPH_DATABASE`
- `ZHIMESH_LOCAL_BASE_DATA_PATH`
- `ZHIMESH_CONVERSATION_ENABLED` — set to `true` for the SSE benchmark

When the JAR runs on the server host and PostgreSQL/Redis run in Docker, connect to their host-published ports such as `127.0.0.1:5432` and `127.0.0.1:6379`. Docker-only service names are not normally resolvable by a host process.

```bash
java -Xms512m -Xmx1536m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -jar zhimesh-bootstrap-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

Use systemd or another process manager with a protected environment file for long-running production deployments.

## Docker and performance testing

The project maintains one Compose stack at the repository root; the former `server/docker` deployment is obsolete. See the [Docker guide](../docker/README.md).

```bash
curl -f http://127.0.0.1:9999/auth/search-engine/list
```

For server deployment and SSE performance testing, see the [Chinese deployment and benchmark guide](../docs/guides/server-deployment-and-benchmark.zh-CN.md). Use `http://127.0.0.1:9999` as `ZHIMESH_API_BASE_URL` for direct JAR access, or `http://127.0.0.1/api` through the project gateway.
