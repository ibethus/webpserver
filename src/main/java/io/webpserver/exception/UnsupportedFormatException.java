package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class UnsupportedFormatException extends WebApplicationException {
    public UnsupportedFormatException() {
        super(Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Unsupported image format. Accepted: jpeg, png, webp, gif."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
