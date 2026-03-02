package io.webpserver.service;

import io.webpserver.config.AppConfig;
import io.webpserver.exception.AnimatedResizeException;
import io.webpserver.exception.FileTooLargeException;
import io.webpserver.exception.ImageNotFoundException;
import io.webpserver.exception.InvalidSizeException;
import io.webpserver.exception.RemoteUrlException;
import io.webpserver.model.ImageEntry;
import io.webpserver.model.ImageVariantKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ImageService {

    private static final Logger LOG = Logger.getLogger(ImageService.class);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String WEBP_EXTENSION = ".webp";
    private static final String TMP_EXTENSION = ".tmp";

    private final CacheService cacheService;
    private final ConversionService conversionService;
    private final AppConfig config;

    @Inject
    public ImageService(CacheService cacheService, ConversionService conversionService, AppConfig config) {
        this.cacheService = cacheService;
        this.conversionService = conversionService;
        this.config = config;
    }

    public UploadResult upload(byte[] bytes) throws IOException {
        long maxBytes = (long) config.maxSizeMb() * 1024 * 1024;
        if (bytes.length > maxBytes) {
            LOG.warnf("Upload rejected: file size %d exceeds limit of %d bytes", bytes.length, maxBytes);
            throw new FileTooLargeException((int) config.maxSizeMb());
        }

        String format = conversionService.detectFormat(bytes);
        LOG.debugf("Uploading %s image (%d bytes)", format, bytes.length);

        byte[] webpBytes = conversionService.toWebP(bytes, format);

        String uuid = UUID.randomUUID().toString();
        String filename = uuid + WEBP_EXTENSION;
        Path filePath = cacheService.getImagesDir().resolve(filename);
        Files.write(filePath, webpBytes);

        cacheService.registerImage(uuid);
        LOG.infof("Uploaded image: %s (%d bytes -> %d bytes WebP)", filename, bytes.length, webpBytes.length);

        return new UploadResult(filename);
    }

    public UploadResult uploadFromUrl(String url) throws IOException {
        LOG.infof("Fetching image from URL: %s", url);
        byte[] remoteBytes;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(HTTP_CONNECT_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("Remote URL returned HTTP %d for: %s", response.statusCode(), url);
                throw new RemoteUrlException("Remote URL returned HTTP " + response.statusCode());
            }

            remoteBytes = response.body();
        } catch (RemoteUrlException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            LOG.warnf(e, "Invalid URL provided: %s", url);
            throw new RemoteUrlException("Invalid URL: " + e.getMessage());
        } catch (InterruptedException e) {
            LOG.errorf(e, "Request interrupted for URL: %s", url);
            Thread.currentThread().interrupt();
            throw new RemoteUrlException("Request to remote URL was interrupted");
        } catch (IOException e) {
            LOG.warnf(e, "Failed to fetch remote URL: %s", url);
            throw new RemoteUrlException("Failed to fetch URL: " + e.getMessage());
        }
        return upload(remoteBytes);
    }

    public ServeResult serveImage(String filename, Integer w, Integer h) throws IOException {
        String uuid = filename.replace(WEBP_EXTENSION, "");
        if (!isValidUuid(uuid)) {
            throw new ImageNotFoundException();
        }

        Optional<ImageEntry> entryOpt = cacheService.getEntry(uuid);
        if (entryOpt.isEmpty()) {
            throw new ImageNotFoundException();
        }

        ImageEntry entry = entryOpt.get();

        if (w == null && h == null) {
            Path originalPath = cacheService.getImagesDir().resolve(filename);
            try {
                byte[] bytes = Files.readAllBytes(originalPath);
                LOG.debugf("Served original: %s", filename);
                return new ServeResult(bytes, false);
            } catch (NoSuchFileException e) {
                LOG.warnf("Original file missing from disk for uuid: %s", uuid);
                throw new ImageNotFoundException();
            }
        }

        if (config.validSizes().isPresent()) {
            var validSizes = config.validSizes().get();
            if (w != null && !validSizes.contains(w)) {
                throw new InvalidSizeException(w);
            }
            if (h != null && !validSizes.contains(h)) {
                throw new InvalidSizeException(h);
            }
        }

        ImageVariantKey key = new ImageVariantKey(w != null ? w : 0, h != null ? h : 0);

        if (entry.hasVariant(key)) {
            Path variantPath = buildVariantPath(uuid, key);
            byte[] bytes = Files.readAllBytes(variantPath);
            LOG.debugf("Cache HIT (in-memory): %s variant %dx%d", uuid, key.width(), key.height());
            return new ServeResult(bytes, true);
        }

        Path variantPath = buildVariantPath(uuid, key);
        if (Files.exists(variantPath)) {
            cacheService.registerVariant(uuid, key);
            byte[] bytes = Files.readAllBytes(variantPath);
            LOG.debugf("Cache HIT (disk fallback): %s variant %dx%d", uuid, key.width(), key.height());
            return new ServeResult(bytes, true);
        }

        LOG.debugf("Cache MISS: generating variant %dx%d for %s", key.width(), key.height(), uuid);

        Path originalPath = cacheService.getImagesDir().resolve(uuid + WEBP_EXTENSION);
        byte[] originalBytes;
        try {
            originalBytes = Files.readAllBytes(originalPath);
        } catch (NoSuchFileException e) {
            LOG.warnf("Original file missing for resize, uuid: %s", uuid);
            throw new ImageNotFoundException();
        }

        if (conversionService.isAnimated(originalBytes)) {
            throw new AnimatedResizeException();
        }

        BufferedImage original = conversionService.decodeWebP(originalBytes);
        BufferedImage resized = conversionService.resize(original, w != null ? w : 0, h != null ? h : 0);
        byte[] encoded = conversionService.encodeBufferedImage(resized);

        Path tmpPath = buildTmpPath(uuid, key);
        Files.write(tmpPath, encoded);
        Files.move(tmpPath, variantPath, StandardCopyOption.ATOMIC_MOVE);

        cacheService.registerVariant(uuid, key);
        LOG.infof("Generated and cached variant: %s_%dx%d", uuid, key.width(), key.height());

        return new ServeResult(encoded, false);
    }

    public DeleteResult deleteImage(String filename) throws IOException {
        String uuid = filename.replace(WEBP_EXTENSION, "");
        if (!isValidUuid(uuid)) {
            throw new ImageNotFoundException();
        }

        Optional<ImageEntry> entryOpt = cacheService.removeEntry(uuid);
        if (entryOpt.isEmpty()) {
            throw new ImageNotFoundException();
        }

        ImageEntry entry = entryOpt.get();
        int removedCount = 0;

        for (ImageVariantKey key : entry.getVariants()) {
            Path variantPath = buildVariantPath(uuid, key);
            if (Files.deleteIfExists(variantPath)) {
                removedCount++;
                LOG.debugf("Deleted variant: %s_%dx%d", uuid, key.width(), key.height());
            }
        }

        Path originalPath = cacheService.getImagesDir().resolve(uuid + WEBP_EXTENSION);
        Files.deleteIfExists(originalPath);
        LOG.infof("Deleted image: %s (%d variants removed)", filename, removedCount);

        return new DeleteResult("deleted", removedCount);
    }

    private Path buildVariantPath(String uuid, ImageVariantKey key) {
        return cacheService.getImagesDir()
                .resolve(uuid + "_" + key.width() + "x" + key.height() + WEBP_EXTENSION);
    }

    private Path buildTmpPath(String uuid, ImageVariantKey key) {
        return cacheService.getImagesDir()
                .resolve(uuid + "_" + key.width() + "x" + key.height() + TMP_EXTENSION);
    }

    private boolean isValidUuid(String uuid) {
        return uuid.matches("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$");
    }

    public record UploadResult(
        @Schema(description = "Generated filename of the stored WebP image.",
                example = "3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp")
        String filename) {}

    public record ServeResult(byte[] bytes, boolean cacheHit) {}

    public record DeleteResult(
        @Schema(description = "Deletion status.", example = "deleted", enumeration = {"deleted"})
        String status,
        @Schema(description = "Number of resized variant files that were also deleted.", example = "3")
        int cachedFilesRemoved) {}
}
