# webpserver — Technical Architecture

## Table of contents

1. [Overview](#1-overview)
2. [Technology stack](#2-technology-stack)
3. [Repository layout](#3-repository-layout)
4. [Java package structure](#4-java-package-structure)
5. [Configuration](#5-configuration)
6. [Domain model](#6-domain-model)
7. [Cache system](#7-cache-system)
8. [Image storage layout](#8-image-storage-layout)
9. [Components — detailed specification](#9-components--detailed-specification)
   - 9.1 [AppConfig](#91-appconfig)
   - 9.2 [ImageVariantKey](#92-imagevariantkey)
   - 9.3 [ImageEntry](#93-imageentry)
   - 9.4 [ConversionService](#94-conversionservice)
   - 9.5 [CacheService](#95-cacheservice)
   - 9.6 [ImageService](#96-imageservice)
   - 9.7 [AuthFilter](#97-authfilter)
   - 9.8 [RateLimitFilter](#98-ratelimitfilter)
   - 9.9 [LivenessResource](#99-livenessresource)
   - 9.10 [ImageResource](#910-imageresource)
10. [Request lifecycle](#10-request-lifecycle)
11. [Concurrency model](#11-concurrency-model)
12. [Multi-pod safety](#12-multi-pod-safety)
13. [Error handling](#13-error-handling)
14. [Testing strategy](#14-testing-strategy)
15. [Build system](#15-build-system)
16. [CI/CD pipeline](#16-cicd-pipeline)
17. [Cross-platform support](#17-cross-platform-support)

---

## 1. Overview

webpserver is a self-hosted, minimalist image hosting service. It exposes a small HTTP API to upload, serve and delete images. All images are normalised to the WebP format on ingest. Resized variants are generated on demand and cached on disk; subsequent requests for the same size are served from the disk cache without re-encoding.

The service is stateless in the sense that it does not require an external database. Consistency state (which images and variants exist) is stored on the filesystem and reflected in an in-memory index that is rebuilt from disk on startup.

---

## 2. Technology stack

| Component | Choice | Version | Rationale |
|-----------|--------|---------|-----------|
| Language | Java | 21 | LTS, virtual threads (JEP 444 stable) |
| Framework | Quarkus | latest stable (3.x) | Fast startup, reactive HTTP, excellent virtual thread support |
| HTTP layer | RESTEasy Reactive | bundled with Quarkus | Non-blocking I/O at the Netty level, JAX-RS API |
| Thread model | Virtual threads (`@RunOnVirtualThread`) | JDK 21 | JNI calls and disk I/O block the carrier thread. Virtual threads make this transparent and cheap |
| WebP library | webp4j-core | 2.1.1 | JNI wrapper of libwebp 1.6.0, ships native libs for linux/amd64 and linux/arm64 |
| JSON | Jackson | via `quarkus-resteasy-reactive-jackson` | Industry standard, zero config with Quarkus |
| OpenAPI UI | smallrye-openapi | via `quarkus-smallrye-openapi` | Exposes spec at `/q/openapi`, UI at `/q/swagger-ui` |
| Metrics | Micrometer + Prometheus | via `quarkus-micrometer-registry-prometheus` | Exposes `/q/metrics` |
| Tests | JUnit 5 + RestAssured | via `quarkus-junit5` | Integration tests against the running application |

**Maven coordinates for dependencies in `pom.xml`:**

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-resteasy-reactive</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-openapi</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>dev.matrixlab.webp4j</groupId>
  <artifactId>webp4j-core</artifactId>
  <version>2.1.1</version>
</dependency>
<!-- Test scope -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-junit5</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.rest-assured</groupId>
  <artifactId>rest-assured</artifactId>
  <scope>test</scope>
</dependency>
```

**Quarkus project creation command:**

```bash
quarkus create app io.webpserver:webpserver \
  --java=21 \
  --maven \
  --no-code \
  --extensions="resteasy-reactive,resteasy-reactive-jackson,smallrye-openapi,micrometer-registry-prometheus"
```

---

## 3. Repository layout

```
webpserver/
├── .github/
│   └── workflows/
│       ├── ci.yml            # Build and test on every push / PR
│       ├── release.yml       # Build multi-arch Docker image and push to GHCR on tag v*
│       └── docs.yml          # Deploy Jekyll docs to GitHub Pages on push to main
├── docs/                     # Jekyll documentation site
│   ├── _config.yml
│   ├── index.md
│   ├── endpoints.md
│   ├── architecture.md
│   ├── configuration.md
│   ├── docker.md
│   └── kubernetes.md
├── kubernetes/
│   └── deployment.yaml       # Kubernetes manifests (Namespace, Deployment, PVC, Service, Ingress example)
├── src/
│   ├── main/
│   │   ├── java/io/webpserver/
│   │   │   ├── config/
│   │   │   │   └── AppConfig.java
│   │   │   ├── model/
│   │   │   │   ├── ImageEntry.java
│   │   │   │   └── ImageVariantKey.java
│   │   │   ├── service/
│   │   │   │   ├── CacheService.java
│   │   │   │   ├── ConversionService.java
│   │   │   │   └── ImageService.java
│   │   │   ├── filter/
│   │   │   │   ├── AuthFilter.java
│   │   │   │   └── RateLimitFilter.java
│   │   │   └── resource/
│   │   │       ├── ImageResource.java
│   │   │       └── LivenessResource.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       ├── java/io/webpserver/
│       │   └── ImageApiIT.java
│       └── resources/
│           └── fixtures/
│               ├── sample.jpg
│               ├── sample.png
│               ├── sample.webp
│               └── sample.gif
├── docker-compose.yml
├── Dockerfile
├── openapi.yaml
├── ARCHITECTURE.md
└── pom.xml
```

---

## 4. Java package structure

Root package: `io.webpserver`

All classes live under this root. Sub-packages:

| Sub-package | Role |
|-------------|------|
| `config` | Quarkus `@ConfigMapping` or `@ConfigProperty` holder. Single class. |
| `model` | Plain Java records. No framework annotations. |
| `service` | Business logic. `@ApplicationScoped` CDI beans. |
| `filter` | JAX-RS `@ServerRequestFilter` implementations. |
| `resource` | JAX-RS `@Path` resource classes. |

---

## 5. Configuration

All configuration is read from environment variables (or `application.properties` for defaults).

| Environment variable | `application.properties` key | Type | Default | Description |
|---|---|---|---|---|
| `IMAGES_DIR` | `webpserver.images-dir` | `String` (path) | `/images` | Absolute path to the directory where images and variants are stored. Must be writable. |
| `API_KEY` | `webpserver.api-key` | `Optional<String>` | empty | If set, enables authentication for delete (and optionally upload). |
| `REQUIRE_API_KEY_FOR_UPLOAD` | `webpserver.require-api-key-for-upload` | `boolean` | `false` | When true, upload also requires the Bearer token. |
| `MAX_SIZE_MB` | `webpserver.max-size-mb` | `int` | `16` | Maximum allowed file size for uploads, in megabytes. |
| `VALID_SIZES` | `webpserver.valid-sizes` | `Optional<List<Integer>>` | empty (all allowed) | Comma-separated list of allowed pixel values for `w` and `h` query parameters. Example: `100,200,300,640,1280`. |
| `MAX_UPLOADS_PER_MINUTE` | `webpserver.max-uploads-per-minute` | `int` | `20` | Rate limit per IP per minute for upload requests. |
| `MAX_UPLOADS_PER_HOUR` | `webpserver.max-uploads-per-hour` | `int` | `100` | Rate limit per IP per hour. |
| `MAX_UPLOADS_PER_DAY` | `webpserver.max-uploads-per-day` | `int` | `1000` | Rate limit per IP per day. |
| `ALLOWED_ORIGINS` | `webpserver.allowed-origins` | `List<String>` | `["*"]` | CORS allowed origins. Configured via Quarkus `quarkus.http.cors.origins`. |
| `WEBP_QUALITY` | `webpserver.webp-quality` | `float` | `75.0` | Lossy WebP quality factor (0.0–100.0). Used for all conversions except GIF lossless. |
| `WEBP_LOSSLESS` | `webpserver.webp-lossless` | `boolean` | `false` | If true, all static images are encoded with lossless compression. |

`application.properties` defaults:

```properties
webpserver.images-dir=/images
webpserver.require-api-key-for-upload=false
webpserver.max-size-mb=16
webpserver.max-uploads-per-minute=20
webpserver.max-uploads-per-hour=100
webpserver.max-uploads-per-day=1000
webpserver.webp-quality=75.0
webpserver.webp-lossless=false

quarkus.http.port=8080
quarkus.http.cors=true
quarkus.smallrye-openapi.path=/q/openapi
```

---

## 6. Domain model

### `ImageVariantKey`

```java
package io.webpserver.model;

public record ImageVariantKey(int width, int height) {}
```

A value object representing a requested resize dimension pair. Both `width` and `height` are in pixels. The value `0` means "not specified" (aspect-ratio-preserved resize uses the other dimension). Used as a key in the per-image variant set.

- `width=320, height=240` — resized to exactly 320×240 (cover crop)
- `width=320, height=0` — resized to width 320, height calculated to preserve aspect ratio
- `width=0, height=240` — resized to height 240, width calculated to preserve aspect ratio

### `ImageEntry`

```java
package io.webpserver.model;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class ImageEntry {
    private final String uuid;
    private final Set<ImageVariantKey> variants;

    public ImageEntry(String uuid) {
        this.uuid = uuid;
        this.variants = new CopyOnWriteArraySet<>();
    }

    public String getUuid() { return uuid; }
    public Set<ImageVariantKey> getVariants() { return variants; }

    public void addVariant(ImageVariantKey key) {
        variants.add(key);
    }

    public boolean hasVariant(ImageVariantKey key) {
        return variants.contains(key);
    }
}
```

One `ImageEntry` per uploaded image. The `CopyOnWriteArraySet` is thread-safe for concurrent reads (dominant operation) with low write frequency (variant added once, never mutated).

---

## 7. Cache system

The cache is a two-level system:

### Level 1 — In-memory index

A `ConcurrentHashMap<String, ImageEntry>` held by `CacheService`. The key is the UUID string (without `.webp` extension).

- **Reads** (GET requests): O(1) lookup to determine if a variant exists.
- **Writes** (upload, first variant request): `putIfAbsent` and `entry.addVariant()`.
- **Deletes**: `remove(uuid)`.

### Level 2 — Disk

Files on the filesystem under `IMAGES_DIR`. The disk is the source of truth. The in-memory index is a projection of the disk state.

### Startup reconstruction

`CacheService` listens to `@Observes StartupEvent`. On startup it:

1. Opens `IMAGES_DIR` using `Files.walk(imagesDir, 1)` (depth 1, no recursion into subdirs).
2. Filters files matching the regex `^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?:_(\d+)x(\d+))?\.webp$`.
3. For each match:
   - Extract UUID from group 1.
   - If no group 2/3: register as `ImageEntry(uuid)` with no variants (the original file).
   - If group 2/3 present: parse `width` and `height`, call `entry.addVariant(new ImageVariantKey(w, h))`.
4. `.tmp` files (partial writes) are deleted during scan if found.

### Cache miss on GET (variant generation)

```
Request: GET /{uuid}.webp?w=W&h=H

Step 1: entry = map.get(uuid)
        if entry == null → 404

Step 2: key = new ImageVariantKey(W, H)
        if entry.hasVariant(key) → serve variantPath directly  [L1 HIT]

Step 3: variantPath = imagesDir/{uuid}_{W}x{H}.webp
        if Files.exists(variantPath) →
            entry.addVariant(key)          // lazy recovery after restart
            serve variantPath              [L2 HIT]

Step 4: originalBytes = Files.readAllBytes(imagesDir/{uuid}.webp)
        bufferedImage = WebPCodec.decodeImage(originalBytes)
        resized = resize(bufferedImage, W, H)    // see ConversionService
        encodedBytes = WebPCodec.encodeImage(resized, quality) or encodeLosslessImage(resized)
        tmpPath = imagesDir/{uuid}_{W}x{H}.tmp
        Files.write(tmpPath, encodedBytes)
        Files.move(tmpPath, variantPath, StandardCopyOption.ATOMIC_MOVE)
        entry.addVariant(key)
        serve variantPath                  [MISS → generated]
```

### Animated WebP detection

Before any resize, `ConversionService` calls `WebPCodec.getWebPInfo(bytes)` and checks the WebP file header for the `ANIM` chunk flag. If the image is animated, resize is refused with a descriptive error.

---

## 8. Image storage layout

```
/images/
├── 3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp        # original
├── 3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c_320x240.webp # variant 320×240
├── 3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c_640x0.webp   # variant width=640, height=proportional
├── a1b2c3d4-...webp
└── ...
```

Naming conventions:
- Original: `{uuidv4}.webp`
- Variant: `{uuidv4}_{width}x{height}.webp` where `0` means "not specified" for that dimension.
- Temp file: `{uuidv4}_{width}x{height}.tmp` (deleted or renamed atomically; never served).

The `.tmp` suffix is used during write operations. A file is only visible to serving code once the atomic rename to `.webp` succeeds. On startup, any `.tmp` file is a leftover from a crashed write and must be deleted.

---

## 9. Components — detailed specification

### 9.1 AppConfig

**File:** `src/main/java/io/webpserver/config/AppConfig.java`

```java
@ApplicationScoped
public class AppConfig {

    @ConfigProperty(name = "webpserver.images-dir", defaultValue = "/images")
    String imagesDir;

    @ConfigProperty(name = "webpserver.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "webpserver.require-api-key-for-upload", defaultValue = "false")
    boolean requireApiKeyForUpload;

    @ConfigProperty(name = "webpserver.max-size-mb", defaultValue = "16")
    int maxSizeMb;

    @ConfigProperty(name = "webpserver.valid-sizes")
    Optional<List<Integer>> validSizes;

    @ConfigProperty(name = "webpserver.max-uploads-per-minute", defaultValue = "20")
    int maxUploadsPerMinute;

    @ConfigProperty(name = "webpserver.max-uploads-per-hour", defaultValue = "100")
    int maxUploadsPerHour;

    @ConfigProperty(name = "webpserver.max-uploads-per-day", defaultValue = "1000")
    int maxUploadsPerDay;

    @ConfigProperty(name = "webpserver.webp-quality", defaultValue = "75.0")
    float webpQuality;

    @ConfigProperty(name = "webpserver.webp-lossless", defaultValue = "false")
    boolean webpLossless;

    // public getters for each field
}
```

Use standard `@ConfigProperty` (not `@ConfigMapping`) to keep it simple. All fields are package-private with public getters. `@ApplicationScoped` ensures a single instance for the lifespan of the application.

---

### 9.2 ImageVariantKey

See section 6. This is a Java `record` with no annotations.

Override `equals` and `hashCode` are provided automatically by `record`. The key is used inside `CopyOnWriteArraySet` — correctness depends on proper equality semantics.

---

### 9.3 ImageEntry

See section 6. Plain Java class, no framework annotations.

---

### 9.4 ConversionService

**File:** `src/main/java/io/webpserver/service/ConversionService.java`

**Responsibilities:**
- Detect and validate the input image format from raw bytes.
- Convert input bytes to WebP bytes for storage.
- Decode an existing WebP to `BufferedImage`.
- Resize a `BufferedImage`.
- Encode a `BufferedImage` to WebP bytes.
- Detect whether a WebP byte array is animated.

**CDI scope:** `@ApplicationScoped`

**Method specifications:**

```java
@ApplicationScoped
public class ConversionService {

    @Inject
    AppConfig config;
```

---

#### `detectFormat(byte[] bytes) : String`

Reads the first 12 bytes (magic bytes) to identify the format without relying on `Content-Type` headers (which can be spoofed).

| Format | Magic bytes | Detection rule |
|--------|-------------|----------------|
| JPEG | `FF D8 FF` | bytes[0]==0xFF && bytes[1]==0xD8 && bytes[2]==0xFF |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | bytes[0..7] match signature |
| GIF | `47 49 46 38` | bytes[0..3] == "GIF8" |
| WebP | `52 49 46 46 ?? ?? ?? ?? 57 45 42 50` | bytes[0..3]=="RIFF" && bytes[8..11]=="WEBP" |

Returns one of `"jpeg"`, `"png"`, `"gif"`, `"webp"`, or throws `UnsupportedFormatException` (a custom `WebApplicationException` with HTTP 400).

---

#### `toWebP(byte[] inputBytes, String format) : byte[]`

Converts raw input bytes to WebP bytes.

```
if format == "gif":
    return WebPCodec.encodeGifToWebP(inputBytes)
    // uses default GifToWebPConfig (lossy, quality from config, minimizeSize=true)
    // if config.isWebpLossless(): WebPCodec.encodeGifToWebPLossless(inputBytes)

if format == "webp":
    // re-encode to normalise quality (avoid storing arbitrary quality WebP files)
    BufferedImage img = WebPCodec.decodeImage(inputBytes)
    return encodeBufferedImage(img)

else (jpeg, png):
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(inputBytes))
    if img == null: throw UnsupportedFormatException
    return encodeBufferedImage(img)
```

---

#### `encodeBufferedImage(BufferedImage img) : byte[]`

```
if config.isWebpLossless():
    return WebPCodec.encodeLosslessImage(img)
else:
    return WebPCodec.encodeImage(img, config.getWebpQuality())
```

---

#### `decodeWebP(byte[] webpBytes) : BufferedImage`

```
return WebPCodec.decodeImage(webpBytes)
```

Throws `IOException` wrapped in a `RuntimeException` on failure. The caller (`ImageService`) maps this to HTTP 500.

---

#### `isAnimated(byte[] webpBytes) : boolean`

The WebP RIFF container includes an `ANIM` chunk for animated files. The chunk identifier appears at a fixed offset in a well-formed animated WebP.

Detection algorithm (without using webp4j — raw byte inspection):
1. Verify RIFF header (bytes 0–3 == `RIFF`, bytes 8–11 == `WEBP`).
2. Walk the chunk list starting at offset 12.
3. For each chunk: read 4-byte FourCC + 4-byte little-endian size.
4. If FourCC == `ANIM`: return `true`.
5. If FourCC == `VP8 ` or `VP8L` (space-padded): return `false` (static image found before ANIM).
6. Advance by `size` (rounded up to even). Repeat from step 3.
7. Default: return `false`.

This method must be fast (called on every resize request). It reads at most a few hundred bytes.

---

#### `resize(BufferedImage src, int targetWidth, int targetHeight) : BufferedImage`

Resize semantics:
- If `targetWidth > 0` and `targetHeight > 0`: **cover crop**.
  1. Compute scale factor to fill the target rectangle while preserving aspect ratio.
  2. Scale the image with `Image.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH)`.
  3. Draw on `BufferedImage(targetWidth, targetHeight, src.getType())`.
  4. Center-crop by drawing with a negative offset: `g.drawImage(scaled, -offsetX, -offsetY, null)`.
- If only `targetWidth > 0`: scale to width, compute height proportionally.
- If only `targetHeight > 0`: scale to height, compute width proportionally.

Always return a `BufferedImage` of type `TYPE_INT_RGB` (if source has no alpha) or `TYPE_INT_ARGB` (if source has alpha). This is important because webp4j's encoder treats RGB and RGBA differently.

---

### 9.5 CacheService

**File:** `src/main/java/io/webpserver/service/CacheService.java`

**Responsibilities:**
- Maintain the in-memory `ConcurrentHashMap<String, ImageEntry>`.
- Rebuild the map from disk at startup.
- Provide thread-safe read, write and delete operations on the map.

**CDI scope:** `@ApplicationScoped`

**Fields:**

```java
private final ConcurrentHashMap<String, ImageEntry> index = new ConcurrentHashMap<>();
private Path imagesDir;
```

**Startup (`void onStart(@Observes StartupEvent event)`):**

```
imagesDir = Path.of(config.getImagesDir())
Files.createDirectories(imagesDir)   // ensure it exists

Pattern FILENAME_PATTERN = Pattern.compile(
    "^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?:_(\\d+)x(\\d+))?\\.webp$"
)

try (Stream<Path> files = Files.list(imagesDir)) {
    files.forEach(path -> {
        String name = path.getFileName().toString()
        if (name.endsWith(".tmp")) {
            Files.deleteIfExists(path)   // clean up partial writes
            return
        }
        Matcher m = FILENAME_PATTERN.matcher(name)
        if (!m.matches()) return
        String uuid = m.group(1)
        ImageEntry entry = index.computeIfAbsent(uuid, ImageEntry::new)
        if (m.group(2) != null) {
            int w = Integer.parseInt(m.group(2))
            int h = Integer.parseInt(m.group(3))
            entry.addVariant(new ImageVariantKey(w, h))
        }
    })
}
```

**`registerImage(String uuid)`:**
```
index.putIfAbsent(uuid, new ImageEntry(uuid))
```

**`registerVariant(String uuid, ImageVariantKey key)`:**
```
ImageEntry entry = index.get(uuid)
if (entry != null) entry.addVariant(key)
```

**`getEntry(String uuid) : Optional<ImageEntry>`:**
```
return Optional.ofNullable(index.get(uuid))
```

**`removeEntry(String uuid) : Optional<ImageEntry>`:**
```
return Optional.ofNullable(index.remove(uuid))
```

**`getImagesDir() : Path`:**
```
return imagesDir
```

---

### 9.6 ImageService

**File:** `src/main/java/io/webpserver/service/ImageService.java`

**CDI scope:** `@ApplicationScoped`

**Dependencies:** `CacheService`, `ConversionService`, `AppConfig`

This is the main orchestration layer. All public methods are called from `ImageResource`. All I/O is blocking (disk reads/writes, JNI calls) and runs on virtual threads.

---

#### `UploadResult upload(byte[] bytes)`

```
1. Validate size: bytes.length > config.getMaxSizeMb() * 1024 * 1024 → throw FileTooLargeException (HTTP 400)

2. String format = conversionService.detectFormat(bytes)
   // throws UnsupportedFormatException on unknown format

3. byte[] webpBytes = conversionService.toWebP(bytes, format)

4. String uuid = UUID.randomUUID().toString()
   String filename = uuid + ".webp"
   Path filePath = cacheService.getImagesDir().resolve(filename)
   Files.write(filePath, webpBytes)
   // No ATOMIC_MOVE needed here: the file is new, no reader can reference it before
   // this method registers it in the index in the next step.

5. cacheService.registerImage(uuid)

6. return new UploadResult(filename)
```

`UploadResult` is an inner record: `record UploadResult(String filename)`.

---

#### `UploadResult uploadFromUrl(String url)`

```
1. HttpClient client = HttpClient.newBuilder()
       .followRedirects(HttpClient.Redirect.NORMAL)
       .connectTimeout(Duration.ofSeconds(10))
       .build()
   HttpRequest request = HttpRequest.newBuilder(URI.create(url))
       .timeout(Duration.ofSeconds(30))
       .GET()
       .build()
   HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray())
   // IOException or IllegalArgumentException → wrap in RemoteUrlException (HTTP 422)

2. if response.statusCode() < 200 or >= 300:
       throw new RemoteUrlException("Remote URL returned HTTP " + response.statusCode())

3. byte[] bytes = response.body()

4. delegate to upload(bytes)
```

---

#### `ServeResult serveImage(String filename, Integer w, Integer h)`

```
1. Parse UUID: strip ".webp" suffix. Validate format matches UUID pattern.
   → 400 if malformed

2. Optional<ImageEntry> entryOpt = cacheService.getEntry(uuid)
   → 404 if empty

3. if w == null && h == null:
       Path original = cacheService.getImagesDir().resolve(filename)
       return new ServeResult(Files.readAllBytes(original), false)

4. Validate VALID_SIZES: if config.getValidSizes() is present,
       both w (if not null) and h (if not null) must be in validSizes
       → 400 with descriptive message

5. ImageVariantKey key = new ImageVariantKey(w != null ? w : 0, h != null ? h : 0)

6. if entry.hasVariant(key):
       Path variantPath = buildVariantPath(uuid, key)
       return new ServeResult(Files.readAllBytes(variantPath), true)   // cache HIT

7. Path variantPath = buildVariantPath(uuid, key)
   if Files.exists(variantPath):
       cacheService.registerVariant(uuid, key)
       return new ServeResult(Files.readAllBytes(variantPath), true)   // disk HIT

8. Path originalPath = cacheService.getImagesDir().resolve(uuid + ".webp")
   byte[] originalBytes = Files.readAllBytes(originalPath)

9. if conversionService.isAnimated(originalBytes):
       throw new AnimatedResizeException()   // HTTP 400

10. BufferedImage original = conversionService.decodeWebP(originalBytes)
    BufferedImage resized = conversionService.resize(original, w != null ? w : 0, h != null ? h : 0)
    byte[] encoded = conversionService.encodeBufferedImage(resized)

11. Path tmpPath = buildTmpPath(uuid, key)
    Files.write(tmpPath, encoded)
    Files.move(tmpPath, variantPath, StandardCopyOption.ATOMIC_MOVE)

12. cacheService.registerVariant(uuid, key)

13. return new ServeResult(encoded, false)   // cache MISS
```

`ServeResult` is an inner record: `record ServeResult(byte[] bytes, boolean cacheHit)`.

**`buildVariantPath(String uuid, ImageVariantKey key) : Path`:**
```
return cacheService.getImagesDir().resolve(uuid + "_" + key.width() + "x" + key.height() + ".webp")
```

**`buildTmpPath(String uuid, ImageVariantKey key) : Path`:**
```
return cacheService.getImagesDir().resolve(uuid + "_" + key.width() + "x" + key.height() + ".tmp")
```

---

#### `DeleteResult deleteImage(String filename)`

```
1. Parse UUID from filename (strip ".webp"). Validate format.
   → 404 if malformed (no point revealing info)

2. Optional<ImageEntry> entryOpt = cacheService.removeEntry(uuid)
   → 404 if empty

3. ImageEntry entry = entryOpt.get()
   int removedCount = 0

4. for each ImageVariantKey key in entry.getVariants():
       Path variantPath = buildVariantPath(uuid, key)
       if Files.deleteIfExists(variantPath): removedCount++

5. Path originalPath = cacheService.getImagesDir().resolve(uuid + ".webp")
   Files.deleteIfExists(originalPath)

6. return new DeleteResult(removedCount)
```

`DeleteResult` is an inner record: `record DeleteResult(int cachedFilesRemoved)`.

---

### 9.7 AuthFilter

**File:** `src/main/java/io/webpserver/filter/AuthFilter.java`

**Responsibilities:** Validate the `Authorization: Bearer <token>` header on upload (conditional) and delete (always, if `API_KEY` set).

```java
@Provider
@ServerRequestFilter
public class AuthFilter {

    @Inject AppConfig config;
```

**Logic:**

```
if config.getApiKey() is empty:
    return   // no API key configured, auth disabled globally

path = request.getUriInfo().getPath()
method = request.getMethod()

requireAuth = false
if method == "DELETE": requireAuth = true
if method == "POST" && path == "/" && config.isRequireApiKeyForUpload(): requireAuth = true

if !requireAuth: return

header = request.getHeaderString("Authorization")
if header == null || !header.startsWith("Bearer "):
    abort with Response.status(401).entity(new ErrorResponse("Missing or invalid Authorization header.")).build()

token = header.substring("Bearer ".length()).strip()
if !token.equals(config.getApiKey().get()):
    abort with Response.status(401).entity(new ErrorResponse("Invalid API key.")).build()
```

Use `ContainerRequestContext.abortWith(Response)` to short-circuit the filter chain.

---

### 9.8 RateLimitFilter

**File:** `src/main/java/io/webpserver/filter/RateLimitFilter.java`

**Responsibilities:** Block excessive upload requests per IP using a sliding window algorithm.

Only applies to `POST /`.

**Data structure per IP:**

```java
private static class IpBucket {
    final Deque<Long> minuteTimestamps = new ArrayDeque<>();
    final Deque<Long> hourTimestamps   = new ArrayDeque<>();
    final Deque<Long> dayTimestamps    = new ArrayDeque<>();
}

private final ConcurrentHashMap<String, IpBucket> buckets = new ConcurrentHashMap<>();
```

**Sliding window logic (called with the current epoch millisecond `now` and the deque for a window of `windowMs`):**

```
evict timestamps older than (now - windowMs) from the front of the deque
if deque.size() >= limit: return false (rate limited)
deque.addLast(now)
return true (allowed)
```

**`checkAndRecord(String ip) : boolean`:**

```
IpBucket bucket = buckets.computeIfAbsent(ip, k -> new IpBucket())
synchronized (bucket):
    now = System.currentTimeMillis()
    if !slideWindow(bucket.minuteTimestamps, now, 60_000L, config.getMaxUploadsPerMinute()):
        return false
    if !slideWindow(bucket.hourTimestamps, now, 3_600_000L, config.getMaxUploadsPerHour()):
        return false
    if !slideWindow(bucket.dayTimestamps, now, 86_400_000L, config.getMaxUploadsPerDay()):
        return false
    return true
```

The `synchronized (bucket)` block ensures atomicity for a single IP across the three window checks. Different IPs are independent (no global lock).

**Filter application:**

```
if method != "POST" || path != "/": return

ip = request.getRemoteAddr()
if !rateLimitFilter.checkAndRecord(ip):
    abort with Response.status(429)
        .header("Retry-After", "60")
        .entity(new ErrorResponse("Upload rate limit exceeded. Try again later."))
        .build()
```

---

### 9.9 LivenessResource

**File:** `src/main/java/io/webpserver/resource/LivenessResource.java`

```java
@Path("/liveness")
@Produces(MediaType.APPLICATION_JSON)
public class LivenessResource {

    @GET
    @RunOnVirtualThread
    public Response liveness() {
        return Response.ok(Map.of("status", "ok")).build();
    }
}
```

No authentication. No rate limiting. Always returns 200. This is intentionally trivial.

---

### 9.10 ImageResource

**File:** `src/main/java/io/webpserver/resource/ImageResource.java`

```java
@Path("/")
public class ImageResource {

    @Inject ImageService imageService;
```

---

#### `POST /` — multipart upload

```java
@POST
@Consumes(MediaType.MULTIPART_FORM_DATA)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public Response uploadMultipart(@MultipartForm MultipartBody body) {
```

`MultipartBody` is a simple class annotated with `@RegisterForReflection`:
```java
public class MultipartBody {
    @FormParam("file")
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    public byte[] file;
}
```

Logic:
```
if body.file == null or body.file.length == 0:
    return 400 with error "No file provided."
ImageService.UploadResult result = imageService.upload(body.file)
return Response.ok(Map.of("filename", result.filename())).build()
```

---

#### `POST /` — URL upload

```java
@POST
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public Response uploadUrl(Map<String, String> body) {
```

Logic:
```
String url = body.get("url")
if url == null or url.isBlank():
    return 400 with error "No URL provided."
ImageService.UploadResult result = imageService.uploadFromUrl(url)
return Response.ok(Map.of("filename", result.filename())).build()
```

> Note: Quarkus RESTEasy Reactive routes `POST /` to one or the other method based on `Content-Type`. Both `@Consumes` annotations are on separate Java methods — JAX-RS content-type-based dispatch handles this correctly.

---

#### `GET /{filename}`

```java
@GET
@Path("/{filename}")
@Produces("image/webp")
@RunOnVirtualThread
public Response getImage(
    @PathParam("filename") String filename,
    @QueryParam("w") Integer w,
    @QueryParam("h") Integer h) {
```

Logic:
```
ImageService.ServeResult result = imageService.serveImage(filename, w, h)

CacheControl cc = new CacheControl()
if result.cacheHit():
    cc.setMaxAge(31536000)      // 1 year, immutable
    cc.setNoTransform(false)
    return Response.ok(result.bytes(), "image/webp")
        .cacheControl(cc)
        .header("X-Cache", "HIT")
        .build()
else:
    cc.setMaxAge(3600)
    return Response.ok(result.bytes(), "image/webp")
        .cacheControl(cc)
        .header("X-Cache", "MISS")
        .build()
```

---

#### `DELETE /{filename}`

```java
@DELETE
@Path("/{filename}")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public Response deleteImage(@PathParam("filename") String filename) {
```

Logic:
```
ImageService.DeleteResult result = imageService.deleteImage(filename)
return Response.ok(Map.of(
    "status", "deleted",
    "cached_files_removed", result.cachedFilesRemoved()
)).build()
```

---

#### Exception mapping

Define a single `@Provider` class `GlobalExceptionMapper implements ExceptionMapper<WebApplicationException>` that catches all custom exceptions (which extend `WebApplicationException`) and returns the appropriate JSON response body.

Custom exception classes (all in package `io.webpserver.exception`):

| Class | HTTP status | Message |
|---|---|---|
| `UnsupportedFormatException` | 400 | "Unsupported image format. Accepted: jpeg, png, webp, gif." |
| `FileTooLargeException` | 400 | "File size exceeds the X MB limit." |
| `InvalidSizeException` | 400 | "Requested size X is not in the list of valid sizes." |
| `AnimatedResizeException` | 400 | "Resizing animated WebP is not supported." |
| `ImageNotFoundException` | 404 | "Image not found." |
| `RemoteUrlException` | 422 | Dynamic message from constructor |

Each class has a constructor that calls `super(Response.status(statusCode).entity(new ErrorResponse(message)).type(MediaType.APPLICATION_JSON).build())`.

`ErrorResponse` is a simple record: `record ErrorResponse(String error)`.

---

## 10. Request lifecycle

### Upload (multipart, no auth required)

```
HTTP POST /  (multipart/form-data)
  │
  ├─ RateLimitFilter.filter()
  │    ├─ IP not in buckets → add entry, proceed
  │    └─ IP over limit → 429 + Retry-After header
  │
  ├─ AuthFilter.filter()
  │    ├─ REQUIRE_API_KEY_FOR_UPLOAD=false → skip
  │    └─ true → validate Bearer token
  │
  └─ ImageResource.uploadMultipart()   [virtual thread]
       │
       └─ ImageService.upload(bytes)
            ├─ validate size → FileTooLargeException (400)
            ├─ ConversionService.detectFormat(bytes)
            │    └─ UnsupportedFormatException (400)
            ├─ ConversionService.toWebP(bytes, format)   [JNI — libwebp]
            ├─ Files.write(path, webpBytes)
            ├─ CacheService.registerImage(uuid)
            └─ return UploadResult("uuid.webp")
                 └─ Response 200 {"filename": "uuid.webp"}
```

### GET with resize (cache miss)

```
HTTP GET /{uuid}.webp?w=320&h=240
  │
  └─ ImageResource.getImage()   [virtual thread]
       │
       └─ ImageService.serveImage(filename, 320, 240)
            ├─ CacheService.getEntry(uuid) → Optional[entry]
            ├─ validate VALID_SIZES → InvalidSizeException (400)
            ├─ key = ImageVariantKey(320, 240)
            ├─ entry.hasVariant(key) → false  [L1 miss]
            ├─ Files.exists(variantPath) → false  [L2 miss]
            ├─ Files.readAllBytes(originalPath)
            ├─ ConversionService.isAnimated(bytes) → false
            ├─ ConversionService.decodeWebP(bytes) → BufferedImage
            ├─ ConversionService.resize(img, 320, 240) → BufferedImage
            ├─ ConversionService.encodeBufferedImage(img) → byte[]  [JNI]
            ├─ Files.write(tmpPath, encoded)
            ├─ Files.move(tmpPath, variantPath, ATOMIC_MOVE)
            ├─ CacheService.registerVariant(uuid, key)
            └─ return ServeResult(encoded, false)
                 └─ Response 200 image/webp, X-Cache: MISS, Cache-Control: max-age=3600
```

---

## 11. Concurrency model

All JAX-RS resource methods are annotated with `@RunOnVirtualThread`. This is a Quarkus-specific annotation that instructs the framework to execute the method body on a JDK 21 virtual thread rather than a platform (carrier) thread from the RESTEasy Reactive worker pool.

**Why virtual threads and not Mutiny/Uni?**

- WebP encoding/decoding via JNI blocks the thread until the native code returns.
- Disk reads/writes block the thread.
- There is no non-blocking API for either operation.
- Virtual threads are designed for exactly this pattern: high concurrency with blocking I/O, without the complexity of reactive chaining.
- Using `Uni.runSubscriptionOn(executor)` would work but adds unnecessary verbosity. `@RunOnVirtualThread` is idiomatic in Quarkus 3.x for this use case.

**Thread safety guarantees:**

| Shared state | Access pattern | Mechanism |
|---|---|---|
| `ConcurrentHashMap<String, ImageEntry>` | Multi-reader, occasional writer | `ConcurrentHashMap` (lock-striped) |
| `ImageEntry.variants` (`CopyOnWriteArraySet`) | ~100% reads, rare writes | `CopyOnWriteArraySet` |
| `IpBucket` in `RateLimitFilter` | Per-IP reads+writes | `synchronized(bucket)` per IP |
| Disk variant files | Concurrent writes for same variant | `ATOMIC_MOVE` ensures no partial read |
| Disk original files | Write once, read many, delete possible | POSIX `unlink` semantics |

---

## 12. Multi-pod safety

When multiple replicas are deployed (e.g. on Kubernetes with a `ReadWriteMany` PVC), the following guarantees apply:

### No data corruption

Variant files are written atomically via `rename(2)`. If two pods simultaneously generate the same variant:
- Both encode the same image (deterministic output for the same quality setting).
- Both write to `.tmp` files with different temp names (can add PID or UUID suffix to `.tmp` if needed, but atomic rename makes it safe even with the same name).
- The second `ATOMIC_MOVE` overwrites the first with an identical file.
- No partial read is possible because the file is not visible under its final name until the rename completes.

### Eventual consistency of in-memory indexes

Pod A may create variant `uuid_320x240.webp`. Pod B does not know about it (different in-memory map). On the next request for that variant on Pod B, `Files.exists()` at step 3 of the cache miss flow will find the file and add it to Pod B's index. The index converges lazily.

### Delete safety

`DELETE /{filename}` removes the entry from the local pod's index. Other pods still hold the entry in their index. When they receive a subsequent `GET` request, `Files.exists()` will return false, and they will attempt to read the original which also no longer exists. The catch of `NoSuchFileException` in `serveImage` must propagate as a 404:

```java
try {
    byte[] bytes = Files.readAllBytes(originalPath);
} catch (NoSuchFileException e) {
    throw new ImageNotFoundException();
}
```

### Recommendation for production

Use a single replica (`replicas: 1`) with a `ReadWriteOnce` PVC for strong consistency. Multi-replica with `ReadWriteMany` (NFS, CephFS, Longhorn RWX) is supported with eventual consistency semantics as described above.

A future v2 option would add a distributed map (Redis) as level 1 cache to achieve strong consistency across pods.

---

## 13. Error handling

All exceptions in the `io.webpserver.exception` package extend `WebApplicationException` and carry the full JAX-RS `Response` in their constructor. RESTEasy Reactive catches these and sends the embedded response directly.

A fallback `@Provider ExceptionMapper<Throwable>` catches any unchecked exception that escapes the service layer and returns:

```json
{"error": "Internal server error."}
```

with HTTP 500. The actual exception is logged at `ERROR` level including the stack trace.

Logging uses the Quarkus/JBoss logger: `Logger.getLogger(ClassName.class)`. No additional logging framework is needed.

---

## 14. Testing strategy

**Framework:** JUnit 5 + RestAssured, run with `@QuarkusTest` (in-process, uses dev services) or `@QuarkusIntegrationTest` (against the packaged JAR).

**Test class:** `src/test/java/io/webpserver/ImageApiIT.java`

**Test `application.properties`** (`src/test/resources/application.properties`):

```properties
webpserver.images-dir=${java.io.tmpdir}/webpserver-test
webpserver.api-key=test-api-key
webpserver.require-api-key-for-upload=false
webpserver.max-size-mb=1
webpserver.valid-sizes=100,200,320,640
webpserver.max-uploads-per-minute=1000
webpserver.max-uploads-per-hour=10000
webpserver.max-uploads-per-day=100000
webpserver.webp-quality=75.0
webpserver.webp-lossless=false
```

**Test fixtures** in `src/test/resources/fixtures/`:
- `sample.jpg` — a small (< 100KB) JPEG test image
- `sample.png` — a small PNG test image with transparency
- `sample.webp` — a small static WebP file
- `sample.gif` — a small animated GIF
- `invalid.bin` — 100 bytes of random data

**Test cases:**

| # | Method | Path | Input | Expected |
|---|--------|------|-------|----------|
| 1 | POST | / | `sample.jpg` multipart | 200, `{"filename": "*.webp"}` |
| 2 | POST | / | `sample.png` multipart | 200, `{"filename": "*.webp"}` |
| 3 | POST | / | `sample.webp` multipart | 200, `{"filename": "*.webp"}` |
| 4 | POST | / | `sample.gif` multipart | 200, `{"filename": "*.webp"}` |
| 5 | POST | / | `invalid.bin` multipart | 400, `error` field present |
| 6 | POST | / | file > MAX_SIZE_MB | 400, error mentions size |
| 7 | POST | / | JSON `{"url": "...sample.jpg"}` | 200, `{"filename": "*.webp"}` |
| 8 | POST | / | JSON `{"url": "http://localhost:9999/nonexistent"}` | 422 |
| 9 | GET | /{filename} | valid filename, no params | 200, Content-Type: image/webp |
| 10 | GET | /{filename} | valid filename, `?w=320&h=200` | 200, image/webp, X-Cache: MISS |
| 11 | GET | /{filename} | repeat of test 10 | 200, image/webp, X-Cache: HIT |
| 12 | GET | /{filename} | `?w=320&h=200` after restart (index rebuilt) | 200, X-Cache: HIT (file found on disk) |
| 13 | GET | /{filename} | `?w=999` (not in VALID_SIZES) | 400 |
| 14 | GET | /{filename} | animated GIF uuid, `?w=100` | 400, "animated WebP" in error |
| 15 | GET | /nonexistent.webp | — | 404 |
| 16 | DELETE | /{filename} | valid filename, correct Bearer | 200, `{status: deleted, cached_files_removed: N}` |
| 17 | DELETE | /{filename} | valid filename, no Bearer | 401 |
| 18 | DELETE | /nonexistent.webp | correct Bearer | 404 |
| 19 | POST | / | over rate limit | 429, Retry-After header present |
| 20 | GET | /liveness | — | 200, `{"status": "ok"}` |

Test 12 requires starting a fresh `@QuarkusTest` context (or restarting the CDI container). This is tested by calling `CacheService.onStart()` directly after clearing the in-memory map in a `@BeforeEach`.

Test 19 requires lowering `MAX_UPLOADS_PER_MINUTE` to `2` in a specific test configuration profile.

---

## 15. Build system

The project uses **Maven** via the Quarkus Maven wrapper. No Gradle.

**Key Maven commands:**

```bash
./mvnw quarkus:dev                    # Dev mode with live reload
./mvnw test                           # Unit and integration tests
./mvnw verify                         # Full build including integration tests
./mvnw package                        # Build the runnable JAR (fast-jar)
./mvnw package -Dquarkus.package.type=uber-jar  # Single shaded JAR
```

**`Dockerfile`** (JVM mode, multi-arch compatible):

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /work
COPY --chown=1001 target/quarkus-app/lib/ /work/lib/
COPY --chown=1001 target/quarkus-app/*.jar /work/
COPY --chown=1001 target/quarkus-app/app/ /work/app/
COPY --chown=1001 target/quarkus-app/quarkus/ /work/quarkus/

RUN mkdir -p /images && chown 1001:1001 /images

EXPOSE 8080
USER 1001
VOLUME ["/images"]

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-XX:MaxRAMPercentage=75.0", \
  "--enable-preview", \
  "-jar", "/work/quarkus-run.jar"]
```

JVM flags rationale:
- `ZGC` with generational mode: lowest latency GC, ideal for a long-running web server under variable load. Avoids stop-the-world pauses during image encoding.
- `MaxRAMPercentage=75.0`: safe limit for containerised environments.
- `--enable-preview`: required for virtual threads in some Quarkus versions.

---

## 16. CI/CD pipeline

Three GitHub Actions workflows:

### `ci.yml` — continuous integration

```
Trigger: push to any branch, pull_request to main

Steps:
  1. actions/checkout@v4
  2. actions/setup-java@v4 (java-version: '21', distribution: 'temurin', cache: 'maven')
  3. ./mvnw verify -B
```

### `release.yml` — Docker image build and push

```
Trigger: push of tag matching v*.*.*

Steps:
  1. actions/checkout@v4
  2. actions/setup-java@v4 (java-version: '21', distribution: 'temurin', cache: 'maven')
  3. ./mvnw package -B -DskipTests
  4. docker/setup-qemu-action@v3             (needed for cross-arch)
  5. docker/setup-buildx-action@v3
  6. docker/login-action@v3
       registry: ghcr.io
       username: ${{ github.actor }}
       password: ${{ secrets.GITHUB_TOKEN }}
  7. docker/metadata-action@v5
       images: ghcr.io/${{ github.repository_owner }}/webpserver
       tags:
         type=semver,pattern={{version}}           → v1.2.3
         type=semver,pattern={{major}}.{{minor}}   → v1.2
         type=raw,value=latest
  8. docker/build-push-action@v5
       platforms: linux/amd64,linux/arm64
       push: true
       tags: ${{ steps.meta.outputs.tags }}
       labels: ${{ steps.meta.outputs.labels }}
```

The `linux/arm64` build runs via QEMU emulation on the GitHub-hosted `ubuntu-latest` runner. It is slower (~15min) but avoids the need for a self-hosted ARM runner.

### `docs.yml` — GitHub Pages deployment

```
Trigger: push to main (path filter: docs/**)

Permissions:
  contents: read
  pages: write
  id-token: write

Steps:
  1. actions/checkout@v4
  2. ruby/setup-ruby@v1 (ruby-version: '3.3', bundler-cache: true, working-directory: docs)
  3. Run: bundle exec jekyll build --source docs --destination docs/_site
  4. actions/upload-pages-artifact@v3 (path: docs/_site)
  5. actions/deploy-pages@v4
```

GitHub Pages must be configured in repository settings to use GitHub Actions as the source.

---

## 17. Cross-platform support

webp4j 2.1.1 ships the following native libraries bundled in the JAR:

| Platform | Architecture | Native lib |
|---|---|---|
| Linux | x86_64 | `libwebp4j-linux-x64.so` |
| Linux | aarch64 | `libwebp4j-linux-arm64.so` |
| macOS | x86_64 | `libwebp4j-macos-x64.dylib` |
| macOS | aarch64 | `libwebp4j-macos-arm64.dylib` |
| Windows | x86_64 | `webp4j-windows-x64.dll` |
| Windows | aarch64 | `webp4j-windows-arm64.dll` |

The library extracts the correct native file to a temp directory at runtime using platform detection. No developer action is required.

The Docker image built with `linux/amd64` and `linux/arm64` via `docker buildx` produces separate layers per-arch. The correct `.so` is selected at runtime. The JVM itself (`eclipse-temurin:21-jre`) is also multi-arch.

**ARM64 on GitHub Actions:**

The `release.yml` workflow uses `docker/setup-qemu-action` to enable QEMU-based ARM64 emulation. The build is slower but produces correct binaries. An alternative is to use GitHub's native ARM64 runners (`ubuntu-24.04-arm` in GA) to speed up the build — documented as an optional upgrade in `docs/configuration.md`.
