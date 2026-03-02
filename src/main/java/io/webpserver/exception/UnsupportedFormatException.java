package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class UnsupportedFormatException extends WebApplicationException {
    public UnsupportedFormatException() {
        super(Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Image format not supported. Accepted: jpeg, png, gif, webp."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
