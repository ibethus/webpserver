package io.webpserver.service;

import io.quarkus.runtime.Startup;
import io.webpserver.config.AppConfig;
import io.webpserver.model.ImageEntry;
import io.webpserver.model.ImageVariant;
import io.webpserver.model.ImagesIndex;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.InvalidObjectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static io.webpserver.service.FilenameUtils.getFilename;

@ApplicationScoped
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);
    public static final String WEBP = ".webp";

    private final ImagesIndex imagesIndex;
    private final AppConfig config;
    private Path imagesDir;

    public CacheService(AppConfig config) {
        this.config = config;
        imagesIndex = new ImagesIndex();
    }

    @Startup
    void init() throws Exception {
        imagesDir = Path.of(config.imagesDir());
        Files.createDirectories(imagesDir);
        LOG.infof("Scanning images directory: %s", imagesDir);
        try (Stream<Path> files = Files.list(imagesDir)) {
            files
                    .forEach(file -> {
                        if (file.toString().endsWith(WEBP)) {
                            try {
                                addFileToCache(file.getFileName().toString());
                            } catch (InvalidObjectException e) {
                                LOG.error("Image name invalid, skipping (%s)".formatted(file.getFileName()));
                            }
                        }
                    });
        }
        LOG.infof("Index rebuilt: %d originals, %d variants", imagesIndex.originalsSize(), imagesIndex.variantsSize());
    }

    public void addFileToCache(String filename) throws InvalidObjectException {
        String filenameNoExt = getFilename(filename);
        String[] s = filenameNoExt.split("_");
        if (s.length > 1) {
            processResizedImage(s);
        } else {
            imagesIndex.addOriginal(s[0]);
        }
    }

    private void processResizedImage(String[] s) {
        String filename = s[0];
        String[] dimensions = s[1].split("x");
        if (dimensions.length != 2) {
            LOG.error("The file %s has an incorrect size, discarding...".formatted(filename));
        } else {
            try {
                int width = Integer.parseInt(dimensions[0]);
                int height = Integer.parseInt(dimensions[1]);
                imagesIndex.addResized(filename, width, height);
            } catch (NumberFormatException e) {
                LOG.error("File %s has incorrect dimensions (%s x %s), discarding...".formatted(filename, dimensions[0], dimensions[1]));
            }
        }
    }

    public void registerImage(String name) {
        imagesIndex.addOriginal(name);
        LOG.infof("Registered original: %s", name);
    }

    public void registerVariant(String name, ImageVariant key) {
        imagesIndex.addResized(name, key);
        LOG.infof("Registered variant: %s_%dx%d", name, key.getWidth(), key.getHeight());
    }


    public Optional<ImageEntry> getEntry(String name) {
        return imagesIndex.getEntry(name);
    }

    public Optional<ImageEntry> removeEntry(String name) {
        Optional<ImageEntry> removed = imagesIndex.removeEntry(name);
        removed.ifPresentOrElse(
                e -> LOG.infof("Removed index entry: %s", name),
                () -> LOG.infof("Remove requested for unknown name: %s", name)
        );
        return removed;
    }

    public Path getImagesDir() {
        return imagesDir;
    }

}
