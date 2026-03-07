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
         │  RateLimitFilter      │  per-IP sliding window
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
         │  ImageService         │  orchestration
         └──┬──────────────┬─────┘
            │              │
   ┌────────▼────┐  ┌──────▼────────┐
   │Conversion   │  │  CacheService │
   │Service      │  │               │
   │(webp4j/JNI) │  │ ImagesIndex   │
   └─────────────┘  │ + disk scan   │
                    └──────┬────────┘
                           │
                    ┌──────▼────────┐
                    │  Filesystem   │
                    │  /images/     │
                    └───────────────┘
```

---

## Image conversion pipeline

Every uploaded image goes through the same pipeline regardless of input format:

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
                              {stem}.webp written to disk
                              stem registered in CacheService
```

The filename stem is derived from the original upload name: lowercased, extension stripped, characters outside `[a-z0-9-.]` replaced with `-` (`FilenameUtils.sanitize`).

---

## Cache system

The cache has two levels:

**Level 1 — In-memory index**

`CacheService` holds an `ImagesIndex` which wraps a `ConcurrentHashMap<String, ImageEntry>`. Each key is a filename stem (e.g. `my-photo`); each value is an `ImageEntry` holding a `HashSet<ImageVariant>` of known variants (including the original). Lookups are O(1). The index is rebuilt from disk on every application startup by scanning `IMAGES_DIR` and parsing filenames.

**Level 2 — Disk**

Variant files are named `{stem}_{w}x{h}.webp`. The disk is the source of truth. If the in-memory index is missing an entry (e.g. after a crash or a multi-pod write), `Files.exists()` provides a fallback before re-encoding.

**Lookup order on GET with resize:**

```
1. In-memory index hasVariant(w, h)? → serve file  [L1 HIT]
2. Files.exists(variantPath)?        → register in index, serve file  [L2 HIT]
3. Decode original → resize → encode → write (ATOMIC_MOVE) → register → serve  [MISS]
```

The `X-Cache: HIT / MISS` response header indicates which path was taken.

---

## Atomic writes and multi-pod safety

Original images are written directly with `Files.write()`. Resized **variant** files are always written through a `.tmp` intermediate file:

```
encode → write {stem}_{w}x{h}.tmp → Files.move(..., ATOMIC_MOVE) → {stem}_{w}x{h}.webp
```

`ATOMIC_MOVE` maps to `rename(2)` on Linux. A variant file is never visible under its final name until the write is complete. Two pods writing the same variant concurrently both succeed — the second rename overwrites the first with an identical file. No corruption, no partial reads.

In-memory indexes across pods converge lazily: if pod B is missing a variant created by pod A, the `Files.exists()` check at step 2 above catches it on the next request.

---

## Platform support

webp4j ships native libraries for `linux/amd64` and `linux/arm64` inside the JAR. The correct library is extracted and loaded at runtime. No platform-specific configuration is needed.
