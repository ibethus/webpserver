---
layout: page
title: Configuration
permalink: /configuration/
nav_order: 3
---

# Configuration

All configuration is done through environment variables. Pass them with `-e` in Docker, under `environment:` in Compose, or via `env:` in Kubernetes.

```bash
docker run -p 8080:8080 \
  -e WEBPSERVER_API_KEY=my-secret \
  -e WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD=true \
  -e WEBPSERVER_VALID_SIZES=320,640,1280 \
  -v /srv/images:/images \
  ghcr.io/owner/webpserver:latest
```

---

## Application variables

### Storage

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_IMAGES_DIR` | `/images` | Path where images and variants are stored. Mount a persistent volume here. Must be writable by UID 1001. |

### Authentication

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_API_KEY` | _(none — auth disabled)_ | Static Bearer token. If unset, all endpoints are public. |
| `WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD` | `false` | Set to `true` to require the token on `POST /`. The `DELETE /{filename}` endpoint always requires it when a key is set. |

```
Authorization: Bearer your-secret-key-here
```

### Uploads

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_MAX_SIZE_MB` | `16` | Maximum upload size in MB. Larger requests are rejected with HTTP 400. |
| `WEBPSERVER_MAX_UPLOADS_PER_MINUTE` | `20` | Per-IP rate limit (minute). |
| `WEBPSERVER_MAX_UPLOADS_PER_HOUR` | `100` | Per-IP rate limit (hour). |
| `WEBPSERVER_MAX_UPLOADS_PER_DAY` | `1000` | Per-IP rate limit (day). |

Rate limits apply to `POST /` only. Exceeding them returns HTTP 429 with `Retry-After: 60`. Set to a large value (e.g. `999999`) to disable.

### Resize validation

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_VALID_SIZES` | `100,200,400,800,1200,1900,2500` | Allowed values for `?w=` and `?h=` query parameters. Requests with other values are rejected with HTTP 400. Unset to allow any size (not recommended — exposes you to cache-bombing). |

### WebP encoding

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_WEBP_QUALITY` | `60` | Lossy quality, `0`–`100`. Higher = better quality, larger files. |
| `WEBPSERVER_WEBP_LOSSLESS` | `false` | Set to `true` for lossless encoding. Useful for PNG sources; avoid for JPEG. |

---

## Quarkus variables

The server runs on [Quarkus](https://quarkus.io). A few of its built-in variables are useful to configure at deploy time:

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_HTTP_PORT` | `8080` | Listening port inside the container. |
| `QUARKUS_HTTP_ROOT_PATH` | `/` | Root path for all endpoints. Set to e.g. `/api/v1` when serving behind a sub-path proxy. Swagger UI and OpenAPI spec adjust automatically. |
| `QUARKUS_HTTP_CORS_ORIGINS` | `*` | Allowed CORS origins. Restrict to your domain in production. |

For the full list of Quarkus HTTP and CORS options see the official docs:
[HTTP reference](https://quarkus.io/guides/http-reference) · [CORS](https://quarkus.io/guides/http-reference#cors-filter)

---

## Metrics (Micrometer + Prometheus)

Metrics are **disabled by default**. To enable them and expose a Prometheus scrape endpoint:

```
QUARKUS_MICROMETER_ENABLED=true
QUARKUS_MICROMETER_EXPORT_PROMETHEUS_ENABLED=true
```

The metrics endpoint is then available at `/q/metrics`. Point your Prometheus `scrape_configs` at it:

```yaml
scrape_configs:
  - job_name: webpserver
    static_configs:
      - targets: ["webpserver:8080"]
    metrics_path: /q/metrics
```

For the full list of options (custom registry, push gateway, tags, etc.) see the [Quarkus Micrometer guide](https://quarkus.io/guides/micrometer).

---

## Security checklist for production

1. Set `WEBPSERVER_API_KEY` to a strong random value (`openssl rand -hex 32`).
2. Set `WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD=true`.
3. Set `WEBPSERVER_VALID_SIZES` to only the sizes your app needs.
4. Set `QUARKUS_HTTP_CORS_ORIGINS` to your domain.
5. Put the service behind a TLS-terminating reverse proxy (nginx, Traefik, etc.).
