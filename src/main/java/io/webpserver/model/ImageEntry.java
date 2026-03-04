package io.webpserver.model;

import java.util.HashSet;
import java.util.Set;

public class ImageEntry {
    private final Set<ImageVariant> variants;

    public ImageEntry() {
        this.variants = new HashSet<>();
    }

    public Set<ImageVariant> getVariants() {
        return variants;
    }

    public void addVariant(ImageVariant key) {
        variants.add(key);
    }

    public boolean hasVariant(ImageVariant key) {
        return variants.contains(key);
    }
}
