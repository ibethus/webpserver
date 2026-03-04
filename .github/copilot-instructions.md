# Copilot Instructions — webpserver

## Project overview

Self-hosted WebP image server. Java 21 + Quarkus 3.x + webp4j (JNI/libwebp). No database, no front-end.
API contract: `openapi.yaml` (OpenAPI 3.1).

## Architecture in one paragraph

Every upload is immediately converted to WebP via JNI (webp4j-core 2.1.1) and stored as `{uuid}.webp`.
Resized variants are generated on first GET and cached as `{uuid}_{w}x{h}.webp`. An in-memory
`ConcurrentHashMap<String, ImageEntry>` (the "index") is rebuilt from disk at startup by scanning
filenames with regex. No distributed state, no Redis in v1.

## Package layout

```
io.webpserver
  config/    AppConfig.java          — @ConfigProperty bindings only
  model/     ImageVariantKey.java    — record(int width, int height)
             ImageEntry.java         — UUID + CopyOnWriteArraySet<ImageVariantKey>
  service/   ConversionService.java  — webp4j calls, magic-byte detection, resize
             CacheService.java       — in-memory index + startup disk scan
             ImageService.java       — orchestrates the above two
  filter/    AuthFilter.java         — @ServerRequestFilter, Bearer token
             RateLimitFilter.java    — @ServerRequestFilter, sliding window per IP
  resource/  ImageResource.java      — POST /, GET /{filename}, DELETE /{filename}
             LivenessResource.java   — GET /liveness
  exception/ *Exception.java         — extend WebApplicationException, carry HTTP status
```

## Critical patterns

**Every resource method must use `@RunOnVirtualThread`.** JNI (webp4j) and disk I/O are blocking;
reactive Uni/Multi is not used anywhere. Do not add Mutiny chaining.

**Image format is detected from magic bytes, never from Content-Type:**
- JPEG: `bytes[0]==0xFF && bytes[1]==0xD8 && bytes[2]==0xFF`
- PNG: bytes 0–7 match PNG signature
- GIF: bytes 0–3 == `GIF8`
- WebP: bytes 0–3 == `RIFF` and bytes 8–11 == `WEBP`

**Variant writes are always atomic:**
```java
Files.write(tmpPath, encoded);                          // write .tmp
Files.move(tmpPath, variantPath, ATOMIC_MOVE);          // atomic rename
```
Never write directly to the final `.webp` path. Never serve `.tmp` files.

**Cache lookup order in `ImageService.serveImage()`:**
1. `entry.hasVariant(key)` (in-memory) → serve file
2. `Files.exists(variantPath)` (disk fallback) → register + serve
3. Encode → atomic write → register → serve

**Animated WebP detection** is done by walking the RIFF chunk list looking for the `ANIM` FourCC
(pure byte inspection, no webp4j call). Animated images cannot be resized (return HTTP 400).

**`ImageVariantKey(0, 0)` is never valid.** `0` means "not specified" for that dimension only
(e.g. `(320, 0)` = scale to width 320, preserve aspect ratio). Both `w=0` and `h=0` together
should never reach `serveImage`.

## Error handling

All custom exceptions live in `io.webpserver.exception`, extend `WebApplicationException`, and
embed the full JAX-RS `Response` (including JSON body) in their constructor. Do not return
`Response` objects directly from service methods — throw typed exceptions instead.
A single fallback `ExceptionMapper<Throwable>` returns HTTP 500 for anything unexpected.

## Configuration

All env vars are prefixed `WEBPSERVER_` (Quarkus auto-converts to `webpserver.*` properties).
Read them only through `AppConfig` — never with `System.getenv()` directly.
`VALID_SIZES` is `Optional<List<Integer>>`; if absent, any size is accepted.
`API_KEY` is `Optional<String>`; if absent, auth is globally disabled.

## Build and test commands

```bash
./mvnw quarkus:dev          # dev mode, live reload, swagger-ui at /q/swagger-ui
./mvnw test                 # unit tests
./mvnw verify               # full build including integration tests (required before PR)
./mvnw package -DskipTests  # build quarkus-app/ fast-jar for Docker
```

Test config overrides are in `src/test/resources/application.properties`.
Test fixtures (sample.jpg, sample.png, sample.webp, sample.gif, invalid.bin) live in
`src/test/resources/fixtures/`.

## CI/CD

- Push any branch → `ci.yml` runs `./mvnw verify`
- Push tag `v*.*.*` → `release.yml` builds multi-arch image (`linux/amd64,linux/arm64` via QEMU)
  and pushes to `ghcr.io/{owner}/webpserver`
- Push to `main` touching `docs/**` → `docs.yml` deploys Jekyll site to GitHub Pages

## Conventional Commits

All commits follow [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>[(<scope>)]: <description>

[optional body]

[optional footer(s)]
```

**Types:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`, `build`, `ci`.

**Scope examples:** `config`, `model`, `service`, `filter`, `resource`, `cache`, `conversion`, `test`.

**Examples:**

```
feat(resource): implement POST / multipart upload endpoint
feat(service): add animated WebP detection in ConversionService
fix(cache): handle NoSuchFileException on concurrent delete
test(integration): add 20 test cases for image lifecycle
docs(architecture): clarify multi-pod consistency guarantees
```

## Do not

- Add a front-end, database, or message broker
- Use `Uni`/`Multi` (Mutiny) — virtual threads are the concurrency model
- Write log comments or emoji in code
- Use `System.getenv()` directly — use `AppConfig`
- Serve a file before its atomic rename completes
- Resize animated WebP (throw `AnimatedResizeException`, document as v1 limitation)
