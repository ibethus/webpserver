---
layout: page
title: Configuration
permalink: /configuration/
nav_order: 3
---

# Configuration

All configuration is done through environment variables. There are no configuration files to manage.

---

## Storage

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_IMAGES_DIR` | `/images` | Absolute path to the directory where images and variants are stored. Must be writable by the process (UID 1001). Mount a persistent volume at this path. |

---

## Authentication

Authentication uses a static Bearer token.

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_API_KEY` | _(none)_ | Static API key. If not set, authentication is **disabled globally** (all operations are public). Set this to a strong random secret in production. |
| `WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD` | `false` | When `true`, upload requests require a valid `Authorization: Bearer <key>` header. |

The `DELETE /{filename}` endpoint always requires the Bearer token if `WEBPSERVER_API_KEY` is set, regardless of `WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD`.

Example header:

```
Authorization: Bearer your-secret-key-here
```

---

## File size

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_MAX_SIZE_MB` | `16` | Maximum allowed upload size in megabytes. Requests exceeding this limit are rejected before processing with HTTP 400. |

---

## Resize validation

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_VALID_SIZES` | _(any)_ | Comma-separated list of allowed pixel values for the `?w=` and `?h=` query parameters. Example: `100,200,320,640,1280`. Requests with unlisted sizes are rejected with HTTP 400. **Strongly recommended in production** to prevent cache-bombing attacks (an attacker requesting thousands of different sizes to fill your disk). |

---

## Rate limiting

Rate limiting applies to upload requests only (`POST /`), per client IP address.

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_MAX_UPLOADS_PER_MINUTE` | `20` | Maximum upload requests per IP per minute. |
| `WEBPSERVER_MAX_UPLOADS_PER_HOUR` | `100` | Maximum upload requests per IP per hour. |
| `WEBPSERVER_MAX_UPLOADS_PER_DAY` | `1000` | Maximum upload requests per IP per day. |

When a rate limit is exceeded, the server returns HTTP 429 with a `Retry-After: 60` header.

Set these to very high values (e.g. `999999`) to effectively disable rate limiting.

---

## WebP encoding

| Variable | Default | Description |
|----------|---------|-------------|
| `WEBPSERVER_WEBP_QUALITY` | `75.0` | Lossy WebP quality factor. Range: `0.0` (worst) to `100.0` (best). Higher values produce better image quality but larger files. `75.0` is a good balance for most use cases. |
| `WEBPSERVER_WEBP_LOSSLESS` | `false` | Set to `true` to use lossless compression for all images. Recommended for PNG sources. Not recommended for JPEG (lossless re-encoding of a lossy source typically produces larger files without visible quality benefit). |

---

## CORS

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_HTTP_CORS` | `true` | Enable or disable CORS headers. |
| `QUARKUS_HTTP_CORS_ORIGINS` | `*` | Comma-separated list of allowed origins. Example: `https://app.example.com,https://admin.example.com`. Set to a specific domain in production to prevent cross-origin abuse. |

---

## HTTP server

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_HTTP_PORT` | `8080` | Port the HTTP server listens on inside the container. Change the Docker/Kubernetes port mapping rather than this value in most cases. |

---

## Security recommendations for production

1. **Set `WEBPSERVER_API_KEY`** to a cryptographically random value (e.g. `openssl rand -hex 32`).
2. **Set `WEBPSERVER_REQUIRE_API_KEY_FOR_UPLOAD=true`** unless you intentionally want a public upload endpoint.
3. **Set `WEBPSERVER_VALID_SIZES`** to the exact set of sizes needed by your application.
4. **Restrict `QUARKUS_HTTP_CORS_ORIGINS`** to your application's domain.
5. **Place the service behind a reverse proxy** (nginx, Traefik) that handles TLS termination.
6. **Set a reasonable `WEBPSERVER_MAX_SIZE_MB`** (default 16 MB is suitable for photos; reduce for avatars).
