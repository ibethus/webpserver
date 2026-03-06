package io.webpserver.model;

import io.quarkus.logging.Log;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ImagesIndex {
    private final ConcurrentHashMap<String, ImageEntry> index;

    public ImagesIndex() {
        index = new ConcurrentHashMap<>();
    }

    public void addOriginal(String name) {
        Log.infof("Original image %s added to index", name);
        if (index.containsKey(name)) {
            index.get(name).addVariant(new ImageVariant());
        } else {
            ImageEntry imageEntry = new ImageEntry();
            imageEntry.addVariant(new ImageVariant());
            index.put(name, imageEntry);
        }
    }

    public void addResized(String name, int width, int height) {
        this.addResized(name, new ImageVariant(width, height));
    }

    public void addResized(String name, ImageVariant variant) {
        Log.infof("Image %s, size %d x %d added to index", name, variant.getWidth(), variant.getHeight());
        if (index.containsKey(name)) {
            index.get(name).addVariant(variant);
        } else {
            ImageEntry imageEntry = new ImageEntry();
            imageEntry.addVariant(variant);
            index.put(name, imageEntry);
        }
    }

    public Optional<ImageEntry> getEntry(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index.get(name));
    }

    public Optional<ImageEntry> removeEntry(String name) {
        if (name != null) {
            return Optional.ofNullable(index.remove(name));
        }
        return Optional.empty();
    }

    public int originalsSize() {
        return index.size();
    }

    public void clear() {
        index.clear();
    }

    public long variantsSize() {
        return index.values().stream()
                .flatMap(imgEntry -> imgEntry.getVariants().stream())
                .filter(variant -> !variant.isOriginal())
                .count();
    }
}
