---
layout: page
title: webpserver
permalink: /
nav_order: 1
---

# webpserver

![webpserver](../assets/webpserver.svg)

Self-hosted minimalist WebP image service. Upload images in any common format; receive them back in WebP at any size, cached on disk.

Built with **Java 21**, **Quarkus** and **webp4j** (libwebp 1.6.0 via JNI).

---

## Features

- Upload images via multipart form or remote URL
- Automatic conversion to WebP on ingest (JPEG, PNG, WebP, animated GIF)
- On-demand resized variants (`?w=320&h=240`), cached on disk after first generation
- In-memory index rebuilt from disk on startup — no external database
- Optional Bearer token authentication for upload and delete
- Rate limiting per IP (configurable)
- `/liveness` endpoint for Docker and Kubernetes health probes
- Multi-arch Docker image: `linux/amd64` and `linux/arm64`

---

## Quickstart

### Docker

```bash
docker run \
  -v $(pwd)/images:/images \
  -p 8080:8080 \
  ghcr.io/ibethus/webpserver:latest
```

### Upload an image

```bash
curl -F 'file=@photo.jpg' http://localhost:8080/
# {"filename":"3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp"}
```

### Retrieve the original

```bash
curl http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp --output photo.webp
```

### Retrieve a resized variant (320x240)

```bash
curl "http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp?w=320&h=240" \
  --output photo_320x240.webp
```

The first request generates and caches the variant. All subsequent requests are served from the disk cache instantly.

### Upload from a URL

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/photo.jpg"}' \
  http://localhost:8080/
```

### Delete an image

```bash
curl -X DELETE \
  -H "Authorization: Bearer your-api-key" \
  http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp
# {"status":"deleted","cached_files_removed":3}
```

---

## Documentation

- [Endpoints](endpoints.md) — full API reference with examples
- [Configuration](configuration.md) — all environment variables
- [Architecture](architecture.md) — technical design, cache system, concurrency
- [Docker](docker.md) — docker-compose setup
- [Kubernetes](kubernetes.md) — Kubernetes deployment guide

---

## License

MIT
