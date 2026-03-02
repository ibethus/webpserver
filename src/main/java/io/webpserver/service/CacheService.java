package io.webpserver.service;

import io.quarkus.runtime.StartupEvent;
import io.webpserver.config.AppConfig;
import io.webpserver.model.ImageEntry;
import io.webpserver.model.ImageVariantKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
        "^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?:_(\\d+)x(\\d+))?\\.webp$"
    );
    private static final String TMP_SUFFIX = ".tmp";

    private final ConcurrentHashMap<String, ImageEntry> index = new ConcurrentHashMap<>();
    private final AppConfig config;
    private Path imagesDir;

    @Inject
    public CacheService(AppConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) throws Exception {
        imagesDir = Path.of(config.imagesDir());
        Files.createDirectories(imagesDir);
        LOG.infof("Scanning images directory: %s", imagesDir);

        int[] counts = {0, 0, 0};

        try (Stream<Path> files = Files.list(imagesDir)) {
            files.forEach(path -> {
                String name = path.getFileName().toString();

                if (name.endsWith(TMP_SUFFIX)) {
                    try {
                        Files.deleteIfExists(path);
                        counts[2]++;
                        LOG.debugf("Deleted stale tmp file: %s", name);
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to delete stale tmp file: %s", name);
                    }
                    return;
                }

                Matcher m = FILENAME_PATTERN.matcher(name);
                if (!m.matches()) {
                    LOG.debugf("Ignored unrecognized file: %s", name);
                    return;
                }

                String uuid = m.group(1);
                ImageEntry entry = index.computeIfAbsent(uuid, ImageEntry::new);

                if (m.group(2) != null) {
                    int w = Integer.parseInt(m.group(2));
                    int h = Integer.parseInt(m.group(3));
                    entry.addVariant(new ImageVariantKey(w, h));
                    counts[1]++;
                } else {
                    counts[0]++;
                }
            });
        }

        LOG.infof("Index rebuilt: %d originals, %d variants, %d stale tmp files removed",
                counts[0], counts[1], counts[2]);
    }

    public void registerImage(String uuid) {
        index.putIfAbsent(uuid, new ImageEntry(uuid));
        LOG.debugf("Registered image: %s", uuid);
    }

    public void registerVariant(String uuid, ImageVariantKey key) {
        ImageEntry entry = index.get(uuid);
        if (entry != null) {
            entry.addVariant(key);
            LOG.debugf("Registered variant: %s_%dx%d", uuid, key.width(), key.height());
        }
    }

    public Optional<ImageEntry> getEntry(String uuid) {
        return Optional.ofNullable(index.get(uuid));
    }

    public Optional<ImageEntry> removeEntry(String uuid) {
        Optional<ImageEntry> removed = Optional.ofNullable(index.remove(uuid));
        removed.ifPresentOrElse(
                e -> LOG.debugf("Removed index entry: %s", uuid),
                () -> LOG.debugf("Remove requested for unknown uuid: %s", uuid)
        );
        return removed;
    }

    public Path getImagesDir() {
        return imagesDir;
    }
}
