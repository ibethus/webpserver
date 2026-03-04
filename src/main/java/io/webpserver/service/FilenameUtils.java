package io.webpserver.service;

import jakarta.annotation.Nonnull;

import java.io.InvalidObjectException;

public class FilenameUtils {
    @Nonnull
    static String getFilename(String inputFilename) throws InvalidObjectException {
        if (!inputFilename.contains(".")) {
            throw new InvalidObjectException("Image name must contain the extension");
        }
        return inputFilename.substring(0, inputFilename.lastIndexOf("."));
    }

    /**
     * Derives a safe, filesystem-friendly stem from a raw filename (e.g. from a multipart upload
     * or a URL segment).
     *
     * <p>Rules applied in order:
     * <ol>
     *   <li>Strip the extension (everything from the last {@code .}).</li>
     *   <li>Lowercase the result.</li>
     *   <li>Replace every character outside {@code [a-z0-9\-.]} with {@code -}.</li>
     *   <li>Collapse consecutive {@code -} into a single one.</li>
     *   <li>Trim leading and trailing {@code -}.</li>
     * </ol>
     *
     * @param rawName original filename (with or without extension)
     * @return sanitized stem, never blank and never consisting solely of dots
     * @throws IllegalArgumentException if {@code rawName} is blank or reduces to empty after sanitisation
     */
    @Nonnull
    static String sanitize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        String stem = rawName.contains(".")
                ? rawName.substring(0, rawName.lastIndexOf("."))
                : rawName;
        String lower = stem.toLowerCase();
        String cleaned = lower.replaceAll("[^a-z0-9\\-.]", "-");
        String collapsed = cleaned.replaceAll("-{2,}", "-");
        String trimmed = collapsed.replaceAll("^-+|-+$", "");
        if (trimmed.isEmpty() || trimmed.replace(".", "").isEmpty()) {
            throw new IllegalArgumentException("Filename reduces to empty after sanitization: " + rawName);
        }
        return trimmed;
    }
}
