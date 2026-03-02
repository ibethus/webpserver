package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class AnimatedResizeException extends WebApplicationException {
    public AnimatedResizeException() {
        super(Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Resizing animated WebP is not supported."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
