package io.webpserver.model;

import java.util.Objects;

public class ImageVariant {
    private final boolean isOriginal;
    private final int width;
    private final int height;

    public ImageVariant() {
        this.isOriginal = true;
        this.width = 0;
        this.height = 0;
    }

    public ImageVariant(int width, int height) {
        this.isOriginal = false;
        this.width = width;
        this.height = height;
    }

    public boolean isOriginal() {
        return isOriginal;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ImageVariant that = (ImageVariant) o;
        return isOriginal == that.isOriginal && width == that.width && height == that.height;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isOriginal, width, height);
    }
}
