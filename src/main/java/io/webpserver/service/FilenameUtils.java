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
}
