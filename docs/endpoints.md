---
layout: page
title: API Endpoints
permalink: /endpoints/
nav_order: 2
---

# API Endpoints

The full machine-readable contract is available as an [OpenAPI 3.1 specification](https://github.com/ibethus/webpserver/blob/main/openapi.yaml).

---

## POST /

Upload an image via multipart form. The image is validated, converted to WebP and stored on disk. The returned `filename` is used for all subsequent GET and DELETE requests.

### Request — multipart file upload

```
POST /
Content-Type: multipart/form-data

file=<binary>
```

**Field:** `file` (required). Binary image data. Accepted formats: JPEG, PNG, WebP, GIF.

### Authentication

If `API_KEY` is set and `REQUIRE_API_KEY_FOR_UPLOAD=true`, the request must include:

```
Authorization: Bearer <your-api-key>
```

### Response — 201 Created

```json
{"filename": "3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp"}
```

### Error responses

| Status | Cause |
|--------|-------|
| 400 | No file provided |
| 400 | Unsupported image format |
| 400 | File exceeds `MAX_SIZE_MB` |
| 401 | Missing or invalid Bearer token |
| 429 | Rate limit exceeded — `Retry-After` header included |

### Examples

```bash
# Multipart upload
curl -F 'file=@photo.jpg' http://localhost:8080/

# Multipart upload with authentication
curl -F 'file=@photo.jpg' \
  -H "Authorization: Bearer your-api-key" \
  http://localhost:8080/
```

---

## POST / (JSON — upload from URL)

Upload an image from a remote URL. The server fetches the URL, validates the content type and processes the bytes identically to a direct upload.

### Request

```
POST /
Content-Type: application/json

{"url": "https://example.com/photo.jpg"}
```

### Authentication

If `API_KEY` is set and `REQUIRE_API_KEY_FOR_UPLOAD=true`, the request must include:

```
Authorization: Bearer <your-api-key>
```

### Response — 201 Created

```json
{"filename": "3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp"}
```

### Error responses

| Status | Cause |
|--------|-------|
| 400 | `url` field missing or empty |
| 400 | Unsupported image format |
| 401 | Missing or invalid Bearer token |
| 422 | Remote URL returned non-2xx or is unreachable |
| 429 | Rate limit exceeded — `Retry-After` header included |

### Examples

```bash
# URL upload
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"url": "https://picsum.photos/800/600.jpg"}' \
  http://localhost:8080/

# URL upload with authentication
curl -X POST \
  -H "Authorization: Bearer your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://picsum.photos/800/600.jpg"}' \
  http://localhost:8080/
```

---

## GET /{filename}

Retrieve an image. Optionally resize it by providing `w` and/or `h` query parameters.

### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `filename` | path | yes | The filename returned by the upload endpoint. Format: `{uuidv4}.webp` |
| `w` | query | no | Target width in pixels |
| `h` | query | no | Target height in pixels |

### Resize behaviour

| `w` | `h` | Result |
|-----|-----|--------|
| provided | provided | Cover crop to exactly W×H pixels (aspect ratio preserved, image cropped to fill) |
| provided | omitted | Scale to width W, height calculated proportionally |
| omitted | provided | Scale to height H, width calculated proportionally |
| omitted | omitted | Original image returned unchanged |

### Cache behaviour

The response includes an `X-Cache` header:

| Value | Meaning |
|-------|---------|
| `HIT` | The variant was found in the in-memory index or on disk — no re-encoding was performed |
| `MISS` | The variant was generated during this request and written to disk for future requests |

On a cache HIT, the response also includes `Cache-Control: public, max-age=31536000, immutable`.  
On a cache MISS, the response includes `Cache-Control: public, max-age=3600`.

### Authentication

This endpoint is always public.

### Response — 200 OK

Binary WebP image. `Content-Type: image/webp`.

### Error responses

| Status | Cause |
|--------|-------|
| 400 | `w` or `h` value not in `VALID_SIZES` |
| 400 | Resize requested on an animated WebP (not supported) |
| 404 | Image not found |

### Examples

```bash
# Original image
curl http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp \
  --output photo.webp

# Resize to 320x240 (cover crop)
curl "http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp?w=320&h=240" \
  --output photo_320x240.webp

# Resize to width 640, proportional height
curl "http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp?w=640" \
  --output photo_640.webp

# Check cache header
curl -I "http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp?w=320&h=240"
# X-Cache: HIT  (on second request)
```

---

## DELETE /{filename}

Delete an image and all its cached resized variants.

### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `filename` | path | yes | The filename returned by the upload endpoint |

### Authentication

Required if `API_KEY` is set.

```
Authorization: Bearer <your-api-key>
```

### Response — 200 OK

```json
{
  "status": "deleted",
  "cached_files_removed": 3
}
```

`cached_files_removed` is the number of resized variant files that were deleted in addition to the original.

### Error responses

| Status | Cause |
|--------|-------|
| 401 | Missing or invalid Bearer token |
| 404 | Image not found |

### Example

```bash
curl -X DELETE \
  -H "Authorization: Bearer your-api-key" \
  http://localhost:8080/3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp
```

---

## GET /liveness

Liveness probe endpoint. Always returns 200. Never requires authentication. Used by Docker and Kubernetes health checkers.

### Response — 200 OK

```json
{"status": "ok"}
```

### Example

```bash
curl http://localhost:8080/liveness
# {"status":"ok"}
```

---

## GET /q/metrics

Exposes application metrics in Prometheus text format. Served by the [Micrometer](https://micrometer.io/) registry bundled with Quarkus.

### Authentication

This endpoint is always public.

### Response — 200 OK

Prometheus text exposition format (`text/plain; version=0.0.4`).

```
# HELP jvm_memory_used_bytes ...
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",...} 4.2e+07
...
```

### Example

```bash
curl http://localhost:8080/q/metrics
```

To scrape from Prometheus, add a job to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: webpserver
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: /q/metrics
```
