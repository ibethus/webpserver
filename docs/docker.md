
layout: page
title: Docker
permalink: /docker/
nav_order: 5


# Docker

## Quickstart

```bash
docker run \
  -v webpserver_images:/images \
  -p 8080:8080 \
  ghcr.io/ibethus/webpserver:latest
```

```bash
curl http://localhost:8080/liveness
# {"status":"ok"}
```



## Docker Compose

A ready-to-use `docker-compose.yml` is included at the root of the repository:

```bash
git clone https://github.com/ibethus/webpserver.git
cd webpserver
docker compose up -d
```

To customise the setup, pass environment variables under the `environment:` key in the compose file. See [Configuration](configuration.md) for the full variable reference.



## Volumes

Always mount a persistent volume at `/images` in production.

```bash
# Named volume (recommended)
docker run -v webpserver_images:/images ...

# Bind mount (direct file access)
docker run -v /path/on/host:/images ...
```

The container runs as a non-root user (UID 1001). For bind mounts, ensure the host directory is writable:

```bash
sudo chown 1001 /path/on/host
```



## Health check

The included compose file configures a healthcheck on `GET /liveness` every 30 seconds. Use `docker compose ps` to inspect status.



## Available tags

| Tag      | Description               |
| -------- | ------------------------- |
| `latest` | Latest stable release     |
| `1.2.3`  | Specific version          |
| `1.2`    | Latest patch of minor 1.2 |

Images are published for both `linux/amd64` and `linux/arm64`.



## Debug logging

To enable verbose logs (auth decisions, cache lookups, rate-limit checks):

```bash
docker run \
  -e QUARKUS_LOG_CATEGORY__IO_WEBPSERVER__LEVEL=DEBUG \
  -v webpserver_images:/images \
  -p 8080:8080 \
  ghcr.io/ibethus/webpserver:latest
```