---
layout: page
title: Docker
permalink: /docker/
nav_order: 5
---

# Docker

## Quickstart

Run the latest image with a named volume for persistent storage:

```bash
docker run \
  -v webpserver_images:/images \
  -p 8080:8080 \
  ghcr.io/ibethus/webpserver:latest
```

Test that the service is running:

```bash
curl http://localhost:8080/liveness
# {"status":"ok"}
```

---

## Docker Compose

A ready-to-use `docker-compose.yml` is included at the root of the repository.

```bash
git clone https://github.com/ibethus/webpserver.git
cd webpserver
docker compose up -d
```

The compose file starts a single `webpserver` container with:
- A named volume `webpserver_images` mounted at `/images`
- Port `8080` exposed on the host
- A healthcheck on `GET /liveness` every 30 seconds
- `restart: unless-stopped` policy

### Enabling authentication

Edit `docker-compose.yml` and uncomment the `WEBPSERVER_API_KEY` line:

```yaml
environment:
  WEBPSERVER_API_KEY: "your-strong-secret"
  WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD: "true"
```

Then restart:

```bash
docker compose up -d
```

### Restricting allowed sizes

To prevent cache-bombing, uncomment and configure `WEBPSERVER_VALID_SIZES`:

```yaml
environment:
  WEBPSERVER_VALID_SIZES: "100,200,320,640,1280"
```

---

## Environment variables

See [Configuration](configuration.md) for the full reference.

In the Docker Compose file and `docker run` commands, environment variables use the format `WEBPSERVER_*` (with underscores) as Quarkus converts them from `webpserver.*` property notation automatically.

---

## Volumes

The container stores all images at `/images`. Always mount a persistent volume at this path in production.

```bash
# Named volume (recommended)
docker run -v webpserver_images:/images ...

# Bind mount (for local development or direct file access)
docker run -v /path/on/host:/images ...
```

**Important:** The process runs as UID 1001. If using a bind mount, ensure the host directory is owned by UID 1001:

```bash
sudo chown -R 1001:1001 /path/on/host
```

---

## Health check

The `docker-compose.yml` includes a healthcheck:

```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -sf http://localhost:8080/liveness || exit 1"]
  interval: 30s
  timeout: 5s
  retries: 3
  start_period: 15s
```

The container is marked as `healthy` after the first successful probe. Use `docker compose ps` to inspect the health status.

---

## Building the image locally

```bash
git clone https://github.com/ibethus/webpserver.git
cd webpserver
./mvnw package -DskipTests
docker build -t webpserver:local .
docker run -v webpserver_images:/images -p 8080:8080 webpserver:local
```

---

## Available tags

| Tag | Description |
|-----|-------------|
| `latest` | Latest stable release |
| `1.2.3` | Specific version |
| `1.2` | Latest patch of minor version 1.2 |

All tags are available for `linux/amd64` and `linux/arm64`.

```bash
# Pull for a specific platform explicitly
docker pull --platform linux/arm64 ghcr.io/ibethus/webpserver:latest
```

---

## Debug logging

By default the application logs at `INFO` level. To enable verbose `DEBUG` logs (auth decisions, cache lookups, rate-limit checks), set the following environment variable:

```bash
docker run \
  -e QUARKUS_LOG_CATEGORY__IO_WEBPSERVER__LEVEL=DEBUG \
  -v webpserver_images:/images \
  -p 8080:8080 \
  ghcr.io/ibethus/webpserver:latest
```

In `docker-compose.yml`, add it under `environment:`:

```yaml
environment:
  QUARKUS_LOG_CATEGORY__IO_WEBPSERVER__LEVEL: DEBUG
```

> **Note:** `DEBUG` logs include individual auth outcomes and per-IP rate-limit results. Do not enable it permanently in production as it is verbose and may expose client IP addresses in logs.
