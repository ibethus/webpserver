<p align="center">
  <img src="docs/assets/webpserver.svg" alt="webpserver" />
</p>

**webpserver** is a self-hosted image server that accepts JPEG, PNG, GIF, or WebP uploads and stores, serves and resizes everything as WebP, fast — no database, no fuss.

---

## TL;DR

### Docker

```bash
docker run \
  -v webpserver_images:/images \
  -p 8080:8080 \
  ghcr.io/ibethus/webpserver:latest
```

### Docker Compose

```bash
git clone https://github.com/ibethus/webpserver.git
cd webpserver
docker compose up -d
```

### API at a glance

**Upload a file**
```bash
curl -F 'file=@photo.jpg' http://localhost:8080/
# → {"filename":"3f2a1b4c-…-9b0c.webp"}
```

**Upload from a URL**
```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/photo.jpg"}' \
  http://localhost:8080/from-url
# → {"filename":"3f2a1b4c-…-9b0c.webp"}
```

**Retrieve an image** (original or resized)

In a terminal :

```bash
curl http://localhost:8080/3f2a1b4c-…-9b0c.webp --output photo.webp

# scale to width 320, preserve aspect ratio
curl "http://localhost:8080/3f2a1b4c-…-9b0c.webp?w=320" --output photo_320.webp

# exact crop 320×240
curl "http://localhost:8080/3f2a1b4c-…-9b0c.webp?w=320&h=240" --output photo_320x240.webp
```
Or navigate to one of the urls in your browser directly.

**Delete an image**
```bash
curl -X DELETE \
  -H "Authorization: Bearer your-api-key" \
  http://localhost:8080/3f2a1b4c-…-9b0c.webp
# → {"status":"deleted","cached_files_removed":3}
```

**Liveness check**
```bash
curl http://localhost:8080/liveness
# → {"status":"ok"}
```

---

## Documentation

| Guide | Description |
|---|---|
| [Endpoints](docs/endpoints.md) | Full API reference with request/response examples |
| [Configuration](docs/configuration.md) | All environment variables and their defaults |
| [Docker](docs/docker.md) | Docker and Docker Compose setup |
| [Kubernetes](docs/kubernetes.md) | Kubernetes deployment and ConfigMap |
| [Architecture](docs/architecture.md) | Cache system, concurrency model, technical design |

---

## License

MIT
