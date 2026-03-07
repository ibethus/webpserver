---
layout: page
title: API Endpoints
permalink: /endpoints/
nav_order: 2
---

# API Endpoints

The full machine-readable contract is available as an [OpenAPI 3.1 specification](https://github.com/ibethus/webpserver/blob/main/openapi.yaml),
or browse it interactively via the [Swagger UI](/webpserver/api/).

---

## Cache header

All GET requests include an `X-Cache` header:

| Value | Meaning |
|-------|---------|
| `HIT` | The variant was found in the in-memory index or on disk — no re-encoding was performed |
| `MISS` | The variant was generated during this request and written to disk for future requests |

On a cache HIT, the response also includes `Cache-Control: public, max-age=31536000, immutable`.  
On a cache MISS, the response includes `Cache-Control: public, max-age=3600`.

---

## Duplicate images

When uploading an image, if a file with the same sanitized name already exists on disk, the server returns HTTP 200 with `alreadyPresent: true`. No new file is written.

```json
{"filename": "photo.webp", "alreadyPresent": true}
```

The filename stored on disk is derived from the original filename: the extension is stripped, the name is lowercased, and any character outside `[a-z0-9-.]` is replaced with `-`. For example `My Photo_2024.JPG` becomes `my-photo-2024.webp`.
