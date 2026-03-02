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

    public String getUuid() {
        return uuid;
    }

    public Set<ImageVariantKey> getVariants() {
        return variants;
    }

    public void addVariant(ImageVariantKey key) {
        variants.add(key);
    }

    public boolean hasVariant(ImageVariantKey key) {
        return variants.contains(key);
    }
}
