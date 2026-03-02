package io.webpserver.exception;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "ErrorResponse")
public record ErrorResponse(
    @Schema(description = "Human-readable description of the error.",
            example = "Unsupported image format. Accepted: jpeg, png, webp, gif.")
    String error) {}
