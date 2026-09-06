# Docker deployment

This is the single Compose entry point for the project. It starts the API, both web applications, Redis, and an Nginx gateway. PostgreSQL and its pgvector/Apache AGE extensions are intentionally managed as an external database.

```bash
cd docker
cp .env.example .env
# Replace the database credentials and the 16-character AES key.
docker compose config
docker compose up -d --build
```

The default routes are `/` for the user app, `/admin/` for the admin app, and `/api/` for the backend. See the [Chinese deployment guide](README.zh-CN.md) and the [database guide](../server/db_migration/README.md) for details.
