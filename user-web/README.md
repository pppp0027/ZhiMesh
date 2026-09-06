> **[🇨🇳 中文文档](README.zh-CN.md)** | English

## Introduction

This directory contains the user-facing web frontend for [zhimesh](../README.md).

## Repository Structure

```
zhimesh/
  ├── server/         Backend service
  ├── admin-web/      Admin dashboard
  └── user-web/       User-facing web app ← This directory
```

- Backend: [server/](../server/)
- Admin Web: [admin-web/](../admin-web/)

## Prerequisites

### Node

`node` v20+

It is recommended to use [nvm](https://github.com/nvm-sh/nvm) to manage multiple local `node` versions.

### PNPM

pnpm v9+

If you haven't installed `pnpm`:

```shell
npm install pnpm -g
```

## Install Dependencies

Run the following commands in this directory:

```shell
cd user-web
pnpm install
```

## Local Development

1. Modify `VITE_GLOB_API_URL` in the `.env` file to your actual backend address.

2. Run the following command in this directory:

```shell
cd user-web
pnpm dev
```

3. If the backend service is on a remote address, use nginx to resolve CORS issues.

For the shared Nginx configuration, refer to [`../docker/nginx/nginx.conf`](../docker/nginx/nginx.conf).

## Production

### Docker

See the project-level [Docker guide](../docker/README.md).

### Manual Build

1. Nginx Configuration

   Refer to the project-level `docker/nginx/nginx.conf` for the combined deployment configuration.

   **If admin web and user web share the same nginx**, use this configuration:

```shell
# zhimesh-web: user web build output
# zhimesh-admin-web: admin web build output

# User Web
# URL: http://your-ip:port/
location / {
  root /usr/share/nginx/zhimesh-web;
  try_files $uri /index.html;
}

# Admin Web
# URL: http://your-ip:port/admin
  location /admin/ {
    alias /usr/share/nginx/zhimesh-admin-web/;
   	index /index.html;
  }

# Backend API
location /api/ {
  proxy_set_header X-Real-IP $remote_addr;
  proxy_pass http://localhost:9999/;
}
```

2. Run the following command in this directory ([reference](https://vitejs.dev/guide/static-deploy.html#building-the-app)):

```shell
pnpm build
```

3. Copy the contents of the `dist` folder to the root directory of your web server.

   The web server root directory is configured in `nginx.conf` under `location /`.

## FAQ

Q: Where to change the API endpoint if I only use the frontend?

A: Set `VITE_GLOB_API_URL` in this directory's `.env` file.

Q: All files show red errors on save?

A: Please install the recommended VSCode extensions, or manually install the ESLint extension.

Q: No typewriter effect in the frontend?

A: One possible reason is that Nginx reverse proxy has buffering enabled. Try adding `proxy_buffering off;` to your reverse proxy config and reload Nginx. The same applies to other web servers.
