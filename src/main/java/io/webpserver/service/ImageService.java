package io.webpserver.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.webpserver.config.AppConfig;
import io.webpserver.exception.*;
import io.webpserver.model.ImageEntry;
import io.webpserver.model.ImageVariant;
import jakarta.annotation.Nonnull;
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

@ApplicationScoped
public class ImageService {

    private static final Logger LOG = Logger.getLogger(ImageService.class);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String WEBP_EXTENSION = ".webp";
    private static final String TMP_EXTENSION = ".tmp";
    private static final ImageVariant ORIGINAL_KEY = new ImageVariant();

    private final CacheService cacheService;
    private final ConversionService conversionService;
    private final AppConfig config;

    @Inject
    public ImageService(CacheService cacheService, ConversionService conversionService, AppConfig config) {
        this.cacheService = cacheService;
        this.conversionService = conversionService;
        this.config = config;
    }

    public UploadResult upload(byte[] bytes, String rawOriginalName) throws IOException {
        long maxBytes = (long) config.maxSizeMb() * 1024 * 1024;
        if (bytes.length > maxBytes) {
            LOG.warnf("Upload rejected: file size %d exceeds limit of %d bytes", bytes.length, maxBytes);
            throw new FileTooLargeException((int) config.maxSizeMb());
        }
        String format = conversionService.detectFormat(bytes);
        String stem = FilenameUtils.sanitize(rawOriginalName);
        if (cacheService.getEntry(stem).isPresent()) {
            LOG.infof("Duplicate upload detected: %s already exists", stem);
            return new UploadResult(stem + WEBP_EXTENSION, true);
        }
        LOG.infof("Uploading %s image (%d bytes) as %s", format, bytes.length, stem);
        String filename = stem + WEBP_EXTENSION;
        Path filePath = cacheService.getImagesDir().resolve(filename);
        byte[] webpBytes = conversionService.toWebP(bytes, format);
        Files.write(filePath, webpBytes);
        cacheService.registerImage(stem);
        int compressionPct = (int) Math.round(100.0 * (bytes.length - webpBytes.length) / bytes.length);
        LOG.infof("Uploaded image: %s (%d bytes -> %d bytes WebP, %d%%)", filename, bytes.length, webpBytes.length, compressionPct);
        return new UploadResult(filename, false);
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
        } catch (IllegalArgumentException e) {
            LOG.warnf(e, "Invalid URL provided: %s", url);
            throw new RemoteUrlException("Invalid URL: " + e.getMessage());
        } catch (InterruptedException e) {
            LOG.errorf(e, "Request interrupted for URL: %s", url);
            Thread.currentThread().interrupt();
            throw new RemoteUrlException("Request to remote URL was interrupted");
        } catch (IOException e) {
            LOG.warnf(e, "Failed to fetch remote URL: %s", url);
            throw new RemoteUrlException("Failed to fetch URL: " + e.getMessage(), 422);
        }
        String urlPath = URI.create(url).getPath();
        String lastSegment = urlPath.substring(urlPath.lastIndexOf('/') + 1);
        if (lastSegment.isEmpty()) {
            lastSegment = "image";
        }
        return upload(remoteBytes, lastSegment);
    }

    public ServeResult serveImage(String inputFilename, Integer w, Integer h) throws IOException {
        String uuid = FilenameUtils.getFilename(inputFilename);
        checkProvidedSizes(w, h);
        ImageEntry entry = cacheService.getEntry(uuid)
                .orElseGet(ImageEntry::new);
        try {
            if (w == null && h == null) {
                return serveOriginal(inputFilename, uuid, entry);
            } else {
                return serveVariant(w, h, entry, uuid);
            }
        } catch (IOException e) {
            throw new ImageNotFoundException();
        }

    }

    @Nonnull
    private ServeResult serveVariant(Integer w, Integer h, ImageEntry entry, String filename) throws IOException {
        ImageVariant key = new ImageVariant(w != null ? w : 0, h != null ? h : 0);
        if (entry.hasVariant(key)) {
            Path variantPath = buildVariantPath(filename, key);
            byte[] bytes = Files.readAllBytes(variantPath);
            LOG.infof("Cache HIT (in-memory): %s variant %dx%d", filename, key.getWidth(), key.getHeight());
            return new ServeResult(bytes, true);
        }

        Path variantPath = buildVariantPath(filename, key);
        if (Files.exists(variantPath)) {
            cacheService.registerVariant(filename, key);
            byte[] bytes = Files.readAllBytes(variantPath);
            LOG.infof("Cache HIT (disk fallback): %s variant %dx%d", filename, key.getWidth(), key.getHeight());
            return new ServeResult(bytes, true);
        }
        LOG.infof("Cache MISS: generating variant %dx%d for %s", key.getWidth(), key.getHeight(), filename);

        byte[] encoded = registerNewVariant(w, h, filename, key, variantPath);

        return new ServeResult(encoded, false);
    }

    private byte[] registerNewVariant(Integer w, Integer h, String uuid, ImageVariant key, Path variantPath) throws IOException {
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
        LOG.infof("Generated and cached variant: %s_%dx%d", uuid, key.getWidth(), key.getHeight());
        return encoded;
    }

    @Nonnull
    private ServeResult serveOriginal(String inputFilename, String uuid, ImageEntry entry) throws IOException {
        Path originalPath = cacheService.getImagesDir().resolve(inputFilename);

        if (entry.hasVariant(ORIGINAL_KEY)) {
            byte[] bytes = Files.readAllBytes(originalPath);
            LOG.infof("Cache HIT (in-memory): %s original", uuid);
            return new ServeResult(bytes, true);
        }

        if (Files.exists(originalPath)) {
            cacheService.registerImage(uuid);
            byte[] bytes = Files.readAllBytes(originalPath);
            LOG.infof("Cache MISS: %s original", uuid);
            return new ServeResult(bytes, false);
        }

        LOG.warnf("Original file missing from disk for: %s", uuid);
        throw new ImageNotFoundException();
    }

    private void checkProvidedSizes(Integer w, Integer h) {
        if (config.validSizes().isPresent()) {
            var validSizes = config.validSizes().get();
            if (w != null && !validSizes.contains(w)) {
                throw new InvalidSizeException(w);
            }
            if (h != null && !validSizes.contains(h)) {
                throw new InvalidSizeException(h);
            }
        }
    }

    public DeleteResult deleteImage(String filename) throws IOException {
        String uuid = FilenameUtils.getFilename(filename);
        Optional<ImageEntry> entryOpt = cacheService.removeEntry(uuid);
        if (entryOpt.isEmpty()) {
            throw new ImageNotFoundException();
        }

        ImageEntry entry = entryOpt.get();
        int removedCount = 0;

        for (ImageVariant key : entry.getVariants()) {
            Path variantPath = buildVariantPath(uuid, key);
            if (Files.deleteIfExists(variantPath)) {
                removedCount++;
                LOG.infof("Deleted variant: %s_%dx%d", uuid, key.getWidth(), key.getHeight());
            }
        }

        Path originalPath = cacheService.getImagesDir().resolve(uuid + WEBP_EXTENSION);
        Files.deleteIfExists(originalPath);
        LOG.infof("Deleted image: %s (%d variants removed)", filename, removedCount);

        return new DeleteResult("deleted", removedCount);
    }

    private Path buildVariantPath(String uuid, ImageVariant key) {
        return cacheService.getImagesDir()
                .resolve(uuid + "_" + key.getWidth() + "x" + key.getHeight() + WEBP_EXTENSION);
    }

    private Path buildTmpPath(String uuid, ImageVariant key) {
        return cacheService.getImagesDir()
                .resolve(uuid + "_" + key.getWidth() + "x" + key.getHeight() + TMP_EXTENSION);
    }

    public record UploadResult(
            @Schema(description = "Filename of the stored WebP image, derived from the original filename.",
                    example = "photo.webp")
            String filename,
            @JsonProperty("alreadyPresent")
            @Schema(description = "True if an image with this name already existed; no new file was written.",
                    example = "false")
            boolean alreadyPresent) {
    }

    public record ServeResult(byte[] bytes, boolean cacheHit) {
    }

    public record DeleteResult(
            @Schema(description = "Deletion status.", example = "deleted", enumeration = {"deleted"})
            String status,
            @Schema(description = "Number of resized variant files that were also deleted.", example = "3")
            int cachedFilesRemoved) {
    }
}
