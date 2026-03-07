---
layout: page
title: Architecture
permalink: /architecture/
nav_order: 4
---

# Technical Architecture

---

## Technology choices

| Component | Technology | Why |
|-----------|-----------|-----|
| Language | Java 21 | LTS, virtual threads |
| Framework | Quarkus 3.x | Fast startup, reactive HTTP, first-class virtual thread support |
| HTTP layer | RESTEasy Reactive | Non-blocking I/O at the Netty level, JAX-RS API |
| Thread model | Virtual threads (`@RunOnVirtualThread`) | JNI and disk I/O are always blocking — virtual threads handle high concurrency without the complexity of reactive chaining |
| WebP library | webp4j-core 2.1.1 | JNI wrapper of Google libwebp 1.6.0, ships native binaries for linux/amd64 and linux/arm64 |

---

## Component overview

```
┌─────────────────────────────────────────────────────┐
│                    HTTP (Netty)                      │
└────────────────────┬────────────────────────────────┘
                     │
         ┌───────────▼───────────┐
         │  RateLimitFilter      │  per-IP sliding window (POST / only)
         └───────────┬───────────┘
                     │
         ┌───────────▼───────────┐
         │  AuthFilter           │  Bearer token validation
         └───────────┬───────────┘
                     │
         ┌───────────▼───────────┐
         │  ImageResource        │  JAX-RS, @RunOnVirtualThread
         └───────────┬───────────┘
                     │
         ┌───────────▼───────────┐
         │  ImageService         │  orchestration (upload, fetch, resize, delete)
         └──┬──────────────┬─────┘
            │              │
   ┌────────▼────┐  ┌──────▼────────┐
       │Conversion   │  │  CacheService │
   │Service      │  │               │
       │(webp4j/JNI) │  │ ImagesIndex   │
       └─────────────┘  │ + startup scan│
                    └──────┬────────┘
                           │
                    ┌──────▼────────┐
                    │  Filesystem   │
                    │  /images/     │
                    └───────────────┘
```

---

## Image conversion pipeline

Every upload goes through the same pipeline regardless of input format:

```
Input bytes
    │
    ├─ Magic byte detection (not Content-Type)
    │    JPEG / PNG / WebP / GIF
    │
       ├─ GIF → WebPCodec.encodeGifToWebP()   ← animated WebP preserved
       ├─ WebP → stored as-is                 ← no re-encode, animated WebP safe
       └─ JPEG/PNG → ImageIO.read() → encode
                                     │
                          WebPCodec.encodeImage(img, quality)
                          or encodeLosslessImage(img)
                                     │
                                                   {name}.webp written to disk
                                                   name registered in CacheService
```

The stored name is derived from the original upload name: lowercased, extension stripped, characters outside `[a-z0-9-.]` replaced with `-` (`FilenameUtils.sanitize`).

If the same name already exists, the upload is treated as a duplicate and no new file is written.

---

## Cache system

The cache has two levels:

**Level 1 — In-memory index**

`CacheService` holds an `ImagesIndex` backed by a `ConcurrentHashMap<String, ImageEntry>`. Each key is the sanitized name (e.g. `my-photo`); each value is an `ImageEntry` with a `HashSet<ImageVariant>` (including the original). Lookups are O(1). The index is rebuilt from disk on startup by scanning `IMAGES_DIR` and parsing filenames.

**Level 2 — Disk**

Variant files are named `{name}_{w}x{h}.webp`. The disk is the source of truth. If the in-memory index is missing an entry (e.g. after a crash or a multi-pod write), `Files.exists()` provides a fallback before re-encoding.

**Lookup order on GET with resize:**

```
1. In-memory index hasVariant(w, h)? → serve file  [L1 HIT]
2. Files.exists(variantPath)?        → register in index, serve file  [L2 HIT]
3. Decode original → resize → encode → write → register → serve  [MISS]
```

The `X-Cache: HIT / MISS` response header indicates which path was taken.

---

## Write behavior and multi-pod safety

Original images and resized variants are written directly with `Files.write()` to their final `.webp` path.

In-memory indexes across pods converge lazily: if pod B is missing a variant created by pod A, the `Files.exists()` check at step 2 above catches it on the next request.

---

## Resize behavior

- Both `w` and `h`: cover crop to the exact size (scale + center crop).
- Only one dimension: keep aspect ratio.
- `VALID_SIZES` (if set) restricts accepted values for `w` and `h`.
- Animated WebP (converted from GIF) cannot be resized; the server returns HTTP 400.

---

## Upload sources

- Multipart upload via `POST /` reads the file from disk and converts to WebP.
- URL upload via `POST /` with JSON fetches remote bytes using Java `HttpClient` (10s connect, 30s request timeout) before conversion.

---

## Platform support

webp4j ships native libraries for `linux/amd64` and `linux/arm64` inside the JAR. The correct library is extracted and loaded at runtime. No platform-specific configuration is needed.
